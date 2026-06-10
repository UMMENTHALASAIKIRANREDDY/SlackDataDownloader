package com.cloudfuze.slackexport.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Data for one private channel ready for export.
 * Used only by PrivateChannelExportServiceDualToken. Does not affect DM export.
 */
@Data
@Builder
public class PrivateChannelExportData {
    private String channelId;
    private String name;
    private long created;
    private String creator;
    private boolean isArchived;
    private boolean isGeneral;
    private JsonNode topic;
    private JsonNode purpose;
    @Builder.Default
    private List<String> members = new ArrayList<>();
    @Builder.Default
    private List<JsonNode> messages = new ArrayList<>();
    /** Token used to fetch this channel (admin or user). */
    private String tokenSource;
}
