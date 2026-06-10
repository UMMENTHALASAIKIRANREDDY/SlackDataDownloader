package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One one-on-one DM from UI. Only channel ID required; users are auto-resolved from the channel. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DmEntry {
    private String channelId;
}
