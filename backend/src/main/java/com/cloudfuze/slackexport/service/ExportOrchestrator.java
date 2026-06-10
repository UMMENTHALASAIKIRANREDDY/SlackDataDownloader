package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.ExportRequest;
import com.cloudfuze.slackexport.api.GroupDmEntry;
import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.slack.SlackApiClient;
import com.cloudfuze.slackexport.slack.SlackApiException;
import com.cloudfuze.slackexport.slack.SlackRateLimitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fetches both one-on-one DMs and Group DMs, attaches group names from request, builds single ZIP.
 * Token selection: try conversations.history with ADMIN token; on channel_not_found / not_authed / not_in_channel
 * retry with USER token. Skip channel ONLY if BOTH tokens fail. No user-based or members-based access checks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportOrchestrator {

    private final SlackFetchService slackFetchService;
    private final SlackApiClient slackApiClient;
    private final ExportService exportService;
    private final SlackTokenConfig slackTokenConfig;

    /** Delay (ms) between starting each channel to avoid Slack 429 rate limits. Process channels sequentially. */
    @Value("${export.slack-channel-delay-ms:1200}")
    private long slackChannelDelayMs = 1200;

    public Mono<byte[]> runExport(ExportRequest request) {
        String requestUserToken = request.getSlackAccessToken() != null ? request.getSlackAccessToken().trim() : null;
        String requestAdminToken = request.getAdminToken() != null ? request.getAdminToken().trim() : null;
        String userToken = (requestUserToken != null && !requestUserToken.isEmpty()) ? requestUserToken : slackTokenConfig.getToken();
        String adminToken = (requestAdminToken != null && !requestAdminToken.isEmpty()) ? requestAdminToken : slackTokenConfig.getAdminToken();
        if ((userToken == null || userToken.isBlank()) && (adminToken == null || adminToken.isBlank())) {
            return Mono.error(new IllegalArgumentException("At least one Slack token is required. Set slack.tokens.admin and/or slack.tokens.user in config, or send adminToken/slackAccessToken in request."));
        }
        String startTs = request.getStartTs();
        String endTs = request.getEndTs();
        Integer limit = request.getMessageLimitPerChannel();
        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;

        // Prefer form-submitted dmEntries/groupDms so the channel IDs the user entered are always used.
        List<String> dmChannelIds = new ArrayList<>();
        if (request.getDmEntries() != null && !request.getDmEntries().isEmpty()) {
            for (var e : request.getDmEntries()) {
                if (e != null && e.getChannelId() != null && !e.getChannelId().isBlank()) {
                    dmChannelIds.add(e.getChannelId().trim());
                }
            }
        } else if (request.getDmChannelIds() != null && !request.getDmChannelIds().isEmpty()) {
            for (String id : request.getDmChannelIds()) {
                if (id != null && !id.isBlank()) dmChannelIds.add(id.trim());
            }
        } else {
            dmChannelIds.addAll(slackTokenConfig.getDmChannelIdsFromConfig());
        }

        List<String> mpimChannelIds = new ArrayList<>();
        if (request.getGroupDms() != null && !request.getGroupDms().isEmpty()) {
            for (GroupDmEntry g : request.getGroupDms()) {
                if (g != null && g.getChannelId() != null && !g.getChannelId().isBlank()) {
                    mpimChannelIds.add(g.getChannelId().trim());
                }
            }
        } else if (request.getMpimChannelIds() != null && !request.getMpimChannelIds().isEmpty()) {
            for (String id : request.getMpimChannelIds()) {
                if (id != null && !id.isBlank()) mpimChannelIds.add(id.trim());
            }
        } else {
            mpimChannelIds.addAll(slackTokenConfig.getMpimChannelIdsFromConfig());
        }

        // Unique channel IDs (no duplicate fetches); preserve user intent: which were submitted as one-on-one DM
        Set<String> exportAsDmChannelIds = new HashSet<>(dmChannelIds);
        List<String> allChannelIds = new ArrayList<>(dmChannelIds);
        for (String id : mpimChannelIds) {
            if (id != null && !id.isBlank() && !exportAsDmChannelIds.contains(id)) {
                allChannelIds.add(id);
            }
        }

        if (allChannelIds.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one DM or MPIM channel is required. Provide dmChannelIds and/or mpimChannelIds (or dmEntries/groupDms)."));
        }

        log.info("runExport: dmChannelIds={}, mpimChannelIds={}, exportAsDm={}, fromDate={}, toDate={}", dmChannelIds, mpimChannelIds, exportAsDmChannelIds, fromDate, toDate);

        String authToken = (userToken != null && !userToken.isBlank()) ? userToken : adminToken;
        Mono<String> authUserIdMono = slackApiClient.withRateLimitRetry(slackApiClient.authTest(authToken));

        return authUserIdMono
                .flatMap(authUserId -> {
                    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
                    Flux<String> channelFlux = Flux.fromIterable(allChannelIds);
                    if (slackChannelDelayMs > 0) {
                        channelFlux = channelFlux.delayElements(Duration.ofMillis(slackChannelDelayMs));
                    }
                    return channelFlux.concatMap(channelId -> slackFetchService.fetchChannelWithTokenFallback(
                                            adminToken, userToken, channelId, startTs, endTs, limit)
                                    .map(ch -> new ChannelResult(channelId, ch, null))
                                    .retryWhen(reactor.util.retry.Retry.backoff(Integer.MAX_VALUE, Duration.ofSeconds(30))
                                            .filter(ExportOrchestrator::isRetryableError)
                                            .doBeforeRetry(s -> log.warn("Channel {} transient error (attempt {}); retrying in 30s: {}", channelId, s.totalRetries() + 1, s.failure().getMessage())))
                                    .onErrorResume(e -> {
                                        if (isRetryableError(e)) {
                                            log.error("Channel {} failed after exhausting retries: {}", channelId, e.getMessage());
                                        }
                                        firstFailure.compareAndSet(null, e);
                                        String slackErr = e instanceof SlackApiException ex ? ex.getSlackError() : null;
                                        String reason = slackErr != null ? slackErr : (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                                        log.warn("Skipping channel {}: {}", channelId, reason);
                                        return Mono.just(new ChannelResult(channelId, null, reason));
                                    }))
                            .collectList()
                            .filter(results -> results.stream().anyMatch(r -> r.data != null))
                            .switchIfEmpty(Mono.defer(() -> {
                                Throwable first = firstFailure.get();
                                String detail = first != null ? " First failure: " + first.getMessage() : "";
                                return Mono.error(new IllegalStateException("No channels could be exported. All channels were skipped (both tokens failed)." + detail));
                            }))
                            .flatMap(results -> {
                                List<ChannelExportData> channels = results.stream()
                                        .filter(r -> r.data != null)
                                        .map(r -> r.data)
                                        .toList();
                                List<Map<String, String>> skipped = results.stream()
                                        .filter(r -> r.errorReason != null)
                                        .map(r -> Map.<String, String>of("channelId", r.channelId, "reason", r.errorReason))
                                        .toList();
                                try {
                                    byte[] zip = exportService.buildZip(channels, authUserId, fromDate, toDate, exportAsDmChannelIds, skipped);
                                    return Mono.just(zip);
                                } catch (IOException e) {
                                    log.error("Export build failed: {}", e.getMessage());
                                    return Mono.error(new RuntimeException("Export build failed", e));
                                } catch (Exception e) {
                                    log.error("Validation or export failed: {}", e.getMessage());
                                    return Mono.error(e);
                                }
                            });
                });
    }

    private record ChannelResult(String channelId, ChannelExportData data, String errorReason) {}

    /** Retry on rate limit and transient network errors; do not retry permanent Slack errors (not_in_channel, etc.). */
    static boolean isRetryableError(Throwable e) {
        if (e == null) return false;
        if (e instanceof SlackRateLimitException) return true;
        if (e instanceof SlackApiException) return false;
        Throwable cause = e.getCause();
        if (cause != null && isRetryableError(cause)) return true;
        String msg = e.getMessage();
        if (msg != null) {
            String lower = msg.toLowerCase();
            if (lower.contains("connection") && (lower.contains("reset") || lower.contains("closed") || lower.contains("before response"))) return true;
            if (lower.contains("premature close") || lower.contains("connection refused")) return true;
        }
        return e instanceof IOException;
    }
}
