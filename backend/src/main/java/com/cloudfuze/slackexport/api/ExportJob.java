package com.cloudfuze.slackexport.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExportJob {
    private String jobId;
    private ExportJobStatus status;
    private String filePath;
    private String suggestedFilename;
    private Instant createdAt;
    private Instant completedAt;
    private String errorMessage;
}
