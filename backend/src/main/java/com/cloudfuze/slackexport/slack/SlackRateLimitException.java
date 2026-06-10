package com.cloudfuze.slackexport.slack;

import lombok.Getter;

@Getter
public class SlackRateLimitException extends RuntimeException {
    private final int retryAfterSeconds;
    private final String responseBody;

    public SlackRateLimitException(String message, int retryAfterSeconds, String responseBody) {
        super(message);
        this.retryAfterSeconds = retryAfterSeconds;
        this.responseBody = responseBody;
    }
}
