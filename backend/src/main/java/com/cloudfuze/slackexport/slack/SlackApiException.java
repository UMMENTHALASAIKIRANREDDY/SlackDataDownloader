package com.cloudfuze.slackexport.slack;

import lombok.Getter;

@Getter
public class SlackApiException extends RuntimeException {

    private final int httpStatus;
    private final String slackError;
    private final String responseBody;
    /** Slack API method that failed (e.g. conversations.info). Null if unknown. */
    private final String slackMethod;

    public SlackApiException(String message, int httpStatus, String slackError, String responseBody) {
        this(message, httpStatus, slackError, responseBody, null);
    }

    public SlackApiException(String message, int httpStatus, String slackError, String responseBody, String slackMethod) {
        super(message);
        this.httpStatus = httpStatus;
        this.slackError = slackError;
        this.responseBody = responseBody;
        this.slackMethod = slackMethod;
    }

    public SlackApiException(String message, Throwable cause) {
        super(message, cause);
        this.httpStatus = -1;
        this.slackError = null;
        this.responseBody = null;
        this.slackMethod = null;
    }
}
