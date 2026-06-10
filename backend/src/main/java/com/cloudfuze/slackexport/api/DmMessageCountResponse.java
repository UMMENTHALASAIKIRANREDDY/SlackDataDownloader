package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Response for GET /api/dm/{channelId}/message-count */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DmMessageCountResponse {
    private String channelId;
    private long messageCount;
}
