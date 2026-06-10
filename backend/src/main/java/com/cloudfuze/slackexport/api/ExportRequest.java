package com.cloudfuze.slackexport.api;

import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportRequest {

    /** Optional override. Else from config (slack.tokens.user). */
    private String slackAccessToken;
    /** Optional override. Else from config (slack.tokens.admin). */
    private String adminToken;

    /** One-on-one DM channel IDs (e.g. D01XXXXXXXX). Input in backend code or request. */
    private List<String> dmChannelIds;
    /** Group DM (MPIM) channel IDs (e.g. C0AXXXXXXXX). Input in backend code or request. */
    private List<String> mpimChannelIds;

    /** Legacy: one-on-one DMs by channel ID. Used if dmChannelIds not provided. */
    private List<@Valid DmEntry> dmEntries;
    /** Legacy: group DMs by channel ID. Group name comes from conversations.info. */
    private List<@Valid GroupDmEntry> groupDms;

    /** From date (YYYY-MM-DD). Inclusive. */
    private String fromDate;
    /** To date (YYYY-MM-DD). Inclusive. */
    private String toDate;

    /** Set by backend from fromDate/toDate. */
    private String startTs;
    private String endTs;

    private Integer messageLimitPerChannel;
}
