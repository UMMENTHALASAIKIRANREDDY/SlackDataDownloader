package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Request for manual private channel export. User provides channel IDs and date range.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportPrivateChannelsManualRequest {

    /** Private channel IDs (e.g. C01234ABCDE). Comma-separated or list. */
    private List<String> channelIds;

    /** From date (YYYY-MM-DD). Required. */
    private String fromDate;

    /** To date (YYYY-MM-DD). Required. */
    private String toDate;
}
