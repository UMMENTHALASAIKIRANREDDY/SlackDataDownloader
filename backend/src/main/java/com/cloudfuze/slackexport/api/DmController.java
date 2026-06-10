package com.cloudfuze.slackexport.api;

import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.service.SlackFetchService;
import com.cloudfuze.slackexport.slack.SlackApiException;
import com.cloudfuze.slackexport.slack.SlackRateLimitException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * DM-only endpoints (e.g. message count). Does not affect export endpoints.
 */
@Slf4j
@RestController
@RequestMapping("/api/dm")
@RequiredArgsConstructor
public class DmController {

    private final SlackFetchService slackFetchService;
    private final SlackTokenConfig slackTokenConfig;

    /**
     * Get message count for a DM or Group DM channel.
     * Optional query params: fromDate, toDate (YYYY-MM-DD). If omitted, counts entire history.
     * Uses slack.tokens.user from config.
     */
    @GetMapping(value = "/{channelId}/message-count", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> getMessageCount(
            @PathVariable String channelId,
            @RequestParam(required = false) String fromDate,
            @RequestParam(required = false) String toDate) {
        if (channelId == null || channelId.isBlank()) {
            return ResponseEntity.badRequest().body("channelId is required.");
        }
        String token = slackTokenConfig.getToken();
        if (token == null || token.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Slack user token is required. Set slack.tokens.user in config.");
        }
        String startTs = null;
        String endTs = null;
        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()) {
            try {
                LocalDate from = LocalDate.parse(fromDate.trim());
                LocalDate to = LocalDate.parse(toDate.trim());
                if (to.isBefore(from)) {
                    return ResponseEntity.badRequest().body("toDate must be on or after fromDate.");
                }
                ZonedDateTime start = from.atStartOfDay(ZoneOffset.UTC);
                ZonedDateTime end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
                startTs = String.valueOf(start.toEpochSecond());
                endTs = String.valueOf(end.toEpochSecond());
            } catch (Exception e) {
                return ResponseEntity.badRequest().body("Invalid fromDate or toDate: " + e.getMessage());
            }
        }
        try {
            Long count = slackFetchService.countMessagesInChannel(token, channelId.trim(), startTs, endTs).block();
            return ResponseEntity.ok(DmMessageCountResponse.builder()
                    .channelId(channelId.trim())
                    .messageCount(count != null ? count : 0)
                    .build());
        } catch (SlackApiException e) {
            log.error("Slack API error for channel {}: {}", channelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(e.getMessage());
        } catch (SlackRateLimitException e) {
            log.warn("Slack rate limit for channel {}: {}", channelId, e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Slack rate limit exceeded. Try again later.");
        } catch (Exception e) {
            log.error("Message count failed for channel {}", channelId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to count messages: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }
}
