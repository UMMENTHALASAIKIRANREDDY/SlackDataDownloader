package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.ExportAllDmsRequest;
import com.cloudfuze.slackexport.api.ExportPrivateChannelsManualRequest;
import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.slack.ConversationsInfoResponse;
import com.cloudfuze.slackexport.slack.ConversationsListResponse;
import com.cloudfuze.slackexport.slack.SlackApiClient;
import com.cloudfuze.slackexport.slack.SlackApiException;
import com.cloudfuze.slackexport.slack.SlackRateLimitException;
import com.cloudfuze.slackexport.slack.UsersInfoResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Dual-token private channel export. Follows same logic as DM dual-token.
 * STEP 1: Fetch private channels from admin token.
 * STEP 2: Fetch private channels from user token.
 * STEP 3: Deduplicate (admin first, add user-only).
 * STEP 4: Fetch members for each channel.
 * STEP 5: Fetch messages (entire history or date range).
 * STEP 6: Generate groups.json, users.json, channels.json.
 * STEP 7: Build ZIP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PrivateChannelExportServiceDualToken {

    private static final String TYPES_PRIVATE_CHANNEL = "private_channel";
    private static final int LIST_PAGE_SIZE = 200;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern UNSAFE_FOLDER_CHARS = Pattern.compile("[\\\\/:*?\"<>|]+");

    @Value("${export.slack-channel-delay-ms:1200}")
    private long slackChannelDelayMs = 1200;

    private final SlackApiClient slackApiClient;
    private final SlackFetchService slackFetchService;
    private final SlackTokenConfig slackTokenConfig;
    private final ExportMessageNormalizer exportMessageNormalizer;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    /** Wrapper for private channel from conversations.list. token = which token listed this channel. */
    private static final class PrivateChannelWrapper {
        final String id;
        final String name;
        final long created;
        final String creator;
        final boolean isArchived;
        final boolean isGeneral;
        final JsonNode topic;
        final JsonNode purpose;
        final boolean fromAdmin;
        final String token;
        List<String> members = new ArrayList<>();

        PrivateChannelWrapper(String id, String name, long created, String creator,
                             boolean isArchived, boolean isGeneral, JsonNode topic, JsonNode purpose, boolean fromAdmin, String token) {
            this.id = id;
            this.name = name;
            this.created = created;
            this.creator = creator;
            this.isArchived = isArchived;
            this.isGeneral = isGeneral;
            this.topic = topic;
            this.purpose = purpose;
            this.fromAdmin = fromAdmin;
            this.token = token;
        }
    }

    public Mono<byte[]> exportPrivateChannelsDualToken(ExportAllDmsRequest request) {
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

        // STEP 1–3 — Fetch from each token and deduplicate by channel ID (first token wins)
        Mono<Map<String, PrivateChannelWrapper>> finalChannelsMono = fetchAndMergePrivateChannelsFromAllTokens(tokens);

        return finalChannelsMono
                .flatMap(finalPrivateChannels -> {
                    if (finalPrivateChannels.isEmpty()) {
                        return Mono.error(new IllegalStateException("No private channels found."));
                    }
                    log.info("STEP 1–3 done. Merged {} unique private channel(s) from {} token(s).", finalPrivateChannels.size(), tokens.size());
                    return fetchMembersForAll(finalPrivateChannels)
                            .thenReturn(finalPrivateChannels);
                })
                .flatMap(finalPrivateChannels -> {
                    long nowEpoch = Instant.now().getEpochSecond();
                    String startTs;
                    String endTs = String.valueOf(nowEpoch);
                    if ("CUSTOM".equals(exportMode)) {
                        ZonedDateTime start = LocalDate.parse(fromDate).atStartOfDay(ZoneOffset.UTC);
                        ZonedDateTime end = LocalDate.parse(toDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
                        startTs = String.valueOf(start.toEpochSecond());
                        endTs = String.valueOf(end.toEpochSecond());
                    } else {
                        startTs = "0";
                    }

                    String finalStartTs = startTs;
                    String finalEndTs = endTs;
                    Flux<Map.Entry<String, PrivateChannelWrapper>> flux = Flux.fromIterable(finalPrivateChannels.entrySet());
                    if (slackChannelDelayMs > 0) {
                        flux = flux.delayElements(java.time.Duration.ofMillis(slackChannelDelayMs));
                    }
                    return flux
                            .flatMap(entry -> {
                                PrivateChannelWrapper w = entry.getValue();
                                String token = w.token != null ? w.token : (slackTokenConfig.getToken() != null ? slackTokenConfig.getToken() : slackTokenConfig.getAdminToken());
                                return slackApiClient.withRateLimitRetry(
                                        slackFetchService.fetchPrivateChannelMessages(token, w.id, finalStartTs, finalEndTs))
                                        .map(msgs -> {
                                            List<JsonNode> normalized = new ArrayList<>();
                                            for (JsonNode m : msgs) {
                                                normalized.add(exportMessageNormalizer.normalizeMessage(m));
                                            }
                                            return PrivateChannelExportData.builder()
                                                    .channelId(w.id)
                                                    .name(w.name)
                                                    .created(w.created)
                                                    .creator(w.creator)
                                                    .isArchived(w.isArchived)
                                                    .isGeneral(w.isGeneral)
                                                    .topic(w.topic)
                                                    .purpose(w.purpose)
                                                    .members(w.members)
                                                    .messages(normalized)
                                                    .tokenSource(token)
                                                    .build();
                                        })
                                        .onErrorResume(err -> {
                                            log.warn("Skipping channel {}: {}", w.id, err.getMessage());
                                            return Mono.just(PrivateChannelExportData.builder()
                                                    .channelId(w.id)
                                                    .name(w.name)
                                                    .created(w.created)
                                                    .creator(w.creator)
                                                    .isArchived(w.isArchived)
                                                    .isGeneral(w.isGeneral)
                                                    .topic(w.topic)
                                                    .purpose(w.purpose)
                                                    .members(w.members)
                                                    .messages(List.of())
                                                    .tokenSource(token)
                                                    .build());
                                        });
                            })
                            .collectList()
                            .flatMap(channelsWithMessages -> {
                                List<PrivateChannelExportData> withMessages = channelsWithMessages.stream()
                                        .filter(c -> c.getMessages() != null && !c.getMessages().isEmpty())
                                        .toList();
                                if (withMessages.isEmpty()) {
                                    return Mono.error(new IllegalStateException("No messages found in selected range."));
                                }
                                // Fetch users for users.json
                                Set<String> allUserIds = new LinkedHashSet<>();
                                for (PrivateChannelWrapper w : finalPrivateChannels.values()) {
                                    allUserIds.addAll(w.members);
                                }
                                String token = slackTokenConfig.getToken();
                                if (token == null || token.isBlank()) token = slackTokenConfig.getAdminToken();
                                String finalToken = token;
                                return Flux.fromIterable(allUserIds)
                                        .flatMap(uid -> slackApiClient.withRateLimitRetry(slackApiClient.usersInfo(finalToken, uid))
                                                .map(UsersInfoResponse::getUser)
                                                .filter(Objects::nonNull)
                                                .map(usr -> {
                                                    ObjectNode u = objectMapper.createObjectNode();
                                                    u.put("id", usr.has("id") ? usr.get("id").asText() : uid);
                                                    u.put("name", usr.has("name") ? usr.get("name").asText() : uid);
                                                    u.set("profile", usr.has("profile") ? usr.get("profile") : objectMapper.createObjectNode());
                                                    u.put("real_name", usr.has("real_name") ? usr.get("real_name").asText() : "");
                                                    return u;
                                                })
                                                .onErrorResume(err -> {
                                                    log.warn("users.info failed for {}: {}", uid, err.getMessage());
                                                    return Mono.empty();
                                                }))
                                        .collectList()
                                        .map(usersList -> {
                                            @SuppressWarnings("unchecked")
                                            List<ObjectNode> userNodes = (List<ObjectNode>) (List<?>) usersList;
                                            try {
                                                return buildZip(withMessages, finalPrivateChannels, userNodes, fromDate, toDate, exportMode);
                                            } catch (IOException e) {
                                                throw new RuntimeException("ZIP build failed", e);
                                            }
                                        });
                            });
                });
    }

    /**
     * Manual export: user provides channel IDs and date range. Fetches each channel using dual-token (admin first, then user).
     * Supports admin-only: when only admin token is set, uses admin token for all fetches.
     */
    public Mono<byte[]> exportPrivateChannelsManual(ExportPrivateChannelsManualRequest request) {
        String adminToken = slackTokenConfig.getAdminToken();
        String userToken = slackTokenConfig.getToken();
        if ((adminToken == null || adminToken.isBlank()) && (userToken == null || userToken.isBlank())) {
            return Mono.error(new IllegalArgumentException("At least one Slack token is required. Set slack.tokens.admin and/or slack.tokens.user in config."));
        }

        List<String> channelIds = request.getChannelIds() != null
                ? request.getChannelIds().stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().toList()
                : List.of();
        if (channelIds.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one private channel ID is required."));
        }

        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            return Mono.error(new IllegalArgumentException("fromDate and toDate are required."));
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

        ZonedDateTime start = LocalDate.parse(fromDate).atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime end = LocalDate.parse(toDate).plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
        String startTs = String.valueOf(start.toEpochSecond());
        String endTs = String.valueOf(end.toEpochSecond());

        String token1 = (adminToken != null && !adminToken.isBlank()) ? adminToken : userToken;
        String token2 = userToken;

        // For each channel: fetch info (admin first, then user), build wrapper, fetch members, fetch messages.
        // Sequential (concatMap) to avoid rate limit burst across channels.
        Flux<Map.Entry<String, PrivateChannelWrapper>> channelFlux = Flux.fromIterable(channelIds)
                .concatMap(channelId -> fetchChannelInfoWithTokenFallback(token1, token2, channelId)
                        .map(w -> Map.entry(channelId, w))
                        .onErrorResume(err -> {
                            log.warn("Skipping channel {}: {}", channelId, err.getMessage());
                            return Mono.empty();
                        }));

        return channelFlux
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(wrappers -> {
                    if (wrappers.isEmpty()) {
                        return Mono.error(new IllegalStateException("No private channels could be fetched. Check channel IDs and token access."));
                    }
                    return fetchMembersForAll(wrappers).thenReturn(wrappers);
                })
                .flatMap(wrappers -> {
                    Flux<Map.Entry<String, PrivateChannelWrapper>> flux = Flux.fromIterable(wrappers.entrySet());
                    if (slackChannelDelayMs > 0) {
                        flux = flux.delayElements(java.time.Duration.ofMillis(slackChannelDelayMs));
                    }
                    return flux
                            .concatMap(e -> {
                                PrivateChannelWrapper w = e.getValue();
                                String token = w.token != null ? w.token : (userToken != null ? userToken : adminToken);
                                return slackApiClient.withRateLimitRetry(
                                        slackFetchService.fetchPrivateChannelMessages(token, w.id, startTs, endTs))
                                        .retryWhen(reactor.util.retry.Retry.backoff(Integer.MAX_VALUE, Duration.ofSeconds(30))
                                                .filter(ExportOrchestrator::isRetryableError)
                                                .doBeforeRetry(s -> log.warn("Private channel {} transient error (attempt {}); retrying in 30s: {}", w.id, s.totalRetries() + 1, s.failure().getMessage())))
                                        .map(msgs -> {
                                            List<JsonNode> normalized = new ArrayList<>();
                                            for (JsonNode m : msgs) {
                                                normalized.add(exportMessageNormalizer.normalizeMessage(m));
                                            }
                                            return PrivateChannelExportData.builder()
                                                    .channelId(w.id)
                                                    .name(w.name)
                                                    .created(w.created)
                                                    .creator(w.creator)
                                                    .isArchived(w.isArchived)
                                                    .isGeneral(w.isGeneral)
                                                    .topic(w.topic)
                                                    .purpose(w.purpose)
                                                    .members(w.members)
                                                    .messages(normalized)
                                                    .tokenSource(token)
                                                    .build();
                                        })
                                        .onErrorResume(err -> {
                                            log.warn("Skipping channel {}: {}", w.id, err.getMessage());
                                            return Mono.just(PrivateChannelExportData.builder()
                                                    .channelId(w.id)
                                                    .name(w.name)
                                                    .created(w.created)
                                                    .creator(w.creator)
                                                    .isArchived(w.isArchived)
                                                    .isGeneral(w.isGeneral)
                                                    .topic(w.topic)
                                                    .purpose(w.purpose)
                                                    .members(w.members)
                                                    .messages(List.of())
                                                    .tokenSource(token)
                                                    .build());
                                        });
                            })
                            .collectList()
                            .flatMap(channelsWithMessages -> {
                                List<PrivateChannelExportData> withMessages = channelsWithMessages.stream()
                                        .filter(c -> c.getMessages() != null && !c.getMessages().isEmpty())
                                        .toList();
                                if (withMessages.isEmpty()) {
                                    return Mono.error(new IllegalStateException("No messages found in selected range."));
                                }
                                Set<String> allUserIds = new LinkedHashSet<>();
                                for (PrivateChannelWrapper w : wrappers.values()) {
                                    allUserIds.addAll(w.members);
                                }
                                String token = slackTokenConfig.getToken();
                                if (token == null || token.isBlank()) token = slackTokenConfig.getAdminToken();
                                String finalToken = token;
                                return Flux.fromIterable(allUserIds)
                                        .flatMap(uid -> slackApiClient.withRateLimitRetry(slackApiClient.usersInfo(finalToken, uid))
                                                .map(UsersInfoResponse::getUser)
                                                .filter(Objects::nonNull)
                                                .map(usr -> {
                                                    ObjectNode u = objectMapper.createObjectNode();
                                                    u.put("id", usr.has("id") ? usr.get("id").asText() : uid);
                                                    u.put("name", usr.has("name") ? usr.get("name").asText() : uid);
                                                    u.set("profile", usr.has("profile") ? usr.get("profile") : objectMapper.createObjectNode());
                                                    u.put("real_name", usr.has("real_name") ? usr.get("real_name").asText() : "");
                                                    return u;
                                                })
                                                .onErrorResume(err -> {
                                                    log.warn("users.info failed for {}: {}", uid, err.getMessage());
                                                    return Mono.empty();
                                                }))
                                        .collectList()
                                        .map(usersList -> {
                                            @SuppressWarnings("unchecked")
                                            List<ObjectNode> userNodes = (List<ObjectNode>) (List<?>) usersList;
                                            try {
                                                return buildZip(withMessages, wrappers, userNodes, fromDate, toDate, "CUSTOM");
                                            } catch (IOException e) {
                                                throw new RuntimeException("ZIP build failed", e);
                                            }
                                        });
                            });
                });
    }

    /** Fetch channel info: try primary token first, then fallback token if not_in_channel (when fallback is present). */
    private Mono<PrivateChannelWrapper> fetchChannelInfoWithTokenFallback(String primaryToken, String fallbackToken, String channelId) {
        if (primaryToken == null || primaryToken.isBlank()) {
            return Mono.error(new IllegalArgumentException("Primary token is required."));
        }
        boolean primaryIsAdmin = primaryToken.equals(slackTokenConfig.getAdminToken());
        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(primaryToken, channelId))
                .map(resp -> toPrivateChannelWrapper(resp.getChannel(), channelId, primaryIsAdmin, primaryToken))
                .onErrorResume(SlackApiException.class, e -> {
                    String err = e.getSlackError() != null ? e.getSlackError() : "";
                    if ((err.contains("not_in_channel") || err.contains("channel_not_found")) && fallbackToken != null && !fallbackToken.isBlank()) {
                        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(fallbackToken, channelId))
                                .map(resp -> toPrivateChannelWrapper(resp.getChannel(), channelId, false, fallbackToken));
                    }
                    return Mono.error(e);
                });
    }

    private PrivateChannelWrapper toPrivateChannelWrapper(JsonNode ch, String channelId, boolean fromAdmin, String token) {
        if (ch == null) {
            return new PrivateChannelWrapper(channelId, channelId, 0L, "", false, false, null, null, fromAdmin, token);
        }
        String id = ch.has("id") ? ch.get("id").asText() : channelId;
        String name = ch.has("name") ? ch.get("name").asText() : id;
        long created = ch.has("created") ? ch.get("created").asLong() : 0L;
        String creator = ch.has("creator") ? ch.get("creator").asText() : "";
        boolean isArchived = ch.has("is_archived") && ch.get("is_archived").asBoolean();
        boolean isGeneral = ch.has("is_general") && ch.get("is_general").asBoolean();
        JsonNode topic = ch.has("topic") ? ch.get("topic") : null;
        JsonNode purpose = ch.has("purpose") ? ch.get("purpose") : null;
        return new PrivateChannelWrapper(id, name, created, creator, isArchived, isGeneral, topic, purpose, fromAdmin, token);
    }

    /** Fetch private channels from each token and merge; first token to list a channel wins (deduplicate by channel ID). */
    private Mono<Map<String, PrivateChannelWrapper>> fetchAndMergePrivateChannelsFromAllTokens(List<String> tokens) {
        Map<String, PrivateChannelWrapper> merged = new LinkedHashMap<>();
        Mono<Map<String, PrivateChannelWrapper>> chain = Mono.just(merged);
        for (String token : tokens) {
            boolean fromAdmin = token.equals(slackTokenConfig.getAdminToken());
            String t = token;
            chain = chain.flatMap(acc -> fetchAllPrivateChannelsAsMap(t, fromAdmin)
                    .map(fetched -> {
                        for (Map.Entry<String, PrivateChannelWrapper> e : fetched.entrySet()) {
                            acc.putIfAbsent(e.getKey(), e.getValue());
                        }
                        return acc;
                    }));
        }
        return chain;
    }

    private Mono<Map<String, PrivateChannelWrapper>> fetchAllPrivateChannelsAsMap(String token, boolean fromAdmin) {
        if (token == null || token.isBlank()) {
            return Mono.just(Collections.emptyMap());
        }
        String t = token;
        Mono<ConversationsListResponse> first = slackApiClient.withRateLimitRetry(
                slackApiClient.conversationsList(token, TYPES_PRIVATE_CHANNEL, LIST_PAGE_SIZE, null, false));
        return first
                .expand(resp -> {
                    String cursor = resp.getNextCursor();
                    if (cursor == null || cursor.isBlank()) return Mono.empty();
                    return slackApiClient.withRateLimitRetry(
                            slackApiClient.conversationsList(t, TYPES_PRIVATE_CHANNEL, LIST_PAGE_SIZE, cursor, false));
                })
                .reduce(new LinkedHashMap<String, PrivateChannelWrapper>(), (map, resp) -> {
                    if (resp.getChannels() != null) {
                        for (JsonNode ch : resp.getChannels()) {
                            if (!ch.has("id")) continue;
                            String id = ch.get("id").asText();
                            String name = ch.has("name") ? ch.get("name").asText() : id;
                            long created = ch.has("created") ? ch.get("created").asLong() : 0L;
                            String creator = ch.has("creator") ? ch.get("creator").asText() : "";
                            boolean isArchived = ch.has("is_archived") && ch.get("is_archived").asBoolean();
                            boolean isGeneral = ch.has("is_general") && ch.get("is_general").asBoolean();
                            JsonNode topic = ch.has("topic") ? ch.get("topic") : null;
                            JsonNode purpose = ch.has("purpose") ? ch.get("purpose") : null;
                            map.put(id, new PrivateChannelWrapper(id, name, created, creator, isArchived, isGeneral, topic, purpose, fromAdmin, t));
                        }
                    }
                    return map;
                });
    }

    private Mono<Void> fetchMembersForAll(Map<String, PrivateChannelWrapper> channels) {
        if (channels.isEmpty()) return Mono.empty();
        return Flux.fromIterable(channels.entrySet())
                .flatMap(e -> {
                    String channelId = e.getKey();
                    PrivateChannelWrapper w = e.getValue();
                    String token = w.token != null ? w.token : (slackTokenConfig.getToken() != null ? slackTokenConfig.getToken() : slackTokenConfig.getAdminToken());
                    return slackApiClient.withRateLimitRetry(slackApiClient.conversationsMembers(token, channelId))
                            .doOnNext(members -> w.members = members != null ? members : List.of())
                            .onErrorResume(err -> {
                                log.warn("conversations.members failed for {}: {}", channelId, err.getMessage());
                                return Mono.empty();
                            })
                            .then();
                }, 2)
                .then();
    }

    private byte[] buildZip(List<PrivateChannelExportData> channelsWithMessages,
                            Map<String, PrivateChannelWrapper> allChannels,
                            List<ObjectNode> usersList,
                            String fromDate, String toDate, String exportMode) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        var writer = objectMapper.writerWithDefaultPrettyPrinter();

        LocalDate from;
        LocalDate to;
        if ("CUSTOM".equals(exportMode) && fromDate != null && toDate != null) {
            from = LocalDate.parse(fromDate.trim());
            to = LocalDate.parse(toDate.trim());
        } else {
            from = LocalDate.EPOCH;
            to = LocalDate.now();
        }
        int numberOfDays = (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {
            // groups.json — ALL private channels from finalPrivateChannels
            ArrayNode groupsRoot = objectMapper.createArrayNode();
            for (PrivateChannelWrapper w : allChannels.values()) {
                ObjectNode g = objectMapper.createObjectNode();
                g.put("id", w.id);
                g.put("name", w.name);
                g.put("created", w.created);
                g.put("creator", w.creator != null ? w.creator : "");
                ArrayNode membersArr = objectMapper.createArrayNode();
                for (String uid : w.members) membersArr.add(uid);
                g.set("members", membersArr);
                if (w.topic != null) g.set("topic", buildTopicPurpose(w.topic));
                else g.set("topic", emptyTopicPurpose());
                if (w.purpose != null) g.set("purpose", buildTopicPurpose(w.purpose));
                else g.set("purpose", emptyTopicPurpose());
                g.set("pins", objectMapper.createArrayNode());
                g.put("is_archived", w.isArchived);
                g.put("is_general", w.isGeneral);
                groupsRoot.add(g);
            }
            putZipEntry(zos, "groups.json", writer.writeValueAsBytes(groupsRoot));

            // channels.json — empty (no public channels)
            putZipEntry(zos, "channels.json", writer.writeValueAsBytes(objectMapper.createArrayNode()));

            // users.json
            putZipEntry(zos, "users.json", writer.writeValueAsBytes(usersList != null ? usersList : List.of()));

            // Date files per channel folder (only channels with messages)
            Set<String> usedFolderNames = new HashSet<>();
            for (PrivateChannelExportData ch : channelsWithMessages) {
                sortMessagesByTs(ch.getMessages());
                attachRepliesToParents(ch.getMessages());

                Map<LocalDate, List<JsonNode>> messagesByDate = new LinkedHashMap<>();
                for (int i = 0; i < numberOfDays; i++) {
                    LocalDate date = from.plusDays(i);
                    List<JsonNode> forDate = new ArrayList<>();
                    for (JsonNode msg : ch.getMessages()) {
                        if (!msg.has("ts")) continue;
                        if (tsToLocalDate(msg.get("ts").asText()).equals(date)) forDate.add(msg);
                    }
                    if (!forDate.isEmpty()) messagesByDate.put(date, forDate);
                }

                String folderName = sanitizeFolderName(ch.getName());
                if (usedFolderNames.contains(folderName)) {
                    folderName = folderName + "-" + ch.getChannelId();
                }
                usedFolderNames.add(folderName);
                String folderPrefix = folderName + "/";

                for (Map.Entry<LocalDate, List<JsonNode>> e : messagesByDate.entrySet()) {
                    String dateKey = e.getKey().format(DATE_FORMAT);
                    ArrayNode rootArray = objectMapper.createArrayNode();
                    for (JsonNode m : e.getValue()) rootArray.add(m);
                    putZipEntry(zos, folderPrefix + dateKey + ".json", writer.writeValueAsBytes(rootArray));
                }
            }
        }

        byte[] zipBytes = baos.toByteArray();
        if (zipBytes.length == 0) throw new IllegalStateException("ZIP is empty.");
        log.info("Private channel export ZIP built: {} channels with messages", channelsWithMessages.size());
        return zipBytes;
    }

    private ObjectNode buildTopicPurpose(JsonNode node) {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("value", node.has("value") ? node.get("value").asText() : "");
        o.put("creator", node.has("creator") ? node.get("creator").asText() : "");
        o.put("last_set", node.has("last_set") ? node.get("last_set").asLong() : 0L);
        return o;
    }

    private ObjectNode emptyTopicPurpose() {
        ObjectNode o = objectMapper.createObjectNode();
        o.put("value", "");
        o.put("creator", "");
        o.put("last_set", 0);
        return o;
    }

    private void putZipEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(content);
        zos.closeEntry();
    }

    private static String sanitizeFolderName(String name) {
        if (name == null || name.isBlank()) return "private-channel";
        return UNSAFE_FOLDER_CHARS.matcher(name.trim()).replaceAll("-").replaceAll("\\s+", "--");
    }

    private static LocalDate tsToLocalDate(String ts) {
        try {
            double sec = Double.parseDouble(ts);
            return Instant.ofEpochSecond((long) sec).atOffset(ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            return LocalDate.EPOCH;
        }
    }

    private void sortMessagesByTs(List<JsonNode> messages) {
        messages.sort(Comparator.comparing(m -> m.has("ts") ? m.get("ts").asText() : "0",
                Comparator.comparingDouble(this::parseTs)));
    }

    private double parseTs(String ts) {
        try {
            return Double.parseDouble(ts);
        } catch (Exception e) {
            return 0;
        }
    }

    private void attachRepliesToParents(List<JsonNode> messages) {
        for (JsonNode msg : messages) {
            if (!msg.has("ts") || !msg.has("thread_ts")) continue;
            String ts = msg.get("ts").asText();
            String threadTs = msg.get("thread_ts").asText();
            if (!ts.equals(threadTs)) continue;
            if (!(msg instanceof ObjectNode parent)) continue;
            ArrayNode replies = objectMapper.createArrayNode();
            for (JsonNode other : messages) {
                if (!other.has("ts") || !other.has("thread_ts")) continue;
                if (!other.get("thread_ts").asText().equals(ts) || other.get("ts").asText().equals(ts)) continue;
                ObjectNode ref = objectMapper.createObjectNode();
                ref.put("ts", other.get("ts").asText());
                ref.put("user", other.has("user") ? other.get("user").asText() : "");
                replies.add(ref);
            }
            if (!replies.isEmpty()) parent.set("replies", replies);
        }
    }
}
