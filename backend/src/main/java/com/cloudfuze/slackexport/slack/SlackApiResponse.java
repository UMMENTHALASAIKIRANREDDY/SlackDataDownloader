package com.cloudfuze.slackexport.slack;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * Generic wrapper for Slack API responses.
 * ok=false with error string indicates failure.
 */
@Data
public class SlackApiResponse {
    private boolean ok;
    private String error;
    private JsonNode responseMetadata;
    private boolean hasMore;

    public String getNextCursor() {
        if (responseMetadata == null || !responseMetadata.has("next_cursor")) {
            return null;
        }
        JsonNode cursor = responseMetadata.get("next_cursor");
        return cursor != null && !cursor.isNull() ? cursor.asText() : null;
    }
}
