package com.cloudfuze.slackexport.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Saves the latest "Export All DMs" ZIP to disk so the user can download it later
 * if the browser missed the response (e.g. system sleep, tab closed).
 */
@Slf4j
@Service
public class ExportStorageService {

    private static final String LATEST_FILENAME = "all-dms-export-latest.zip";

    @Value("${export.save-dir:exports}")
    private String saveDir;

    /**
     * Save the ZIP to disk. Called after each successful "Export All DMs" build.
     */
    public void saveLatestAllDmsExport(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) return;
        try {
            Path dir = Paths.get(saveDir).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path file = dir.resolve(LATEST_FILENAME);
            Files.write(file, zipBytes);
            log.info("Saved latest export to {} ({} bytes)", file, zipBytes.length);
        } catch (IOException e) {
            log.warn("Could not save latest export to disk: {}", e.getMessage());
        }
    }

    /**
     * Read the most recently saved export, if any.
     */
    public Optional<byte[]> getLatestAllDmsExport() {
        try {
            Path file = Paths.get(saveDir).toAbsolutePath().normalize().resolve(LATEST_FILENAME);
            if (!Files.isRegularFile(file)) return Optional.empty();
            return Optional.of(Files.readAllBytes(file));
        } catch (IOException e) {
            log.debug("Could not read latest export: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
