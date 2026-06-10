package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.AllDmsCountResponse;
import com.cloudfuze.slackexport.api.ExportAllDmsRequest;
import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.slack.ConversationsListResponse;
import com.cloudfuze.slackexport.slack.SlackApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.stream.Stream;

/**
 * "Export All My DMs" → Entire History: strict execution order.
 * STEP 1: Fetch ALL DMs from first token (cursor pagination).
 * STEP 2: Fetch members (conversations.members) for each token1 DM.
 * STEP 3: Fetch full message history (history + replies + files + reactions) for each token1 DM.
 * STEP 4: Fetch ALL DMs from second token.
 * STEP 5: Remove duplicates (skip token2 DMs whose channelId exists in token1).
 * STEP 6: For each unique token2 DM: fetch members, then full history.
 * STEP 7–9: Build dms.json, mpims.json, date files, ZIP (using ExportService.buildZip).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportAllDmsDualTokenService {

    private static final String TYPES_IM_MPIM = "im,mpim";
    private static final int LIST_PAGE_SIZE = 200;
    /** Process one channel at a time to avoid Slack 429 rate limits (each 429 adds ~10s wait). */
    private static final int CONCURRENCY_CHANNEL_FETCH = 1;
    private static final int CONCURRENCY_MEMBERS = 2;

    /** Delay (ms) between starting each channel to stay under Slack rate limits. Default 1.2s. Set to 0 to disable. */
    @Value("${export.slack-channel-delay-ms:1200}")
    private long slackChannelDelayMs = 1200;

    private final SlackApiClient slackApiClient;
    private final SlackFetchService slackFetchService;
    private final ExportService exportService;
    private final SlackTokenConfig slackTokenConfig;

    /** Wrapper for a DM from conversations.list; members filled in STEP 2. token = which token listed this channel. */
    private static final class ChannelWrapper {
        final String id;
        final boolean isIm;
        final boolean isMpim;
        final long created;
        final String name; // MPIM name from list, null for DM
        final String token; // token that listed this channel (for fetch)
        List<String> members = new ArrayList<>();

        ChannelWrapper(String id, boolean isIm, boolean isMpim, long created, String name, String token) {
            this.id = id;
            this.isIm = isIm;
            this.isMpim = isMpim;
            this.created = created;
            this.name = name;
            this.token = token;
        }
    }

    public Mono<byte[]> exportAllUserDmsDualToken(ExportAllDmsRequest request) {
        List<String> tokens = slackTokenConfig.getAllTokensForFetch();
        if (tokens.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one Slack token is required. Set slack.tokens.admin and/or slack.tokens.user in config."));
        }

        String exportMode = request.getExportMode() != null ? request.getExportMode().trim().toUpperCase() : "";
        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;

        if ("CUSTOM".equals(exportMode)) {
            if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
                return Mono.error(new IllegalArgumentException("fromDate and toDate are required when exportMode is CUSTOM."));
            }
            try {
                LocalDate from = LocalDate.parse(fromDate);
                LocalDate to = LocalDate.parse(toDate);
                if (to.isBefore(from)) {
                    return Mono.error(new IllegalArgumentException("toDate must be on or after fromDate."));
                }
            } catch (Exception e) {
                return Mono.error(new IllegalArgumentException("Invalid fromDate or toDate: " + e.getMessage()));
            }
        }

        String adminToken = slackTokenConfig.getAdminToken();
        String userToken = slackTokenConfig.getToken();
        String authToken = tokens.get(0);
        Mono<String> authUserIdMono = slackApiClient.withRateLimitRetry(slackApiClient.authTest(authToken));

        // STEP 1 — Fetch DMs from each token, merge and deduplicate by channel ID (first token wins)
        Mono<Map<String, ChannelWrapper>> allDMsMono = fetchAndMergeDmsFromAllTokens(tokens);

        return authUserIdMono
                .flatMap(authUserId -> allDMsMono
                        .flatMap(allDMs -> {
                            log.info("Export All DMs: merged {} unique DM(s) from {} token(s).", allDMs.size(), tokens.size());
                            if (allDMs.isEmpty()) {
                                return Mono.error(new IllegalStateException(
                                        "No DMs found from any token. Check slack.tokens.admin and/or slack.tokens.user in application.yml."));
                            }
                            return Mono.just(allDMs);
                        })
                        .flatMap(allDMs -> {
                            // STEP 2 — Fetch members for each DM (use token that listed the channel)
                            return fetchMembersForAll(allDMs).thenReturn(allDMs);
                        })
                        .flatMap(allDMs -> {
                            // STEP 3 — Fetch full message history for each DM (primary token = listed token, fallback = admin or first other)
                            long nowEpoch = Instant.now().getEpochSecond();
                            return fetchChannelsWithTokenPerChannel(allDMs, adminToken, userToken, tokens, nowEpoch, exportMode, fromDate, toDate)
                                    .collectList();
                        })
                        .flatMap(allChannels -> {
                            long withMessages = allChannels.stream()
                                    .filter(c -> c.getMessages() != null && !c.getMessages().isEmpty())
                                    .count();
                            log.info("Export All DMs: total channels={}, channels with messages={}",
                                    allChannels.size(), withMessages);
                            if (allChannels.isEmpty()) {
                                return Mono.error(new IllegalStateException(
                                        "No DMs found. All channel(s) were skipped. Check backend logs for 'Skipping channel' to see why."));
                            }
                            LocalDate zipFrom;
                            LocalDate zipTo;
                            if ("CUSTOM".equals(exportMode)) {
                                zipFrom = LocalDate.parse(fromDate);
                                zipTo = LocalDate.parse(toDate);
                            } else {
                                zipFrom = allChannels.stream()
                                        .flatMap(c -> c.getMessages() == null ? Stream.empty() : c.getMessages().stream())
                                        .filter(m -> m.has("ts"))
                                        .map(m -> tsToLocalDate(m.get("ts").asText()))
                                        .min(LocalDate::compareTo)
                                        .orElse(LocalDate.now());
                                zipTo = LocalDate.now();
                            }
                            try {
                                byte[] zip = exportService.buildZip(
                                        allChannels,
                                        authUserId,
                                        zipFrom.toString(),
                                        zipTo.toString(),
                                        null);
                                return Mono.just(zip);
                            } catch (IOException e) {
                                return Mono.error(new RuntimeException("Export build failed", e));
                            }
                        }));
    }

    /** Fetch DMs from each token and merge; first token to list a channel wins (deduplicate by channel ID). */
    private Mono<Map<String, ChannelWrapper>> fetchAndMergeDmsFromAllTokens(List<String> tokens) {
        Map<String, ChannelWrapper> merged = new LinkedHashMap<>();
        Mono<Map<String, ChannelWrapper>> chain = Mono.just(merged);
        for (String token : tokens) {
            String t = token;
            chain = chain.flatMap(acc -> fetchAllDmChannelsAsMap(t)
                    .map(fetched -> {
                        for (Map.Entry<String, ChannelWrapper> e : fetched.entrySet()) {
                            acc.putIfAbsent(e.getKey(), e.getValue());
                        }
                        return acc;
                    }));
        }
        return chain;
    }

    /** Fetch channels using per-channel primary token (token that listed it) with fallback. */
    private Flux<ChannelExportData> fetchChannelsWithTokenPerChannel(
            Map<String, ChannelWrapper> wrappers,
            String adminToken,
            String userToken,
            List<String> allTokens,
            long nowEpoch,
            String exportMode,
            String fromDate,
            String toDate) {
        Flux<Map.Entry<String, ChannelWrapper>> flux = Flux.fromIterable(wrappers.entrySet());
        if (slackChannelDelayMs > 0) {
            flux = flux.delayElements(Duration.ofMillis(slackChannelDelayMs));
        }
        return flux.flatMap(e -> {
                    String channelId = e.getKey();
                    ChannelWrapper w = e.getValue();
                    String primaryToken = w.token;
                    String fallbackToken = allTokens.stream().filter(t -> !t.equals(primaryToken)).findFirst().orElse(null);
                    String startTs;
                    String endTs = String.valueOf(nowEpoch);
                    if ("CUSTOM".equals(exportMode)) {
                        ZonedDateTime start = LocalDate.parse(fromDate).atStartOfDay(ZoneOffset.UTC);
                        ZonedDateTime end = LocalDate.parse(toDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
                        startTs = String.valueOf(start.toEpochSecond());
                        endTs = String.valueOf(end.toEpochSecond());
                    } else {
                        startTs = String.valueOf(w.created);
                    }
                    List<String> preFetched = (w.members != null && !w.members.isEmpty()) ? w.members : List.of();
                    return slackFetchService.fetchChannelWithPreferredTokenAndPreFetchedMembers(
                            primaryToken, fallbackToken, channelId, startTs, endTs, preFetched, null)
                            .onErrorResume(err -> {
                                log.warn("Skipping channel {}: {}", channelId, err.getMessage());
                                return Mono.empty();
                            });
                }, CONCURRENCY_CHANNEL_FETCH);
    }

    /**
     * Fetch DM counts from all tokens and return unique count (no duplicates by channel ID).
     * Supports admin-only, user-only, or multiple tokens.
     */
    public Mono<AllDmsCountResponse> getDmCountDualToken() {
        List<String> tokens = slackTokenConfig.getAllTokensForFetch();
        if (tokens.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one Slack token is required. Set slack.tokens.admin and/or slack.tokens.user in config."));
        }
        return fetchAndMergeDmsFromAllTokens(tokens)
                .map(merged -> {
                    int oneOnOne = 0;
                    int groupDm = 0;
                    for (ChannelWrapper w : merged.values()) {
                        if (w.isIm) oneOnOne++;
                        else if (w.isMpim) groupDm++;
                    }
                    return AllDmsCountResponse.builder()
                            .uniqueTotalCount(merged.size())
                            .oneOnOneCount(oneOnOne)
                            .groupDmCount(groupDm)
                            .fromFirstToken(merged.size())
                            .fromSecondToken(0)
                            .build();
                });
    }

    /** Fetch all DM channels (conversations.list, cursor pagination), store as Map<channelId, ChannelWrapper>. */
    private Mono<Map<String, ChannelWrapper>> fetchAllDmChannelsAsMap(String token) {
        if (token == null || token.isBlank()) {
            return Mono.just(Collections.emptyMap());
        }
        String t = token;
        Mono<ConversationsListResponse> first = slackApiClient.withRateLimitRetry(
                slackApiClient.conversationsList(token, TYPES_IM_MPIM, LIST_PAGE_SIZE, null, null));
        return first
                .expand(resp -> {
                    String cursor = resp.getNextCursor();
                    if (cursor == null || cursor.isBlank()) return Mono.empty();
                    return slackApiClient.withRateLimitRetry(
                            slackApiClient.conversationsList(t, TYPES_IM_MPIM, LIST_PAGE_SIZE, cursor, null));
                })
                .reduce(new LinkedHashMap<String, ChannelWrapper>(), (map, resp) -> {
                    if (resp.getChannels() != null) {
                        for (JsonNode ch : resp.getChannels()) {
                            if (!ch.has("id")) continue;
                            boolean im = ch.has("is_im") && ch.get("is_im").asBoolean();
                            boolean mpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                            if (!im && !mpim) continue;
                            String id = ch.get("id").asText();
                            long created = ch.has("created") ? ch.get("created").asLong() : 0L;
                            String name = (mpim && ch.has("name")) ? ch.get("name").asText() : null;
                            map.put(id, new ChannelWrapper(id, im, mpim, created, name, t));
                        }
                    }
                    return map;
                });
    }

    /** Fetch conversations.members for each channel; use token from ChannelWrapper (token that listed it). */
    private Mono<Void> fetchMembersForAll(Map<String, ChannelWrapper> wrappers) {
        if (wrappers.isEmpty()) return Mono.empty();
        return Flux.fromIterable(wrappers.entrySet())
                .flatMap(e -> {
                    String channelId = e.getKey();
                    ChannelWrapper w = e.getValue();
                    String token = w.token;
                    return slackApiClient.withRateLimitRetry(slackApiClient.conversationsMembers(token, channelId))
                            .doOnNext(members -> w.members = members != null ? members : List.of())
                            .onErrorResume(err -> {
                                log.warn("conversations.members failed for {}: {}", channelId, err.getMessage());
                                return Mono.empty();
                            })
                            .then();
                }, CONCURRENCY_MEMBERS)
                .then();
    }

    private static LocalDate tsToLocalDate(String ts) {
        try {
            double sec = Double.parseDouble(ts);
            return Instant.ofEpochSecond((long) sec).atOffset(ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            return LocalDate.EPOCH;
        }
    }
}
