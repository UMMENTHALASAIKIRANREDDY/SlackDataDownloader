package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.slack.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fetches real Slack DM/MPIM data using only official APIs.
 * No mock data; no transformation of message content or blocks.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SlackFetchService {

    private final SlackApiClient slackApiClient;
    private final ObjectMapper objectMapper;

    @Value("${slack.rate-limit.enrichment-delay-ms:800}")
    private long enrichmentDelayMs;

    /** Slack API max per request (999 for history/replies); fetch until has_more is false and next_cursor empty. */
    private static final int HISTORY_PAGE_SIZE = 999;
    private static final int REPLIES_PAGE_SIZE = 999;

    /** Only this error triggers retry with user token (per spec). */
    private static final String RETRY_HISTORY_WITH_USER = "not_in_channel";

    /**
     * Fetch export data for one channel. Tries conversations.history with ADMIN token first;
     * if Slack returns not_in_channel or channel_not_found, retries with USER token.
     * Skips channel ONLY if BOTH tokens fail. No conversations.members, no membership checks.
     * DM type from conversations.info (is_im / is_mpim) only.
     */
    public Mono<ChannelExportData> fetchChannelWithTokenFallback(
            String adminToken,
            String userToken,
            String channelId,
            String startTs,
            String endTs,
            Integer messageLimitPerChannel) {
        return chooseTokenByHistory(adminToken, userToken, channelId, startTs, endTs)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new SlackApiException("Both tokens failed for channel " + channelId + " (conversations.history)", -1, null, null))))
                .flatMap(token -> {
                    String otherToken = (adminToken != null && userToken != null && !adminToken.equals(userToken))
                            ? (token.equals(adminToken) ? userToken : adminToken)
                            : null;
                    return getChannelInfoWithFallback(token, otherToken, channelId)
                            .flatMap(infoResult -> {
                                JsonNode ch = infoResult.channel();
                                String workingToken = infoResult.token();
                                boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                                boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                                if (!isDm && !isMpim) {
                                    return Mono.error(new SlackApiException(
                                            "Channel " + channelId + " is not a DM or Group DM (MPIM). Only one-on-one DMs and Group DMs are allowed.", -1, null, null));
                                }
                                return fetchAllMessages(workingToken, channelId, startTs, endTs, messageLimitPerChannel)
                                        .collectList()
                                        .flatMap(messages -> {
                                            Set<String> uniqueDates = uniqueDatesFromMessages(messages);
                                            List<String> sorted = uniqueDates.stream().sorted().toList();
                                            String dateRange = sorted.isEmpty() ? "none" : sorted.get(0) + (sorted.size() > 1 ? " to " + sorted.get(sorted.size() - 1) : "");
                                            log.info("Channel {}: total messages={} (isDm={}). Dates generated: {}", channelId, messages.size(), isDm, dateRange);
                                            String groupName = isMpim && ch.has("name") ? ch.get("name").asText() : null;
                                            return fetchMembersForChannel(workingToken, channelId)
                                                    .defaultIfEmpty(Collections.<String>emptyList())
                                                    .map(apiMemberList -> {
                                                        List<String> merged = mergeMembers(apiMemberList, messages, ch, isDm);
                                                        return ChannelExportData.builder()
                                                                .channelId(channelId)
                                                                .isDm(isDm)
                                                                .groupName(groupName)
                                                                .channelInfo(ch)
                                                                .messages(messages)
                                                                .members(merged)
                                                                .build();
                                                    });
                                        });
                            });
                });
    }

    /**
     * Same as fetchChannelWithTokenFallback but tries primaryToken first, then fallbackToken.
     * Use for "Export All DMs" when the DM was listed under user token (try user first, then admin).
     */
    public Mono<ChannelExportData> fetchChannelWithPreferredToken(
            String primaryToken,
            String fallbackToken,
            String channelId,
            String startTs,
            String endTs,
            Integer messageLimitPerChannel) {
        return chooseTokenByHistory(primaryToken, fallbackToken, channelId, startTs, endTs)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new SlackApiException("Both tokens failed for channel " + channelId + " (conversations.history)", -1, null, null))))
                .flatMap(token -> {
                    String otherToken = (primaryToken != null && fallbackToken != null && !primaryToken.equals(fallbackToken))
                            ? (token.equals(primaryToken) ? fallbackToken : primaryToken)
                            : null;
                    return getChannelInfoWithFallback(token, otherToken, channelId)
                            .flatMap(infoResult -> {
                                JsonNode ch = infoResult.channel();
                                String workingToken = infoResult.token();
                                boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                                boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                                if (!isDm && !isMpim) {
                                    return Mono.error(new SlackApiException(
                                            "Channel " + channelId + " is not a DM or Group DM (MPIM). Only one-on-one DMs and Group DMs are allowed.", -1, null, null));
                                }
                                return fetchAllMessages(workingToken, channelId, startTs, endTs, messageLimitPerChannel)
                                        .collectList()
                                        .flatMap(messages -> {
                                            Set<String> uniqueDates = uniqueDatesFromMessages(messages);
                                            List<String> sorted = uniqueDates.stream().sorted().toList();
                                            String dateRange = sorted.isEmpty() ? "none" : sorted.get(0) + (sorted.size() > 1 ? " to " + sorted.get(sorted.size() - 1) : "");
                                            log.info("Channel {}: total messages={} (isDm={}). Dates generated: {}", channelId, messages.size(), isDm, dateRange);
                                            String groupName = isMpim && ch.has("name") ? ch.get("name").asText() : null;
                                            return fetchMembersForChannel(workingToken, channelId)
                                                    .defaultIfEmpty(Collections.<String>emptyList())
                                                    .map(apiMemberList -> {
                                                        List<String> merged = mergeMembers(apiMemberList, messages, ch, isDm);
                                                        return ChannelExportData.builder()
                                                                .channelId(channelId)
                                                                .isDm(isDm)
                                                                .groupName(groupName)
                                                                .channelInfo(ch)
                                                                .messages(messages)
                                                                .members(merged)
                                                                .build();
                                                    });
                                        });
                            });
                });
    }

    /**
     * Same as fetchChannelWithPreferredToken but uses pre-fetched members (e.g. from STEP 2).
     * Merges preFetchedMembers with user IDs from messages for correct dms.json/mpims.json.
     */
    public Mono<ChannelExportData> fetchChannelWithPreferredTokenAndPreFetchedMembers(
            String primaryToken,
            String fallbackToken,
            String channelId,
            String startTs,
            String endTs,
            List<String> preFetchedMembers,
            Integer messageLimitPerChannel) {
        return chooseTokenByHistory(primaryToken, fallbackToken, channelId, startTs, endTs)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new SlackApiException("Both tokens failed for channel " + channelId + " (conversations.history)", -1, null, null))))
                .flatMap(token -> {
                    String otherToken = (primaryToken != null && fallbackToken != null && !primaryToken.equals(fallbackToken))
                            ? (token.equals(primaryToken) ? fallbackToken : primaryToken)
                            : null;
                    return getChannelInfoWithFallback(token, otherToken, channelId)
                            .flatMap(infoResult -> {
                                JsonNode ch = infoResult.channel();
                                String workingToken = infoResult.token();
                                boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                                boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                                if (!isDm && !isMpim) {
                                    return Mono.error(new SlackApiException(
                                            "Channel " + channelId + " is not a DM or Group DM (MPIM).", -1, null, null));
                                }
                                String groupName = isMpim && ch.has("name") ? ch.get("name").asText() : null;
                                return fetchAllMessages(workingToken, channelId, startTs, endTs, messageLimitPerChannel)
                                        .collectList()
                                        .map(messages -> {
                                            log.info("Channel {}: total messages={} (isDm={})", channelId, messages.size(), isDm);
                                            List<String> merged = mergeMembers(
                                                    preFetchedMembers != null ? preFetchedMembers : List.of(),
                                                    messages, ch, isDm);
                                            return ChannelExportData.builder()
                                                    .channelId(channelId)
                                                    .isDm(isDm)
                                                    .groupName(groupName)
                                                    .channelInfo(ch)
                                                    .messages(messages)
                                                    .members(merged)
                                                    .build();
                                        });
                            });
                });
    }

    /**
     * Fetch full member list via conversations.members (paginated). On failure or missing scope returns Mono.empty()
     * so callers can use default ChannelExportData without members (buildZip will use channelInfo-based fallback).
     */
    private Mono<List<String>> fetchMembersForChannel(String token, String channelId) {
        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsMembers(token, channelId))
                .filter(list -> list != null && !list.isEmpty())
                .onErrorResume(e -> {
                    log.debug("Channel {}: conversations.members failed ({}), metadata will use channelInfo only", channelId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** Result of getChannelInfoWithFallback: channel node and token to use for history/replies. */
    private record ChannelInfoResult(JsonNode channel, String token) {}

    /**
     * Get channel info (conversations.info). If primary token returns not_in_channel/channel_not_found,
     * retry with other token. Returns (channel JsonNode, token to use for history/replies).
     */
    private Mono<ChannelInfoResult> getChannelInfoWithFallback(String primaryToken, String otherToken, String channelId) {
        Mono<ChannelInfoResult> withPrimary = slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(primaryToken, channelId))
                .filter(resp -> resp.getChannel() != null)
                .map(resp -> new ChannelInfoResult(resp.getChannel(), primaryToken))
                .switchIfEmpty(Mono.error(new SlackApiException("conversations.info returned no channel", -1, null, null)));
        return withPrimary.onErrorResume(SlackApiException.class, e -> {
            String err = e.getSlackError();
            if (RETRY_HISTORY_WITH_USER.equals(err) && otherToken != null && !otherToken.isBlank()) {
                log.info("Channel {}: conversations.info returned {}, retrying with other token", channelId, err);
                return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(otherToken, channelId))
                        .filter(resp -> resp.getChannel() != null)
                        .map(resp -> new ChannelInfoResult(resp.getChannel(), otherToken))
                        .switchIfEmpty(Mono.error(new SlackApiException("conversations.info returned no channel", -1, null, null)));
            }
            return Mono.error(e);
        });
    }

    /**
     * Token selection: call conversations.history with ADMIN token first (if present).
     * If admin fails (e.g. not_in_channel, channel_not_found) → retry with USER token (if present).
     * Supports: admin-only (userToken null), user-only (adminToken null), or both with fallback.
     */
    private Mono<String> chooseTokenByHistory(String adminToken, String userToken, String channelId, String startTs, String endTs) {
        boolean hasAdmin = adminToken != null && !adminToken.isBlank();
        boolean hasUser = userToken != null && !userToken.isBlank();
        boolean sameToken = hasAdmin && hasUser && adminToken.trim().equals(userToken.trim());

        if (sameToken) {
            return Mono.just(userToken);
        }
        if (!hasAdmin) {
            return hasUser ? Mono.just(userToken) : Mono.empty();
        }
        if (!hasUser) {
            return Mono.just(adminToken);
        }

        int probeLimit = 10;
        Mono<String> withAdmin = slackApiClient.withRateLimitRetry(
                        slackApiClient.conversationsHistory(adminToken, channelId, startTs, endTs, probeLimit, null))
                .map(r -> adminToken);
        Mono<String> withUser = slackApiClient.withRateLimitRetry(
                        slackApiClient.conversationsHistory(userToken, channelId, startTs, endTs, probeLimit, null))
                .map(r -> userToken);
        return withAdmin
                .onErrorResume(SlackApiException.class, e -> {
                    log.info("Channel {}: admin token failed ({}), retrying with user token", channelId, e.getSlackError());
                    return withUser;
                })
                .onErrorResume(Throwable.class, e -> {
                    log.info("Channel {}: admin token failed (non-Slack error), retrying with user token", channelId);
                    return withUser;
                })
                .onErrorResume(Throwable.class, e -> {
                    String err = e instanceof SlackApiException ex ? ex.getSlackError() : null;
                    String msg = "Both tokens failed for channel " + channelId + " (conversations.history). Last error: " + (err != null ? err : e.getMessage());
                    log.warn("Channel {}: both tokens failed. User token error: {}", channelId, err != null ? err : e.getMessage());
                    return Mono.error(new SlackApiException(msg, -1, err, null, "conversations.history"));
                });
    }

    /**
     * Fetch channel data with a single token. DM/MPIM from conversations.info only.
     */
    public Mono<ChannelExportData> fetchChannelWithToken(
            String token,
            String channelId,
            String startTs,
            String endTs,
            Integer messageLimitPerChannel) {
        log.info("Fetching channel: {} with resolved token", channelId);
        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(token, channelId))
                .flatMap(infoResp -> {
                    if (infoResp.getChannel() == null) {
                        return Mono.error(new SlackApiException("conversations.info returned no channel", -1, null, null));
                    }
                    JsonNode ch = infoResp.getChannel();
                    boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                    boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                    log.info("conversations.info ok for {}: isDm={}, isMpim={}", channelId, isDm, isMpim);
                    if (!isDm && !isMpim) {
                        return Mono.error(new SlackApiException(
                                "Channel " + channelId + " is not a DM or Group DM (MPIM). Only one-on-one DMs and Group DMs are allowed.", -1, null, null));
                    }
                    return fetchAllMessages(token, channelId, startTs, endTs, messageLimitPerChannel)
                            .collectList()
                            .flatMap(messages -> {
                                log.info("Fetched {} messages for channel {} (isDm={})", messages.size(), channelId, isDm);
                                String groupName = isMpim && ch.has("name") ? ch.get("name").asText() : null;
                                return fetchMembersForChannel(token, channelId)
                                        .defaultIfEmpty(Collections.<String>emptyList())
                                        .map(apiMemberList -> {
                                            List<String> merged = mergeMembers(apiMemberList, messages, ch, isDm);
                                            return ChannelExportData.builder()
                                                    .channelId(channelId)
                                                    .isDm(isDm)
                                                    .groupName(groupName)
                                                    .channelInfo(ch)
                                                    .messages(messages)
                                                    .members(merged)
                                                    .build();
                                        });
                            });
                })
                .doOnError(e -> {
                    String slackError = e instanceof SlackApiException ex ? ex.getSlackError() : null;
                    String body = e instanceof SlackApiException ex ? ex.getResponseBody() : null;
                    log.error("[EXPORT] fetchChannel failed for channel {}. error={} slackError={} responseBody={}",
                            channelId, e.getMessage(), slackError, body != null && body.length() > 300 ? body.substring(0, 300) : body);
                });
    }

    /**
     * Same as fetchChannelWithTokenFallback but uses pre-fetched members (e.g. from STEP 2).
     * Merges preFetchedMembers with user IDs from messages so dms.json/mpims.json include everyone.
     */
    public Mono<ChannelExportData> fetchChannelWithTokenFallbackAndPreFetchedMembers(
            String adminToken,
            String userToken,
            String channelId,
            String startTs,
            String endTs,
            List<String> preFetchedMembers,
            Integer messageLimitPerChannel) {
        return chooseTokenByHistory(adminToken, userToken, channelId, startTs, endTs)
                .switchIfEmpty(Mono.defer(() -> Mono.error(new SlackApiException("Both tokens failed for channel " + channelId + " (conversations.history)", -1, null, null))))
                .flatMap(token -> {
                    String otherToken = (adminToken != null && userToken != null && !adminToken.equals(userToken))
                            ? (token.equals(adminToken) ? userToken : adminToken)
                            : null;
                    return getChannelInfoWithFallback(token, otherToken, channelId)
                            .flatMap(infoResult -> {
                                JsonNode ch = infoResult.channel();
                                String workingToken = infoResult.token();
                                boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                                boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                                if (!isDm && !isMpim) {
                                    return Mono.error(new SlackApiException(
                                            "Channel " + channelId + " is not a DM or Group DM (MPIM).", -1, null, null));
                                }
                                String groupName = isMpim && ch.has("name") ? ch.get("name").asText() : null;
                                return fetchAllMessages(workingToken, channelId, startTs, endTs, messageLimitPerChannel)
                                        .collectList()
                                        .map(messages -> {
                                            Set<String> uniqueDates = uniqueDatesFromMessages(messages);
                                            List<String> sorted = uniqueDates.stream().sorted().toList();
                                            String dateRange = sorted.isEmpty() ? "none" : sorted.get(0) + (sorted.size() > 1 ? " to " + sorted.get(sorted.size() - 1) : "");
                                            log.info("Channel {}: total messages={} (isDm={}). Dates generated: {}", channelId, messages.size(), isDm, dateRange);
                                            List<String> merged = mergeMembers(
                                                    preFetchedMembers != null ? preFetchedMembers : List.of(),
                                                    messages, ch, isDm);
                                            return ChannelExportData.builder()
                                                    .channelId(channelId)
                                                    .isDm(isDm)
                                                    .groupName(groupName)
                                                    .channelInfo(ch)
                                                    .messages(messages)
                                                    .members(merged)
                                                    .build();
                                        });
                            });
                });
    }

    /**
     * For "Entire History" flow: fetch channel info + full message history; use provided members (from conversations.members).
     * Use this after STEP 2 when members were already fetched.
     */
    public Mono<ChannelExportData> fetchChannelInfoAndMessagesWithMembers(
            String token,
            String channelId,
            String startTs,
            String endTs,
            List<String> membersFromConversationsMembers,
            boolean isDm,
            String groupName) {
        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(token, channelId))
                .flatMap(infoResp -> {
                    if (infoResp.getChannel() == null) {
                        return Mono.error(new SlackApiException("conversations.info returned no channel", -1, null, null));
                    }
                    JsonNode ch = infoResp.getChannel();
                    return fetchAllMessages(token, channelId, startTs, endTs, null)
                            .collectList()
                            .map(messages -> {
                                log.info("Fetched {} messages for channel {} (isDm={})", messages.size(), channelId, isDm);
                                List<String> merged = mergeMembers(
                                        membersFromConversationsMembers != null ? membersFromConversationsMembers : List.of(),
                                        messages, ch, isDm);
                                return ChannelExportData.builder()
                                        .channelId(channelId)
                                        .isDm(isDm)
                                        .groupName(groupName)
                                        .channelInfo(ch)
                                        .messages(messages)
                                        .members(merged)
                                        .build();
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Skipping channel {}: {}", channelId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** @deprecated Use fetchChannelWithTokenFallback when both tokens are available. */
    @Deprecated
    public Mono<ChannelExportData> fetchChannel(
            String token,
            String channelId,
            String startTs,
            String endTs,
            Integer messageLimitPerChannel) {
        return fetchChannelWithToken(token, channelId, startTs, endTs, messageLimitPerChannel);
    }

    /**
     * Fetch all messages for a private channel (history + replies + reactions + files).
     * For use by PrivateChannelExportServiceDualToken only. Does not affect DM export.
     */
    public Mono<List<JsonNode>> fetchPrivateChannelMessages(String token, String channelId, String startTs, String endTs) {
        return fetchAllMessages(token, channelId, startTs, endTs, null).collectList();
    }

    /**
     * Fetch all messages for a channel: history + thread replies, then enrich with reactions and files.
     * Messages are ordered by ts (ascending). Thread replies reference parent thread_ts; no new date files for replies.
     */
    private Flux<JsonNode> fetchAllMessages(
            String token,
            String channelId,
            String startTs,
            String endTs,
            Integer messageLimitPerChannel) {
        int limit = messageLimitPerChannel != null && messageLimitPerChannel > 0 ? messageLimitPerChannel : Integer.MAX_VALUE;

        Flux<JsonNode> historyFlux = fetchHistoryPaginated(token, channelId, startTs, endTs).take(limit);
        if (enrichmentDelayMs > 0) {
            historyFlux = historyFlux.delayElements(Duration.ofMillis(enrichmentDelayMs));
        }
        return historyFlux.concatMap(msg -> enrichMessageWithReactionsAndFiles(token, channelId, msg)
                        .flatMapMany(enriched -> {
                            String ts = enriched.has("ts") ? enriched.get("ts").asText() : null;
                            boolean isThreadParent = ts != null && enriched.has("thread_ts")
                                    && enriched.get("thread_ts").asText().equals(ts);
                            if (!isThreadParent) return Flux.just(enriched);
                            return fetchThreadReplies(token, channelId, ts, startTs, endTs)
                                    .flatMap(reply -> enrichMessageWithReactionsAndFiles(token, channelId, reply))
                                    .collectList()
                                    .flatMapMany(replies -> Flux.concat(Flux.just(enriched), Flux.fromIterable(replies)));
                        }));
    }

    /**
     * Use only current members from the API (conversations.members).
     * We do not add user IDs from messages (authors, editors, or @mentions). So:
     * - Users who left the DM/MPIM are not added (they are not in conversations.members).
     * - Users who are only mentioned in messages but are not part of the DM are not added.
     */
    private static List<String> mergeMembers(List<String> apiMembers, List<JsonNode> messages, JsonNode channelInfo, boolean isDm) {
        Set<String> merged = new LinkedHashSet<>();
        if (apiMembers != null) merged.addAll(apiMembers);
        return new ArrayList<>(merged);
    }

    /** Convert message ts to UTC date (YYYY-MM-DD) for debug logging. */
    private static Set<String> uniqueDatesFromMessages(List<JsonNode> messages) {
        if (messages == null || messages.isEmpty()) return Set.of();
        return messages.stream()
                .filter(m -> m.has("ts"))
                .map(m -> {
                    try {
                        double sec = Double.parseDouble(m.get("ts").asText());
                        return LocalDate.ofInstant(Instant.ofEpochSecond((long) sec), ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE);
                    } catch (Exception e) {
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /**
     * Fetch ALL pages of conversations.history; merge into one list.
     * Loop: call with cursor until has_more == false and next_cursor empty.
     * If has_more is true but cursor is blank (Slack quirk), fetch next page using latest=oldest_ts-epsilon.
     */
    private Flux<JsonNode> fetchHistoryPaginated(String token, String channelId, String oldest, String latest) {
        Mono<ConversationsHistoryResponse> firstPage = slackApiClient.withRateLimitRetry(
                slackApiClient.conversationsHistory(token, channelId, oldest, latest, HISTORY_PAGE_SIZE, null));
        return firstPage
                .expand(resp -> {
                    if (!resp.isHasMore()) return Mono.empty();
                    String nextCursor = resp.getNextCursor();
                    if (nextCursor != null && !nextCursor.isBlank()) {
                        return slackApiClient.withRateLimitRetry(
                                slackApiClient.conversationsHistory(token, channelId, oldest, latest, HISTORY_PAGE_SIZE, nextCursor));
                    }
                    // Fallback: has_more but no cursor — fetch next page by latest = oldest ts in this page
                    double nextLatest = minTsFromMessages(resp.getMessages()) - 0.000001;
                    if (nextLatest <= 0) return Mono.empty();
                    log.warn("conversations.history has_more=true but next_cursor blank for {}; fetching next page with latest={}", channelId, nextLatest);
                    return slackApiClient.withRateLimitRetry(
                            slackApiClient.conversationsHistory(token, channelId, oldest, String.valueOf(nextLatest), HISTORY_PAGE_SIZE, null));
                })
                .collectList()
                .flatMapMany(pages -> {
                    List<JsonNode> allMessages = new ArrayList<>();
                    Set<String> seenTs = new HashSet<>();
                    for (ConversationsHistoryResponse page : pages) {
                        if (page.getMessages() == null) continue;
                        for (JsonNode m : page.getMessages()) {
                            String ts = m.has("ts") ? m.get("ts").asText() : null;
                            if (ts != null && seenTs.add(ts)) allMessages.add(m);
                        }
                    }
                    log.info("Fetched {} pages for {}. Total messages: {}", pages.size(), channelId, allMessages.size());
                    return Flux.fromIterable(allMessages);
                });
    }

    /** Minimum ts (as double) from messages, or 0 if none. */
    private static double minTsFromMessages(List<JsonNode> messages) {
        if (messages == null || messages.isEmpty()) return 0;
        double min = Double.MAX_VALUE;
        for (JsonNode m : messages) {
            if (m.has("ts")) {
                try {
                    double t = Double.parseDouble(m.get("ts").asText());
                    if (t < min) min = t;
                } catch (NumberFormatException ignored) { }
            }
        }
        return min == Double.MAX_VALUE ? 0 : min;
    }

    /**
     * Fetch ALL pages of conversations.replies for a thread; merge into one list.
     * First message in first page is the parent (already in channel history) → skip it. Rest are replies.
     * If has_more but cursor blank, fetch next page using latest=oldest_ts-epsilon.
     */
    private Flux<JsonNode> fetchThreadReplies(String token, String channelId, String threadTs, String oldest, String latest) {
        Mono<ConversationsRepliesResponse> firstPage = slackApiClient.withRateLimitRetry(
                slackApiClient.conversationsReplies(token, channelId, threadTs, oldest, latest, REPLIES_PAGE_SIZE, null));
        return firstPage
                .expand(resp -> {
                    if (!resp.isHasMore()) return Mono.empty();
                    String nextCursor = resp.getNextCursor();
                    if (nextCursor != null && !nextCursor.isBlank()) {
                        return slackApiClient.withRateLimitRetry(
                                slackApiClient.conversationsReplies(token, channelId, threadTs, oldest, latest, REPLIES_PAGE_SIZE, nextCursor));
                    }
                    double nextLatest = minTsFromMessages(resp.getMessages()) - 0.000001;
                    if (nextLatest <= 0) return Mono.empty();
                    log.warn("conversations.replies has_more=true but next_cursor blank for thread {} in {}; fetching with latest={}", threadTs, channelId, nextLatest);
                    return slackApiClient.withRateLimitRetry(
                            slackApiClient.conversationsReplies(token, channelId, threadTs, oldest, String.valueOf(nextLatest), REPLIES_PAGE_SIZE, null));
                })
                .collectList()
                .flatMapMany(pages -> {
                    List<JsonNode> allReplies = new ArrayList<>();
                    for (int i = 0; i < pages.size(); i++) {
                        List<JsonNode> msgs = pages.get(i).getMessages() != null ? pages.get(i).getMessages() : List.of();
                        if (i == 0 && !msgs.isEmpty()) {
                            allReplies.addAll(msgs.subList(1, msgs.size()));
                        } else {
                            allReplies.addAll(msgs);
                        }
                    }
                    return Flux.fromIterable(allReplies);
                });
    }

    /**
     * Count messages in a DM/MPIM channel (history + thread replies). Does not enrich or build export.
     * Uses same pagination as export; token must have access to the channel.
     */
    public Mono<Long> countMessagesInChannel(String token, String channelId, String startTs, String endTs) {
        return slackApiClient.withRateLimitRetry(slackApiClient.conversationsInfo(token, channelId))
                .flatMap(infoResp -> {
                    if (infoResp.getChannel() == null) {
                        return Mono.error(new SlackApiException("conversations.info returned no channel", -1, null, null));
                    }
                    JsonNode ch = infoResp.getChannel();
                    boolean isDm = ch.has("is_im") && ch.get("is_im").asBoolean() && (!ch.has("is_mpim") || !ch.get("is_mpim").asBoolean());
                    boolean isMpim = ch.has("is_mpim") && ch.get("is_mpim").asBoolean();
                    if (!isDm && !isMpim) {
                        return Mono.error(new SlackApiException(
                                "Channel " + channelId + " is not a DM or Group DM (MPIM). Only one-on-one DMs and Group DMs are allowed.", -1, null, null));
                    }
                    return fetchHistoryPaginated(token, channelId, startTs, endTs)
                            .collectList()
                            .flatMap(messages -> {
                                long historyCount = messages.size();
                                List<Mono<Long>> replyCountMonos = new ArrayList<>();
                                for (JsonNode m : messages) {
                                    String ts = m.has("ts") ? m.get("ts").asText() : null;
                                    boolean isParent = ts != null && m.has("thread_ts") && ts.equals(m.get("thread_ts").asText());
                                    if (isParent) {
                                        replyCountMonos.add(fetchThreadReplies(token, channelId, ts, startTs, endTs).count());
                                    }
                                }
                                if (replyCountMonos.isEmpty()) {
                                    return Mono.just(historyCount);
                                }
                                return Flux.fromIterable(replyCountMonos).flatMap(m -> m).reduce(0L, Long::sum)
                                        .map(replyTotal -> historyCount + replyTotal);
                            });
                });
    }

    /**
     * Enrich message with full reactions (reactions.get) and full file info (files.info).
     * Retries up to 100 times on rate limit. If still rate limited after retries, include message
     * without reactions/files (last resort) so we never skip a channel.
     */
    private Mono<JsonNode> enrichMessageWithReactionsAndFiles(String token, String channelId, JsonNode message) {
        ObjectNode copy = message.deepCopy();
        String ts = message.has("ts") ? message.get("ts").asText() : null;
        if (ts == null) return Mono.just(copy);

        Mono<JsonNode> reactionsMono = slackApiClient.withRateLimitRetry(
                slackApiClient.reactionsGet(token, channelId, ts))
                .map(ReactionsGetResponse::getMessage)
                .map(msg -> (msg != null && msg.has("reactions")) ? msg.get("reactions") : objectMapper.createArrayNode())
                .onErrorResume(SlackApiException.class, e -> {
                    String err = e.getSlackError();
                    if (err != null && (err.equals("no_reaction") || err.equals("message_not_found") || err.equals("missing_scope"))) {
                        if ("missing_scope".equals(err)) log.debug("reactions.get missing_scope, skipping reactions for message {}", ts);
                        return Mono.just(objectMapper.createArrayNode());
                    }
                    return Mono.error(e);
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("reactions.get rate limit exhausted for channel {} message {}; including message without reactions (channel never skipped)", channelId, ts);
                    return Mono.just(objectMapper.createArrayNode());
                });

        Mono<JsonNode> filesEnrichedMono = Mono.just(copy).flatMap(msg -> {
            if (!msg.has("files") || !msg.get("files").isArray()) return Mono.just(copy);
            ArrayNode files = (ArrayNode) msg.get("files");
            List<Mono<JsonNode>> fileMonos = new ArrayList<>();
            for (JsonNode f : files) {
                String fileId = f.has("id") ? f.get("id").asText() : null;
                if (fileId == null) continue;
                fileMonos.add(slackApiClient.withRateLimitRetry(slackApiClient.filesInfo(token, fileId))
                        .map(FilesInfoResponse::getFile)
                        .onErrorResume(SlackApiException.class, e -> {
                            if ("missing_scope".equals(e.getSlackError())) {
                                log.debug("files.info missing_scope, keeping original file snippet for file {}", fileId);
                                return Mono.just(f);
                            }
                            return Mono.error(e);
                        })
                        .onErrorResume(SlackRateLimitException.class, e -> {
                            log.warn("files.info rate limit exhausted for file {}; keeping original snippet (channel never skipped)", fileId);
                            return Mono.just(f);
                        }));
            }
            if (fileMonos.isEmpty()) return Mono.just(copy);
            return Mono.zip(fileMonos, results -> {
                ArrayNode newFiles = objectMapper.createArrayNode();
                for (Object r : results) newFiles.add((JsonNode) r);
                copy.set("files", newFiles);
                return copy;
            });
        });

        return reactionsMono.zipWith(filesEnrichedMono)
                .map(tuple -> {
                    JsonNode reactions = tuple.getT1();
                    if (reactions.isArray() && reactions.size() > 0) copy.set("reactions", reactions);
                    return tuple.getT2();
                });
    }
}
