package com.cloudfuze.slackexport.api;

import com.cloudfuze.slackexport.config.SlackTokenConfig;
import com.cloudfuze.slackexport.service.ExportAllDmsDualTokenService;
import com.cloudfuze.slackexport.service.ExportAllDmsService;
import com.cloudfuze.slackexport.service.ExportJobService;
import com.cloudfuze.slackexport.service.ExportOrchestrator;
import com.cloudfuze.slackexport.service.ExportStorageService;
import com.cloudfuze.slackexport.slack.SlackApiException;
import com.cloudfuze.slackexport.slack.SlackRateLimitException;
import com.cloudfuze.slackexport.validation.ValidationException;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Export one-on-one DMs and Group DMs into a single ZIP. fromDate/toDate define date range.
 */
@Slf4j
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportOrchestrator exportOrchestrator;
    private final ExportAllDmsService exportAllDmsService;
    private final ExportAllDmsDualTokenService exportAllDmsDualTokenService;
    private final com.cloudfuze.slackexport.service.PrivateChannelExportServiceDualToken privateChannelExportServiceDualToken;
    private final ExportStorageService exportStorageService;
    private final ExportJobService exportJobService;
    private final SlackTokenConfig slackTokenConfig;
    private final GlobalExceptionHandler globalExceptionHandler;

    // --- Async export job (start → poll status → download link) ---

    @PostMapping(value = "/start", produces = "application/json")
    public ResponseEntity<?> startExportJob(@RequestBody ExportStartRequest request) {
        try {
            String jobId = exportJobService.startJob(request);
            log.info("Export job started: jobId={}, exportType={}", jobId, request.getExportType());
            return ResponseEntity.ok(java.util.Map.of("jobId", jobId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping(value = "/status/{jobId}", produces = "application/json")
    public ResponseEntity<ExportJobStatusResponse> getExportJobStatus(@PathVariable String jobId) {
        ExportJobStatusResponse response = exportJobService.getStatusResponse(jobId);
        if (response == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(response);
    }

    @GetMapping(value = "/download/{jobId}", produces = "application/zip")
    public ResponseEntity<byte[]> downloadExport(@PathVariable String jobId) {
        Optional<Path> filePath = exportJobService.getExportPath(jobId);
        if (filePath.isEmpty() || !Files.isRegularFile(filePath.get())) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] zipBytes = Files.readAllBytes(filePath.get());
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType("application/zip"));
            String filename = exportJobService.getJob(jobId)
                    .map(j -> j.getSuggestedFilename())
                    .filter(f -> f != null && !f.isBlank())
                    .orElse("slack-export-" + jobId + ".zip");
            headers.setContentDispositionFormData("attachment", filename);
            headers.setContentLength(zipBytes.length);
            return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.error("Failed to read export file for job {}", jobId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PostMapping(value = "/slack-dm-mpim", produces = "application/zip")
    public ResponseEntity<byte[]> exportSlackDmMpim(@Valid @RequestBody ExportRequest request) {
        String userToken = request.getSlackAccessToken() != null ? request.getSlackAccessToken().trim() : null;
        String adminToken = request.getAdminToken() != null ? request.getAdminToken().trim() : null;
        if (userToken == null || userToken.isEmpty()) {
            userToken = slackTokenConfig.getToken();
            if (userToken != null) request.setSlackAccessToken(userToken);
        } else {
            request.setSlackAccessToken(userToken);
        }
        if (adminToken == null || adminToken.isEmpty()) {
            adminToken = slackTokenConfig.getAdminToken();
            if (adminToken != null) request.setAdminToken(adminToken);
        } else {
            request.setAdminToken(adminToken);
        }
        if ((userToken == null || userToken.isEmpty()) && (adminToken == null || adminToken.isEmpty())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("At least one Slack token is required. Set slack.tokens.admin and/or slack.tokens.user in config.".getBytes(StandardCharsets.UTF_8));
        }

        String fromDate = request.getFromDate();
        String toDate = request.getToDate();
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("fromDate and toDate are required.".getBytes(StandardCharsets.UTF_8));
        }
        try {
            LocalDate from = LocalDate.parse(fromDate.trim());
            LocalDate to = LocalDate.parse(toDate.trim());
            if (to.isBefore(from)) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                        .body("toDate must be on or after fromDate.".getBytes(StandardCharsets.UTF_8));
            }
            ZonedDateTime start = from.atStartOfDay(ZoneOffset.UTC);
            ZonedDateTime end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).minusSeconds(1);
            request.setStartTs(String.valueOf(start.toEpochSecond()));
            request.setEndTs(String.valueOf(end.toEpochSecond()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body(("Invalid fromDate or toDate: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }

        boolean hasDmIds = request.getDmChannelIds() != null && !request.getDmChannelIds().isEmpty();
        boolean hasMpimIds = request.getMpimChannelIds() != null && !request.getMpimChannelIds().isEmpty();
        boolean hasDmEntries = request.getDmEntries() != null && request.getDmEntries().stream().anyMatch(e -> e != null && e.getChannelId() != null && !e.getChannelId().isBlank());
        boolean hasGroupDms = request.getGroupDms() != null && request.getGroupDms().stream().anyMatch(g -> g != null && g.getChannelId() != null && !g.getChannelId().isBlank());
        boolean hasConfigChannels = !slackTokenConfig.getDmChannelIdsFromConfig().isEmpty() || !slackTokenConfig.getMpimChannelIdsFromConfig().isEmpty();
        if (!hasDmIds && !hasMpimIds && !hasDmEntries && !hasGroupDms && !hasConfigChannels) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("At least one DM or MPIM channel is required (dmChannelIds, mpimChannelIds, dmEntries, groupDms, or config slack.export.dm-channel-ids/mpim-channel-ids).".getBytes(StandardCharsets.UTF_8));
        }

        List<String> dmIdsFromEntries = request.getDmEntries() != null ? request.getDmEntries().stream()
                .filter(e -> e != null && e.getChannelId() != null && !e.getChannelId().isBlank())
                .map(e -> e.getChannelId().trim()).toList() : List.of();
        List<String> mpimIdsFromGroups = request.getGroupDms() != null ? request.getGroupDms().stream()
                .filter(g -> g != null && g.getChannelId() != null && !g.getChannelId().isBlank())
                .map(g -> g.getChannelId().trim()).toList() : List.of();
        log.info("Export request: fromDate={}, toDate={}, dmEntries.channelIds={}, groupDms.channelIds={}", fromDate, toDate, dmIdsFromEntries, mpimIdsFromGroups);

        return exportOrchestrator.runExport(request)
                .map(zipBytes -> {
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new IllegalStateException("Export produced empty file. No data to download.");
                    }
                    String filename = "Dm-" + fromDate.trim() + "-to-" + toDate.trim() + ".zip";
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", filename);
                    headers.setContentLength(zipBytes.length);
                    return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
                })
                .onErrorResume(SlackApiException.class, e -> {
                    log.error("Slack API error: {} - {}", e.getMessage(), e.getResponseBody());
                    String body = globalExceptionHandler.buildSlackErrorMessage(e);
                    return Mono.just(errorResponse(HttpStatus.BAD_GATEWAY, body));
                })
                .onErrorResume(ValidationException.class, e -> {
                    log.error("Validation failed: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, "Validation failed: " + nullToEmpty(e.getMessage())));
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("Slack rate limit exceeded: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Slack rate limit exceeded. Try again later."));
                })
                .onErrorResume(Exception.class, e -> {
                    log.error("Export failed", e);
                    String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + msg));
                })
                .block();
    }

    /** Resolve export mode: prefer mode (ALL_HISTORY->FULL, DATE_RANGE->CUSTOM), else exportMode. Returns FULL or CUSTOM, or null. */
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

    @PostMapping(value = "/all-dms", produces = "application/zip")
    public ResponseEntity<byte[]> exportAllDms(@RequestBody ExportAllDmsRequest request) {
        String mode = resolveExportMode(request);
        if (mode == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("exportMode or mode is required (FULL/CUSTOM or ALL_HISTORY/DATE_RANGE).".getBytes(StandardCharsets.UTF_8));
        }
        log.info("Export all DMs request: mode={}", mode);
        request.setExportMode(mode);

        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;
        String dmFilename = (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank())
                ? "Dm-" + fromDate + "-to-" + toDate + ".zip" : "Dm-export.zip";
        return exportAllDmsService.exportAllUserDms(request)
                .map(zipBytes -> {
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new IllegalStateException("Export produced empty file. No data to download.");
                    }
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", dmFilename);
                    headers.setContentLength(zipBytes.length);
                    return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
                })
                .onErrorResume(SlackApiException.class, e -> {
                    log.error("Slack API error: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.BAD_GATEWAY, globalExceptionHandler.buildSlackErrorMessage(e)));
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("Slack rate limit: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Slack rate limit exceeded. Try again later."));
                })
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(errorResponse(HttpStatus.BAD_REQUEST, nullToEmpty(e.getMessage()))))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, nullToEmpty(e.getMessage()))))
                .onErrorResume(Exception.class, e -> {
                    log.error("Export all DMs failed", e);
                    return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + nullToEmpty(e.getMessage())));
                })
                .block();
    }

    /**
     * Get count of DMs and group DMs from both tokens (admin + user), deduplicated by channel ID.
     * Use in "Export All My DMs" tab to show how many channels will be exported before downloading.
     */
    @GetMapping(value = "/all-dms-count", produces = "application/json")
    public ResponseEntity<?> getAllDmsCount() {
        try {
            return ResponseEntity.ok(exportAllDmsDualTokenService.getDmCountDualToken().block());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(e.getMessage());
        } catch (SlackApiException e) {
            log.error("Slack API error: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(globalExceptionHandler.buildSlackErrorMessage(e));
        } catch (SlackRateLimitException e) {
            log.warn("Slack rate limit: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("Slack rate limit exceeded. Try again later.");
        } catch (Exception e) {
            log.error("All DMs count failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Failed to fetch DM count: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
        }
    }

    /**
     * Download the most recently completed "Export All DMs" ZIP from disk.
     * Use this if the browser missed the response (e.g. system sleep, tab refreshed).
     */
    @GetMapping(value = "/latest-download", produces = "application/zip")
    public ResponseEntity<byte[]> getLatestExportDownload() {
        Optional<byte[]> zip = exportStorageService.getLatestAllDmsExport();
        if (zip.isEmpty() || zip.get().length == 0) {
            return ResponseEntity.notFound().build();
        }
        byte[] zipBytes = zip.get();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("application/zip"));
        headers.setContentDispositionFormData("attachment", "Dm-export.zip");
        headers.setContentLength(zipBytes.length);
        return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
    }

    /**
     * Manual private channel export: provide channel IDs and date range. Sync endpoint.
     */
    @PostMapping(value = "/private-channels-manual", produces = "application/zip")
    public ResponseEntity<byte[]> exportPrivateChannelsManual(@RequestBody ExportPrivateChannelsManualRequest request) {
        if (request.getChannelIds() == null || request.getChannelIds().isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("At least one private channel ID is required.".getBytes(StandardCharsets.UTF_8));
        }
        if (request.getFromDate() == null || request.getFromDate().isBlank() || request.getToDate() == null || request.getToDate().isBlank()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("fromDate and toDate are required.".getBytes(StandardCharsets.UTF_8));
        }
        log.info("Export private channels (manual) request: channelIds={}", request.getChannelIds());

        String fromDate = request.getFromDate().trim();
        String toDate = request.getToDate().trim();
        String filename = "PrivateChannel-" + fromDate + "-to-" + toDate + ".zip";
        return privateChannelExportServiceDualToken.exportPrivateChannelsManual(request)
                .map(zipBytes -> {
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new IllegalStateException("Export produced empty file.");
                    }
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", filename);
                    headers.setContentLength(zipBytes.length);
                    return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
                })
                .onErrorResume(SlackApiException.class, e -> {
                    log.error("Slack API error: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.BAD_GATEWAY, globalExceptionHandler.buildSlackErrorMessage(e)));
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("Slack rate limit: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Slack rate limit exceeded. Try again later."));
                })
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(errorResponse(HttpStatus.BAD_REQUEST, nullToEmpty(e.getMessage()))))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, nullToEmpty(e.getMessage()))))
                .onErrorResume(Exception.class, e -> {
                    log.error("Export private channels (manual) failed", e);
                    return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + nullToEmpty(e.getMessage())));
                })
                .block();
    }

    /**
     * Export all private channels (dual token). Sync endpoint.
     */
    @PostMapping(value = "/private-channels-dual", produces = "application/zip")
    public ResponseEntity<byte[]> exportPrivateChannelsDual(@RequestBody ExportAllDmsRequest request) {
        String mode = resolveExportMode(request);
        if (mode == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("exportMode or mode is required (FULL/CUSTOM or ALL_HISTORY/DATE_RANGE).".getBytes(StandardCharsets.UTF_8));
        }
        log.info("Export private channels (dual token) request: mode={}", mode);
        request.setExportMode(mode);

        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;
        String privateFilename = (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank())
                ? "PrivateChannel-" + fromDate + "-to-" + toDate + ".zip" : "PrivateChannel-export.zip";
        return privateChannelExportServiceDualToken.exportPrivateChannelsDualToken(request)
                .map(zipBytes -> {
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new IllegalStateException("Export produced empty file.");
                    }
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", privateFilename);
                    headers.setContentLength(zipBytes.length);
                    return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
                })
                .onErrorResume(SlackApiException.class, e -> {
                    log.error("Slack API error: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.BAD_GATEWAY, globalExceptionHandler.buildSlackErrorMessage(e)));
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("Slack rate limit: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Slack rate limit exceeded. Try again later."));
                })
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(errorResponse(HttpStatus.BAD_REQUEST, nullToEmpty(e.getMessage()))))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, nullToEmpty(e.getMessage()))))
                .onErrorResume(Exception.class, e -> {
                    log.error("Export private channels failed", e);
                    return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + nullToEmpty(e.getMessage())));
                })
                .block();
    }

    @PostMapping(value = "/all-dms-dual", produces = "application/zip")
    public ResponseEntity<byte[]> exportAllDmsDual(@RequestBody ExportAllDmsRequest request) {
        String mode = resolveExportMode(request);
        if (mode == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .contentType(MediaType.parseMediaType("text/plain;charset=UTF-8"))
                    .body("exportMode or mode is required (FULL/CUSTOM or ALL_HISTORY/DATE_RANGE).".getBytes(StandardCharsets.UTF_8));
        }
        log.info("Export all DMs (dual token) request: mode={}", mode);
        request.setExportMode(mode);

        String fromDate = request.getFromDate() != null ? request.getFromDate().trim() : null;
        String toDate = request.getToDate() != null ? request.getToDate().trim() : null;
        String dmFilename = (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank())
                ? "Dm-" + fromDate + "-to-" + toDate + ".zip" : "Dm-export.zip";
        return exportAllDmsDualTokenService.exportAllUserDmsDualToken(request)
                .map(zipBytes -> {
                    if (zipBytes == null || zipBytes.length == 0) {
                        throw new IllegalStateException("Export produced empty file. No data to download.");
                    }
                    exportStorageService.saveLatestAllDmsExport(zipBytes);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.parseMediaType("application/zip"));
                    headers.setContentDispositionFormData("attachment", dmFilename);
                    headers.setContentLength(zipBytes.length);
                    return new ResponseEntity<>(zipBytes, headers, HttpStatus.OK);
                })
                .onErrorResume(SlackApiException.class, e -> {
                    log.error("Slack API error: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.BAD_GATEWAY, globalExceptionHandler.buildSlackErrorMessage(e)));
                })
                .onErrorResume(SlackRateLimitException.class, e -> {
                    log.warn("Slack rate limit: {}", e.getMessage());
                    return Mono.just(errorResponse(HttpStatus.TOO_MANY_REQUESTS, "Slack rate limit exceeded. Try again later."));
                })
                .onErrorResume(IllegalArgumentException.class, e -> Mono.just(errorResponse(HttpStatus.BAD_REQUEST, nullToEmpty(e.getMessage()))))
                .onErrorResume(IllegalStateException.class, e -> Mono.just(errorResponse(HttpStatus.UNPROCESSABLE_ENTITY, nullToEmpty(e.getMessage()))))
                .onErrorResume(Exception.class, e -> {
                    log.error("Export all DMs (dual) failed", e);
                    return Mono.just(errorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Export failed: " + nullToEmpty(e.getMessage())));
                })
                .block();
    }

    private static ResponseEntity<byte[]> errorResponse(HttpStatus status, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/plain;charset=UTF-8"));
        return new ResponseEntity<>(body.getBytes(StandardCharsets.UTF_8), headers, status);
    }

    private static String nullToEmpty(String s) {
        return s != null ? s : "";
    }
}
