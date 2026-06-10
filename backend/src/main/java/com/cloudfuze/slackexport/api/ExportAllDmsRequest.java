package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request for "Export All My DMs" — uses token from config, optional date range.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportAllDmsRequest {

    /** FULL = all history per channel (created to today). CUSTOM = use fromDate/toDate. */
    private String exportMode;

    /** Alternative to exportMode: ALL_HISTORY (same as FULL), DATE_RANGE (same as CUSTOM). Takes precedence when set. */
    private String mode;

    /** Required when exportMode/mode is CUSTOM or DATE_RANGE. YYYY-MM-DD. */
    private String fromDate;

    /** Required when exportMode/mode is CUSTOM or DATE_RANGE. YYYY-MM-DD. */
    private String toDate;
}
