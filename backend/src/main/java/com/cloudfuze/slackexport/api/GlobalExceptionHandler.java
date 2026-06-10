package com.cloudfuze.slackexport.api;

import com.cloudfuze.slackexport.slack.SlackApiException;
import com.cloudfuze.slackexport.slack.SlackRateLimitException;
import com.cloudfuze.slackexport.validation.ValidationException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ensures every error returns a plain-text body so the frontend can display it.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final ObjectMapper objectMapper;

    private static final HttpHeaders TEXT_PLAIN_UTF8;

    static {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        TEXT_PLAIN_UTF8 = h;
    }

    @ExceptionHandler(SlackApiException.class)
    public ResponseEntity<byte[]> handleSlackApi(SlackApiException e) {
        log.error("Slack API error: {} - {}", e.getMessage(), e.getResponseBody());
        String body = buildSlackErrorMessage(e);
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN_UTF8, HttpStatus.BAD_GATEWAY);
    }

    /**
     * Build user-facing error message. For missing_scope, parses Slack's "needed" and stresses reinstall + new token.
     * Can be used from controllers that handle errors inline (e.g. ExportController).
     */
    public String buildSlackErrorMessage(SlackApiException e) {
        String msg = e.getMessage() != null ? e.getMessage() : "unknown";
        if ("missing_scope".equals(e.getSlackError())) {
            List<String> needed = parseNeededScopes(e.getResponseBody());
            String neededStr = needed.isEmpty()
                    ? "(see Slack response below)"
                    : String.join(", ", needed);
            String method = e.getSlackMethod() != null ? e.getSlackMethod() : "unknown";
            log.warn("Slack missing_scope: method={}, needed={}, full response={}", method, needed, e.getResponseBody());
            return "Slack API error: missing_scope. Failing method: " + method + ". Slack reports needed: " + neededStr
                    + ". When both admin and user tokens are set, the app retries with the other token; this message means both lacked the scope. Fix: add the needed scope(s) to your Slack app (User Token Scopes or Bot Token Scopes to match your token type), then Reinstall to Workspace and use the new token in config. Raw Slack response: " + (e.getResponseBody() != null ? e.getResponseBody() : "");
        }
        return "Slack API error: " + msg;
    }

    private List<String> parseNeededScopes(String responseBody) {
        List<String> out = new ArrayList<>();
        if (responseBody == null || responseBody.isBlank()) return out;
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode needed = root.has("needed") ? root.get("needed") : null;
            if (needed == null) return out;
            if (needed.isTextual()) {
                out.add(needed.asText());
            } else if (needed.isArray()) {
                for (JsonNode n : needed) {
                    if (n.isTextual()) out.add(n.asText());
                }
            }
        } catch (Exception ignored) {
            // fallback: no specific scope in message
        }
        return out;
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<byte[]> handleValidation(ValidationException e) {
        log.error("Validation failed: {}", e.getMessage());
        String body = "Validation failed: " + (e.getMessage() != null ? e.getMessage() : "unknown");
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN_UTF8, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @ExceptionHandler(SlackRateLimitException.class)
    public ResponseEntity<byte[]> handleRateLimit(SlackRateLimitException e) {
        log.warn("Slack rate limit: {}", e.getMessage());
        String body = "Slack rate limit exceeded. Try again later.";
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN_UTF8, HttpStatus.TOO_MANY_REQUESTS);
    }

    /** Client closed the connection (e.g. timeout or navigated away) before the ZIP could be sent. */
    @ExceptionHandler(Throwable.class)
    public ResponseEntity<byte[]> handleAny(Throwable e) {
        if (isClientAbort(e)) {
            log.warn("Client disconnected before export response could be sent (timeout or connection closed). Export may have completed on the server.");
            String body = "Client disconnected. Export may have completed; try a smaller date range if download fails.";
            return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN_UTF8, HttpStatus.REQUEST_TIMEOUT);
        }
        log.error("Export failed", e);
        String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        String body = "Export failed: " + msg;
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), TEXT_PLAIN_UTF8, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private static boolean isClientAbort(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String name = t.getClass().getName();
            if (name.contains("ClientAbortException") || name.contains("AsyncRequestNotUsableException")) {
                return true;
            }
        }
        return false;
    }
}
