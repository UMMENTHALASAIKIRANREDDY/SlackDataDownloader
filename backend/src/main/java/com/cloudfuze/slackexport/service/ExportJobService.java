package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.api.*;
import com.cloudfuze.slackexport.config.SlackTokenConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Starts async export jobs and processes them in the background.
 * Uses existing ExportOrchestrator and ExportAllDmsDualTokenService without modifying their logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportJobService {

    @Value("${export.save-dir:exports}")
    private String saveDir;

    private final ExportJobStore jobStore;
    private final ExportOrchestrator exportOrchestrator;
    private final ExportAllDmsDualTokenService exportAllDmsDualTokenService;
    private final PrivateChannelExportServiceDualToken privateChannelExportServiceDualToken;
    private final ExportStorageService exportStorageService;
    private final SlackTokenConfig slackTokenConfig;

    /** Self-injection so processExport() runs via proxy (actually async). Same-class calls bypass @Async. */
    @Lazy
    @Autowired
    private ExportJobService self;

    /**
     * Create a job and start processing in the background. Returns immediately with jobId.
     */
    public String startJob(ExportStartRequest request) {
        String exportType = request.getExportType() != null ? request.getExportType().trim().toLowerCase() : null;
        if (!"manual".equals(exportType) && !"all-dms".equals(exportType) && !"private-channels".equals(exportType) && !"private-channels-manual".equals(exportType)) {
            throw new IllegalArgumentException("exportType must be 'manual', 'all-dms', 'private-channels', or 'private-channels-manual'.");
        }
        if ("manual".equals(exportType) && request.getManualPayload() == null) {
            throw new IllegalArgumentException("manualPayload is required when exportType is 'manual'.");
        }
        if ("all-dms".equals(exportType) && request.getAllDmsPayload() == null) {
            throw new IllegalArgumentException("allDmsPayload is required when exportType is 'all-dms'.");
        }
        if ("private-channels".equals(exportType) && request.getPrivateChannelsPayload() == null) {
            throw new IllegalArgumentException("privateChannelsPayload is required when exportType is 'private-channels'.");
        }
        if ("private-channels-manual".equals(exportType) && request.getPrivateChannelsManualPayload() == null) {
            throw new IllegalArgumentException("privateChannelsManualPayload is required when exportType is 'private-channels-manual'.");
        }

        String jobId = UUID.randomUUID().toString();
        ExportJob job = ExportJob.builder()
                .jobId(jobId)
                .status(ExportJobStatus.PENDING)
                .createdAt(Instant.now())
                .build();
        jobStore.save(job);

        self.processExport(jobId, request);
        return jobId;
    }

    @Async("exportJobExecutor")
    public void processExport(String jobId, ExportStartRequest request) {
        ExportJob job = jobStore.findById(jobId).orElse(null);
        if (job == null) return;

        job.setStatus(ExportJobStatus.PROCESSING);
        jobStore.save(job);

        try {
            byte[] zipBytes;
            String exportType = request.getExportType().trim().toLowerCase();

            if ("manual".equals(exportType)) {
                ExportRequest req = request.getManualPayload();
                prepareManualRequest(req);
                zipBytes = exportOrchestrator.runExport(req).block();
            } else if ("all-dms".equals(exportType)) {
                ExportAllDmsRequest req = request.getAllDmsPayload();
                String mode = resolveExportMode(req);
                if (mode == null) {
                    throw new IllegalArgumentException("exportMode or mode is required (FULL/CUSTOM or ALL_HISTORY/DATE_RANGE).");
                }
                req.setExportMode(mode);
                zipBytes = exportAllDmsDualTokenService.exportAllUserDmsDualToken(req).block();
            } else if ("private-channels".equals(exportType)) {
                ExportAllDmsRequest req = request.getPrivateChannelsPayload();
                String mode = resolveExportMode(req);
                if (mode == null) {
                    throw new IllegalArgumentException("exportMode or mode is required (FULL/CUSTOM or ALL_HISTORY/DATE_RANGE).");
                }
                req.setExportMode(mode);
                zipBytes = privateChannelExportServiceDualToken.exportPrivateChannelsDualToken(req).block();
            } else {
                zipBytes = privateChannelExportServiceDualToken.exportPrivateChannelsManual(request.getPrivateChannelsManualPayload()).block();
            }

            if (zipBytes == null || zipBytes.length == 0) {
                throw new IllegalStateException("Export produced empty file.");
            }

            String suggestedFilename = buildSuggestedFilename(request);
            Path dir = Paths.get(saveDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(jobId + ".zip");
            Files.write(file, zipBytes);

            String completedType = request.getExportType().trim().toLowerCase();
            if ("all-dms".equals(completedType)) {
                exportStorageService.saveLatestAllDmsExport(zipBytes);
            }

            job.setStatus(ExportJobStatus.COMPLETED);
            job.setFilePath(file.toString());
            job.setSuggestedFilename(suggestedFilename);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(null);
            jobStore.save(job);
            log.info("Export job {} completed. File: {}", jobId, file);

        } catch (Exception e) {
            log.error("Export job {} failed", jobId, e);
            job.setStatus(ExportJobStatus.FAILED);
            job.setCompletedAt(Instant.now());
            job.setErrorMessage(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            jobStore.save(job);
        }
    }

    private void prepareManualRequest(ExportRequest request) {
        String token = request.getSlackAccessToken() != null ? request.getSlackAccessToken().trim() : null;
        if (token == null || token.isEmpty()) {
            token = slackTokenConfig.getToken();
            if (token == null || token.isEmpty()) {
                throw new IllegalArgumentException("Slack user token is required.");
            }
        }
        request.setSlackAccessToken(token);

        String fromDate = request.getFromDate();
        String toDate = request.getToDate();
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw new IllegalArgumentException("fromDate and toDate are required.");
        }
        LocalDate from = LocalDate.parse(fromDate.trim());
        LocalDate to = LocalDate.parse(toDate.trim());
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate must be on or after fromDate.");
        }
        ZonedDateTime start = from.atStartOfDay(ZoneOffset.UTC);
        ZonedDateTime end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
        request.setStartTs(String.valueOf(start.toEpochSecond()));
        request.setEndTs(String.valueOf(end.toEpochSecond()));

        boolean hasChannels = (request.getDmChannelIds() != null && !request.getDmChannelIds().isEmpty())
                || (request.getMpimChannelIds() != null && !request.getMpimChannelIds().isEmpty())
                || (request.getDmEntries() != null && request.getDmEntries().stream().anyMatch(e -> e != null && e.getChannelId() != null && !e.getChannelId().isBlank()))
                || (request.getGroupDms() != null && request.getGroupDms().stream().anyMatch(g -> g != null && g.getChannelId() != null && !g.getChannelId().isBlank()))
                || !slackTokenConfig.getDmChannelIdsFromConfig().isEmpty()
                || !slackTokenConfig.getMpimChannelIdsFromConfig().isEmpty();
        if (!hasChannels) {
            throw new IllegalArgumentException("At least one DM or MPIM channel is required.");
        }
    }

    private static String buildSuggestedFilename(ExportStartRequest request) {
        String exportType = request.getExportType() != null ? request.getExportType().trim().toLowerCase() : "";
        String from = null;
        String to = null;
        if ("manual".equals(exportType) && request.getManualPayload() != null) {
            from = request.getManualPayload().getFromDate();
            to = request.getManualPayload().getToDate();
        } else if ("all-dms".equals(exportType) && request.getAllDmsPayload() != null) {
            from = request.getAllDmsPayload().getFromDate();
            to = request.getAllDmsPayload().getToDate();
        } else if ("private-channels".equals(exportType) && request.getPrivateChannelsPayload() != null) {
            from = request.getPrivateChannelsPayload().getFromDate();
            to = request.getPrivateChannelsPayload().getToDate();
        } else if ("private-channels-manual".equals(exportType) && request.getPrivateChannelsManualPayload() != null) {
            from = request.getPrivateChannelsManualPayload().getFromDate();
            to = request.getPrivateChannelsManualPayload().getToDate();
        }
        String prefix = ("private-channels".equals(exportType) || "private-channels-manual".equals(exportType))
                ? "PrivateChannel" : "Dm";
        if (from != null && !from.isBlank() && to != null && !to.isBlank()) {
            return prefix + "-" + from.trim() + "-to-" + to.trim() + ".zip";
        }
        return prefix + "-export.zip";
    }

    private static String resolveExportMode(ExportAllDmsRequest request) {
        if (request == null) return null;
        String m = request.getMode();
        if (m != null && !m.isBlank()) {
            String u = m.trim().toUpperCase();
            if ("ALL_HISTORY".equals(u)) return "FULL";
            if ("DATE_RANGE".equals(u)) return "CUSTOM";
        }
        String e = request.getExportMode();
        if (e != null && !e.isBlank()) {
            String u = e.trim().toUpperCase();
            if ("FULL".equals(u) || "CUSTOM".equals(u)) return u;
        }
        return null;
    }

    public Optional<ExportJob> getJob(String jobId) {
        return jobStore.findById(jobId);
    }

    public ExportJobStatusResponse getStatusResponse(String jobId) {
        return jobStore.findById(jobId)
                .map(job -> {
                    String downloadUrl = job.getStatus() == ExportJobStatus.COMPLETED && job.getFilePath() != null
                            ? "/api/export/download/" + jobId
                            : null;
                    return ExportJobStatusResponse.builder()
                            .jobId(job.getJobId())
                            .status(job.getStatus())
                            .downloadUrl(downloadUrl)
                            .suggestedFilename(job.getSuggestedFilename())
                            .errorMessage(job.getErrorMessage())
                            .build();
                })
                .orElse(null);
    }

    public Optional<Path> getExportPath(String jobId) {
        return jobStore.findById(jobId)
                .filter(j -> j.getStatus() == ExportJobStatus.COMPLETED && j.getFilePath() != null)
                .map(ExportJob::getFilePath)
                .map(Paths::get);
    }
}
