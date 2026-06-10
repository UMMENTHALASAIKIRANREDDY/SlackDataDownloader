package com.cloudfuze.slackexport.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * All data for one channel (DM or MPIM) ready for export.
 * - channelInfo: from conversations.info (for dms.json / mpims.json)
 * - messages: all root-level messages + thread replies (replies reference parent thread_ts)
 *   Messages are preserved as JsonNode (no transformation); blocks and rich_text unchanged.
 */
@Data
@Builder
public class ChannelExportData {
    /** Channel ID (D... or G...) */
    private String channelId;
    /** true = one-on-one DM, false = Group DM (MPIM) */
    private boolean isDm;
    /** Group name from UI (MPIM only). Used as folder name. */
    private String groupName;
    /** Channel metadata from conversations.info */
    private JsonNode channelInfo;
    /** All messages for this channel, ordered by ts. Includes thread replies. */
    @Builder.Default
    private List<JsonNode> messages = new ArrayList<>();
    /** Full member IDs from conversations.members (optional). When set, used in dms.json/mpims.json. */
    private List<String> members;
}
