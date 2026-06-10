package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportJobStatusResponse {
    private String jobId;
    private ExportJobStatus status;
    /** Present when status is COMPLETED. Path to download the ZIP (e.g. /api/export/download/{jobId}). */
    private String downloadUrl;
    /** Suggested filename for download (e.g. Dm-2024-01-01-to-2024-01-31.zip). */
    private String suggestedFilename;
    /** Present when status is FAILED. */
    private String errorMessage;
}
