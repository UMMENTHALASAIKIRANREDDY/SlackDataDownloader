package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.ExportAllDmsRequest;
import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.slack.ConversationsListResponse;
import com.cloudfuze.slackexport.slack.SlackApiClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

/**
 * "Export All My DMs": list all DM/MPIM channels for the user token, dedupe, fetch messages, build same ZIP format.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportAllDmsService {

    private static final String TYPES_IM_MPIM = "im,mpim";
    private static final int LIST_PAGE_SIZE = 200;

    private final SlackApiClient slackApiClient;
    private final SlackFetchService slackFetchService;
    private final ExportService exportService;
    private final SlackTokenConfig slackTokenConfig;

    public Mono<byte[]> exportAllUserDms(ExportAllDmsRequest request) {
        String userToken = slackTokenConfig.getToken();
        String adminToken = slackTokenConfig.getAdminToken();
        if (userToken == null || userToken.isBlank()) {
            return Mono.error(new IllegalArgumentException("Slack user token is required. Set slack.tokens.user in config."));
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

        Mono<String> authUserIdMono = slackApiClient.withRateLimitRetry(slackApiClient.authTest(userToken));

        return authUserIdMono.flatMap(authUserId -> fetchAllDmChannels(userToken)
                .filter(this::isImOrMpim)
                .collectList()
                .map(this::dedupeByChannelId)
                .flatMap(uniqueChannels -> {
                    if (uniqueChannels.isEmpty()) {
                        return Mono.error(new IllegalStateException("No DMs found for this user"));
                    }
                    log.info("Export all DMs: {} unique channels (after dedupe)", uniqueChannels.size());
                    return Mono.just(uniqueChannels);
                })
                .flatMap(uniqueChannels -> fetchAllChannelsWithMessages(
                        adminToken, userToken, uniqueChannels, exportMode, fromDate, toDate))
                .map(channels -> channels.stream()
                        .filter(c -> c.getMessages() != null && !c.getMessages().isEmpty())
                        .toList())
                .flatMap(channelsWithMessages -> {
                    if (channelsWithMessages.isEmpty()) {
                        return Mono.error(new IllegalStateException("No messages found in selected date range"));
                    }
                    LocalDate zipFrom;
                    LocalDate zipTo;
                    if ("CUSTOM".equals(exportMode)) {
                        zipFrom = LocalDate.parse(fromDate);
                        zipTo = LocalDate.parse(toDate);
                    } else {
                        zipFrom = null;
                        zipTo = null;
                    }
                    if (zipFrom == null || zipTo == null) {
                        zipFrom = channelsWithMessages.stream()
                                .flatMap(c -> c.getMessages() == null ? Stream.empty() : c.getMessages().stream())
                                .filter(m -> m.has("ts"))
                                .map(m -> tsToLocalDate(m.get("ts").asText()))
                                .min(LocalDate::compareTo)
                                .orElse(LocalDate.now());
                        zipTo = LocalDate.now();
                    }
                    try {
                        byte[] zip = exportService.buildZip(
                                channelsWithMessages,
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

    private Flux<JsonNode> fetchAllDmChannels(String token) {
        Mono<ConversationsListResponse> first = slackApiClient.withRateLimitRetry(
                slackApiClient.conversationsList(token, TYPES_IM_MPIM, LIST_PAGE_SIZE, null, null));
        return first
                .expand(resp -> {
                    if (!resp.isHasMore()) return Mono.empty();
                    String cursor = resp.getNextCursor();
                    if (cursor == null || cursor.isBlank()) return Mono.empty();
                    return slackApiClient.withRateLimitRetry(
                            slackApiClient.conversationsList(token, TYPES_IM_MPIM, LIST_PAGE_SIZE, cursor, null));
                })
                .flatMap(resp -> Flux.fromIterable(resp.getChannels() != null ? resp.getChannels() : List.of()));
    }

    private boolean isImOrMpim(JsonNode ch) {
        boolean im = ch.has("is_im") && ch.get("is_im").asBoolean();
        boolean mpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
        return im || mpim;
    }

    private List<JsonNode> dedupeByChannelId(List<JsonNode> channels) {
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        for (JsonNode ch : channels) {
            if (ch.has("id")) byId.put(ch.get("id").asText(), ch);
        }
        return new ArrayList<>(byId.values());
    }

    private Mono<List<ChannelExportData>> fetchAllChannelsWithMessages(
            String adminToken,
            String userToken,
            List<JsonNode> uniqueChannels,
            String exportMode,
            String fromDate,
            String toDate) {
        long nowEpoch = Instant.now().getEpochSecond();
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();

        return Flux.fromIterable(uniqueChannels)
                .flatMap(ch -> {
                    String channelId = ch.has("id") ? ch.get("id").asText() : null;
                    if (channelId == null) return Mono.empty();

                    String startTs;
                    String endTs = String.valueOf(nowEpoch);
                    if ("CUSTOM".equals(exportMode)) {
                        ZonedDateTime start = LocalDate.parse(fromDate).atStartOfDay(ZoneOffset.UTC);
                        ZonedDateTime end = LocalDate.parse(toDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
                        startTs = String.valueOf(start.toEpochSecond());
                        endTs = String.valueOf(end.toEpochSecond());
                    } else {
                        long created = ch.has("created") ? ch.get("created").asLong() : 0L;
                        startTs = String.valueOf(created);
                    }

                    return slackFetchService.fetchChannelWithTokenFallback(
                                    adminToken, userToken, channelId, startTs, endTs, null)
                            .onErrorResume(e -> {
                                firstFailure.compareAndSet(null, e);
                                log.warn("Skipping channel {}: {}", channelId, e.getMessage());
                                return Mono.empty();
                            });
                })
                .collectList()
                .map(list -> list);
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
