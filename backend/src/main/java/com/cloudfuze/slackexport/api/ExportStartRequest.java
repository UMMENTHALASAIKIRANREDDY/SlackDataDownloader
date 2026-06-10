package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to start an async export job.
 * Set exportType and the corresponding payload (manual or all-dms).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportStartRequest {
    /** "manual", "all-dms", or "private-channels" */
    private String exportType;
    /** Used when exportType is "manual". Same shape as POST /api/export/slack-dm-mpim. */
    private ExportRequest manualPayload;
    /** Used when exportType is "all-dms". Same shape as POST /api/export/all-dms-dual. */
    private ExportAllDmsRequest allDmsPayload;
    /** Used when exportType is "private-channels". Same shape as ExportAllDmsRequest (exportMode, fromDate, toDate). */
    private ExportAllDmsRequest privateChannelsPayload;
    /** Used when exportType is "private-channels-manual". Channel IDs + fromDate + toDate. */
    private ExportPrivateChannelsManualRequest privateChannelsManualPayload;
}
