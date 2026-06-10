package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One Group DM (MPIM) from UI. Only channel ID required; group name and members are auto-fetched from the channel. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GroupDmEntry {
    private String channelId;
}
