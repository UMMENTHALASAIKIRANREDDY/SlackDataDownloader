package com.cloudfuze.slackexport.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Low-level client for Slack API. Handles auth, rate limits (429), and pagination.
 * Uses only official Slack APIs: conversations.history, conversations.replies,
 * conversations.info, users.info, files.info, reactions.get.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlackApiClient {

    private final WebClient slackWebClient;
    private final ObjectMapper objectMapper;

    @Value("${slack.rate-limit.retry-after-header:Retry-After}")
    private String retryAfterHeader;

    @Value("${slack.rate-limit.max-retries:5}")
    private int maxRetries;

    @Value("${slack.rate-limit.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    private static final int RATE_LIMIT_HTTP_STATUS = 429;

    public Mono<ConversationsHistoryResponse> conversationsHistory(
            String token,
            String channel,
            String oldest,
            String latest,
            Integer limit,
            String cursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel", channel);
        if (oldest != null && !oldest.isBlank()) params.put("oldest", oldest);
        if (latest != null && !latest.isBlank()) params.put("latest", latest);
        if (limit != null) params.put("limit", Math.min(limit, 1000));
        if (cursor != null && !cursor.isBlank()) params.put("cursor", cursor);

        return getWithToken(token, "/conversations.history", params)
                .flatMap(body -> parseSlackOkResponse(body, "/conversations.history"))
                .map(this::toConversationsHistoryResponse);
    }

    public Mono<ConversationsRepliesResponse> conversationsReplies(
            String token,
            String channel,
            String threadTs,
            String oldest,
            String latest,
            Integer limit,
            String cursor) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel", channel);
        params.put("ts", threadTs);
        if (oldest != null && !oldest.isBlank()) params.put("oldest", oldest);
        if (latest != null && !latest.isBlank()) params.put("latest", latest);
        if (limit != null) params.put("limit", Math.min(limit, 1000));
        if (cursor != null && !cursor.isBlank()) params.put("cursor", cursor);

        return getWithToken(token, "/conversations.replies", params)
                .flatMap(body -> parseSlackOkResponse(body, "/conversations.replies"))
                .map(this::toConversationsRepliesResponse);
    }

    public Mono<ConversationsInfoResponse> conversationsInfo(String token, String channel) {
        Map<String, Object> params = Map.of("channel", channel);
        return getWithToken(token, "/conversations.info", params)
                .flatMap(body -> parseSlackOkResponse(body, "/conversations.info"))
                .map(this::toConversationsInfoResponse);
    }

    /** conversations.list: list channels (e.g. types=im,mpim,private_channel). Paginated via cursor. */
    public Mono<ConversationsListResponse> conversationsList(String token, String types, Integer limit, String cursor, Boolean excludeArchived) {
        Map<String, Object> params = new HashMap<>();
        if (types != null && !types.isBlank()) params.put("types", types);
        if (limit != null) params.put("limit", Math.min(limit, 200));
        if (cursor != null && !cursor.isBlank()) params.put("cursor", cursor);
        if (excludeArchived != null) params.put("exclude_archived", excludeArchived);
        return getWithToken(token, "/conversations.list", params)
                .flatMap(body -> parseSlackOkResponse(body, "/conversations.list"))
                .map(this::toConversationsListResponse);
    }

    /** conversations.members: paginated member IDs for a channel. Fetches all pages. */
    public Mono<List<String>> conversationsMembers(String token, String channel) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel", channel);
        params.put("limit", 200);
        Mono<ConversationsMembersResponse> first = getWithToken(token, "/conversations.members", params)
                .flatMap(body -> parseSlackOkResponse(body, "/conversations.members"))
                .map(this::toConversationsMembersResponse);
        return first
                .expand(resp -> {
                    if (!resp.isHasMore()) return Mono.empty();
                    String cursor = resp.getNextCursor();
                    if (cursor == null || cursor.isBlank()) return Mono.empty();
                    Map<String, Object> nextParams = new HashMap<>();
                    nextParams.put("channel", channel);
                    nextParams.put("limit", 200);
                    nextParams.put("cursor", cursor);
                    return getWithToken(token, "/conversations.members", nextParams)
                            .flatMap(body -> parseSlackOkResponse(body, "/conversations.members"))
                            .map(this::toConversationsMembersResponse);
                })
                .reduce(new ArrayList<String>(), (list, resp) -> {
                    if (resp.getMembers() != null) list.addAll(resp.getMembers());
                    return list;
                });
    }

    public Mono<UsersInfoResponse> usersInfo(String token, String user) {
        Map<String, Object> params = Map.of("user", user);
        return getWithToken(token, "/users.info", params)
                .flatMap(body -> parseSlackOkResponse(body, "/users.info"))
                .map(this::toUsersInfoResponse);
    }

    public Mono<FilesInfoResponse> filesInfo(String token, String fileId) {
        Map<String, Object> params = Map.of("file", fileId);
        return getWithToken(token, "/files.info", params)
                .flatMap(body -> parseSlackOkResponse(body, "/files.info"))
                .map(this::toFilesInfoResponse);
    }

    public Mono<ReactionsGetResponse> reactionsGet(String token, String channel, String timestamp) {
        Map<String, Object> params = new HashMap<>();
        params.put("channel", channel);
        params.put("timestamp", timestamp);
        return getWithToken(token, "/reactions.get", params)
                .flatMap(body -> parseSlackOkResponse(body, "/reactions.get"))
                .map(this::toReactionsGetResponse);
    }

    /** auth.test: returns the user_id of the token holder. Required for dms.json members. */
    public Mono<String> authTest(String token) {
        return getWithToken(token, "/auth.test", Map.of())
                .flatMap(body -> parseSlackOkResponse(body, "/auth.test"))
                .map(root -> root.has("user_id") ? root.get("user_id").asText() : null);
    }

    private static String tokenTypeForLog(String token) {
        if (token == null || token.isEmpty()) return "none";
        String t = token.trim();
        if (t.startsWith("Bearer ")) t = t.substring(7).trim();
        if (t.startsWith("xoxp-")) return "user";
        if (t.startsWith("xoxb-")) return "bot";
        if (t.startsWith("xoxe-") || t.startsWith("xoxa-")) return "enterprise";
        return "other";
    }

    /**
     * Normalize token: trim, strip optional "Bearer " prefix, remove control chars/whitespace from config/env
     * so the value sent matches what works in Postman.
     */
    private static String normalizeToken(String token) {
        if (token == null) return "";
        String t = token.trim();
        if (t.startsWith("Bearer ")) t = t.substring(7).trim();
        return t.replaceAll("[\\p{C}\\s]+", "").trim();
    }

    /** Token prefix for logging only (first 10 chars). Never log full token. */
    public static String tokenPrefixForLog(String token) {
        String t = normalizeToken(token);
        if (t.isEmpty()) return "(empty)";
        return t.length() <= 10 ? t : t.substring(0, 10) + "...";
    }

    private Mono<String> getWithToken(String token, String path, Map<String, Object> queryParams) {
        String normalizedToken = normalizeToken(token);
        if (normalizedToken.isEmpty()) {
            log.error("Slack API call aborted: token is null or empty for endpoint {}", path);
            return Mono.error(new SlackApiException("Slack token is null or empty. Set slack.tokens.user in config or send slackAccessToken in request.", -1, null, null, path));
        }
        String authValue = "Bearer " + normalizedToken;
        String pathSegment = path != null && path.startsWith("/") ? path.substring(1) : (path != null ? path : "");
        String channelId = queryParams != null && queryParams.containsKey("channel") ? String.valueOf(queryParams.get("channel")) : null;

        log.debug("Slack API request: endpoint={}, tokenType={}, channelId={}, tokenPrefix={}", pathSegment, tokenTypeForLog(token), channelId, tokenPrefixForLog(token));

        return slackWebClient.get()
                .uri(uriBuilder -> {
                    var b = uriBuilder.pathSegment(pathSegment);
                    if (queryParams != null) {
                        queryParams.forEach((k, v) -> {
                            if (v != null && !v.toString().isBlank()) b.queryParam(k, v);
                        });
                    }
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, authValue)
                .accept(MediaType.APPLICATION_JSON)
                .exchangeToMono(response -> handleResponse(response, path, channelId, tokenTypeForLog(token)));
    }

    private Mono<String> handleResponse(ClientResponse response, String path, String channelId, String tokenTypeForLog) {
        HttpStatusCode status = response.statusCode();
        return response.bodyToMono(String.class)
                .flatMap(body -> {
                    String errorField = parseErrorFieldFromBody(body);
                    log.debug("Slack API response: endpoint={}, httpStatus={}, channelId={}, error={}, bodyLength={}",
                            path, status.value(), channelId, errorField != null ? errorField : "none", body != null ? body.length() : 0);
                    if (status.value() == RATE_LIMIT_HTTP_STATUS) {
                        long retryAfterSeconds = response.headers().asHttpHeaders()
                                .getFirst(retryAfterHeader) != null
                                ? Long.parseLong(response.headers().asHttpHeaders().getFirst(retryAfterHeader))
                                : 60L;
                        log.warn("Slack rate limit (429) for {}; Retry-After: {}s. Body: {}", path, retryAfterSeconds, body);
                        return Mono.error(new SlackRateLimitException("Rate limited", (int) retryAfterSeconds, body));
                    }
                    if (!status.is2xxSuccessful()) {
                        log.error("Slack API error: endpoint={}, tokenType={}, channelId={}, httpStatus={}, responseBody={}", path, tokenTypeForLog, channelId, status.value(), body);
                        return Mono.error(new SlackApiException("Slack API error: " + path, status.value(), null, body, path));
                    }
                    return Mono.just(body);
                });
    }

    private String parseErrorFieldFromBody(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.has("error") && !root.get("error").isNull() ? root.get("error").asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Mono<JsonNode> parseSlackOkResponse(String body, String path) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.has("ok")) {
                return Mono.error(new SlackApiException("Invalid Slack response: no 'ok' field", -1, null, body, path));
            }
            if (!root.get("ok").asBoolean()) {
                String error = root.has("error") ? root.get("error").asText() : "unknown";
                log.debug("Slack API ok=false: endpoint={}, error={}, fullResponse={}", path, error, body);
                log.error("Slack API returned ok=false for {}. error={}. Full response: {}", path, error, body);
                return Mono.error(new SlackApiException("Slack API error: " + error, -1, error, body, path));
            }
            return Mono.just(root);
        } catch (Exception e) {
            return Mono.error(new SlackApiException("Failed to parse Slack response", e));
        }
    }

    private ConversationsHistoryResponse toConversationsHistoryResponse(JsonNode root) {
        ConversationsHistoryResponse r = new ConversationsHistoryResponse();
        r.setOk(true);
        if (root.has("response_metadata")) r.setResponseMetadata(root.get("response_metadata"));
        if (root.has("has_more")) r.setHasMore(root.get("has_more").asBoolean(false));
        if (root.has("messages") && root.get("messages").isArray()) {
            List<JsonNode> msgs = objectMapper.convertValue(root.get("messages"),
                    objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, JsonNode.class));
            r.setMessages(msgs);
            log.debug("conversations.history page: messages.length={}, has_more={}", msgs.size(), r.isHasMore());
        }
        return r;
    }

    private ConversationsRepliesResponse toConversationsRepliesResponse(JsonNode root) {
        ConversationsRepliesResponse r = new ConversationsRepliesResponse();
        r.setOk(true);
        if (root.has("response_metadata")) r.setResponseMetadata(root.get("response_metadata"));
        if (root.has("has_more")) r.setHasMore(root.get("has_more").asBoolean(false));
        if (root.has("messages") && root.get("messages").isArray()) {
            r.setMessages(objectMapper.convertValue(root.get("messages"),
                    objectMapper.getTypeFactory().constructCollectionType(java.util.List.class, JsonNode.class)));
        }
        return r;
    }

    private ConversationsInfoResponse toConversationsInfoResponse(JsonNode root) {
        ConversationsInfoResponse r = new ConversationsInfoResponse();
        r.setOk(true);
        if (root.has("channel")) r.setChannel(root.get("channel"));
        return r;
    }

    private ConversationsMembersResponse toConversationsMembersResponse(JsonNode root) {
        ConversationsMembersResponse r = new ConversationsMembersResponse();
        r.setOk(true);
        if (root.has("response_metadata")) r.setResponseMetadata(root.get("response_metadata"));
        if (root.has("has_more")) r.setHasMore(root.get("has_more").asBoolean(false));
        if (root.has("members") && root.get("members").isArray()) {
            List<String> list = new ArrayList<>();
            for (JsonNode n : root.get("members")) list.add(n.asText());
            r.setMembers(list);
        }
        return r;
    }

    private ConversationsListResponse toConversationsListResponse(JsonNode root) {
        ConversationsListResponse r = new ConversationsListResponse();
        r.setOk(true);
        if (root.has("response_metadata")) r.setResponseMetadata(root.get("response_metadata"));
        if (root.has("has_more")) r.setHasMore(root.get("has_more").asBoolean(false));
        if (root.has("channels") && root.get("channels").isArray()) {
            r.setChannels(objectMapper.convertValue(root.get("channels"),
                    objectMapper.getTypeFactory().constructCollectionType(List.class, JsonNode.class)));
        }
        return r;
    }

    private UsersInfoResponse toUsersInfoResponse(JsonNode root) {
        UsersInfoResponse r = new UsersInfoResponse();
        r.setOk(true);
        if (root.has("user")) r.setUser(root.get("user"));
        return r;
    }

    private FilesInfoResponse toFilesInfoResponse(JsonNode root) {
        FilesInfoResponse r = new FilesInfoResponse();
        r.setOk(true);
        if (root.has("file")) r.setFile(root.get("file"));
        return r;
    }

    private ReactionsGetResponse toReactionsGetResponse(JsonNode root) {
        ReactionsGetResponse r = new ReactionsGetResponse();
        r.setOk(true);
        if (root.has("message")) r.setMessage(root.get("message"));
        return r;
    }

    private static final long RATE_LIMIT_BACKOFF_MAX_SECONDS = 60L;

    /**
     * Execute a Mono with retry on HTTP 429. Uses Retry-After header when present,
     * otherwise exponential backoff (1s → 2s → 4s → 8s → …) capped at 60s. Max 5 retries.
     */
    public <T> Mono<T> withRateLimitRetry(Mono<T> mono) {
        return mono.retryWhen(reactor.util.retry.Retry.from(signals -> signals.flatMap(signal -> {
            if (!(signal.failure() instanceof SlackRateLimitException rateLimit)) {
                return Mono.error(signal.failure());
            }
            if (signal.totalRetries() >= maxRetries) {
                return Mono.error(signal.failure());
            }
            long sec = rateLimit.getRetryAfterSeconds() > 0
                    ? Math.min(RATE_LIMIT_BACKOFF_MAX_SECONDS, rateLimit.getRetryAfterSeconds())
                    : Math.min(RATE_LIMIT_BACKOFF_MAX_SECONDS,
                            Math.max(1, (long) Math.pow(2, signal.totalRetries()) * initialBackoffMs / 1000));
            log.warn("Rate limited. Retrying after {} seconds...", sec);
            return Mono.delay(Duration.ofSeconds(sec));
        })));
    }
}
