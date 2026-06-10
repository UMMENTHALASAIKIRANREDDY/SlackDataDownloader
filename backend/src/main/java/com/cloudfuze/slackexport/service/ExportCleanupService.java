package com.cloudfuze.slackexport.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Deletes async export ZIP files older than 24 hours from the exports folder.
 * Only removes files named {uuid}.zip (job exports), not the latest-export file.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportCleanupService {

    private static final Pattern JOB_FILE_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.zip");

    @Value("${export.save-dir:exports}")
    private String saveDir;

    @Value("${export.cleanup-max-age-hours:24}")
    private int maxAgeHours;

    @Scheduled(cron = "${export.cleanup-cron:0 0 * * * *}") // every hour
    public void deleteOldExportFiles() {
        Path dir = Paths.get(saveDir).toAbsolutePath().normalize();
        if (!Files.isDirectory(dir)) return;

        long cutoff = Instant.now().minusSeconds(maxAgeHours * 3600L).toEpochMilli();
        try (Stream<Path> list = Files.list(dir)) {
            list.filter(Files::isRegularFile)
                    .filter(p -> JOB_FILE_PATTERN.matcher(p.getFileName().toString()).matches())
                    .filter(p -> {
                        try {
                            return Files.getLastModifiedTime(p).toMillis() < cutoff;
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("Deleted old export file: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("Could not delete old export file {}: {}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.debug("Could not list export dir {}: {}", dir, e.getMessage());
        }
    }
}
