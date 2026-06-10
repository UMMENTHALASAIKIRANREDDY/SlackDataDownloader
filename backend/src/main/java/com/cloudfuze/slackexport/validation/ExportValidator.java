package com.cloudfuze.slackexport.validation;

import com.cloudfuze.slackexport.service.ChannelExportData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates single ZIP: dms.json and mpims.json at root; folders (DM channel ID or group name) with date files only; no metadata inside folders.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExportValidator {

    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;

    public void validateExportZip(List<ChannelExportData> channels, byte[] zipBytes, String fromDateStr, String toDateStr) throws IOException {
        LocalDate from;
        LocalDate to;
        try {
            from = LocalDate.parse(fromDateStr.trim());
            to = LocalDate.parse(toDateStr.trim());
        } catch (Exception e) {
            throw new ValidationException("Invalid fromDate or toDate for validation");
        }
        int number_of_days = (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;
        Set<String> expectedDates = new LinkedHashSet<>();
        for (int i = 0; i < number_of_days; i++) {
            expectedDates.add(from.plusDays(i).format(DATE_FORMAT));
        }

        Set<String> rootDmsJson = new HashSet<>();
        Set<String> rootMpimsJson = new HashSet<>();
        Map<String, Set<String>> folderToDateFiles = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.contains("..")) throw new ValidationException("Invalid ZIP entry: " + name);

                String[] parts = name.split("/");
                if (parts.length == 1 && name.endsWith(".json")) {
                    if ("dms.json".equals(name)) rootDmsJson.add(name);
                    else if ("mpims.json".equals(name)) rootMpimsJson.add(name);
                    else if ("export-summary.json".equals(name)) { /* allowed: lists succeeded/skipped channels */ }
                    else throw new ValidationException("Root must contain only dms.json, mpims.json, and optionally export-summary.json, found: " + name);
                } else if (parts.length >= 2) {
                    String folder = parts[0] + "/";
                    String file = parts[1];
                    if (parts.length == 2 && file.endsWith(".json")) {
                        String dateKey = file.replace(".json", "");
                        if (isValidDateFormat(dateKey)) {
                            folderToDateFiles.computeIfAbsent(folder, k -> new HashSet<>()).add(dateKey);
                        } else {
                            throw new ValidationException("Folder must contain only date files (YYYY-MM-DD.json), found: " + name);
                        }
                    } else if (parts.length > 2) {
                        throw new ValidationException("No nested folders allowed: " + name);
                    }
                }

                if (name.endsWith(".json")) {
                    byte[] buf = zis.readAllBytes();
                    if (name.equals("dms.json")) validateDmsJsonStructure(buf);
                    else if (name.equals("mpims.json")) validateMpimsJsonStructure(buf);
                    else if (parts.length == 2 && isValidDateFormat(parts[1].replace(".json", ""))) {
                        validateDateFileStructure(buf);
                    }
                }
                zis.closeEntry();
            }
        }

        if (!rootDmsJson.contains("dms.json")) throw new ValidationException("dms.json must be present at ZIP root");
        if (!rootMpimsJson.contains("mpims.json")) throw new ValidationException("mpims.json must be present at ZIP root");

        int totalDateFiles = folderToDateFiles.values().stream().mapToInt(Set::size).sum();
        if (totalDateFiles == 0) {
            throw new ValidationException("At least one date JSON must exist in the ZIP. No message data was added.");
        }

        for (Map.Entry<String, Set<String>> e : folderToDateFiles.entrySet()) {
            String folder = e.getKey();
            Set<String> dateFilesInZip = e.getValue();
            for (String dateKey : dateFilesInZip) {
                if (!expectedDates.contains(dateKey)) {
                    throw new ValidationException("Date file " + dateKey + " in folder " + folder + " is outside selected range.");
                }
            }
        }
    }

    private static String sanitizeFolderName(String name) {
        if (name == null || name.isBlank()) return "group-dm";
        String s = name.trim().replaceAll("[\\\\/:*?\"<>|]+", "-").replaceAll("\\s+", "--");
        return s;
    }

    private boolean isValidDateFormat(String dateKey) {
        if (dateKey.length() != 10) return false;
        try {
            String[] ymd = dateKey.split("-");
            if (ymd.length != 3) return false;
            int y = Integer.parseInt(ymd[0]);
            int m = Integer.parseInt(ymd[1]);
            int d = Integer.parseInt(ymd[2]);
            return y >= 1970 && y <= 2100 && m >= 1 && m <= 12 && d >= 1 && d <= 31;
        } catch (Exception e) {
            return false;
        }
    }

    private void validateDmsJsonStructure(byte[] jsonBytes) throws IOException {
        JsonNode root = objectMapper.readTree(jsonBytes);
        if (root == null || !root.isArray()) throw new ValidationException("dms.json must be a root array");
        for (JsonNode item : root) {
            if (!item.has("id")) throw new ValidationException("dms.json entry must have id");
            if (!item.has("created")) throw new ValidationException("dms.json entry must have created");
            if (!item.has("members") || !item.get("members").isArray()) throw new ValidationException("dms.json entry must have members array");
        }
    }

    private void validateMpimsJsonStructure(byte[] jsonBytes) throws IOException {
        JsonNode root = objectMapper.readTree(jsonBytes);
        if (root == null || !root.isArray()) throw new ValidationException("mpims.json must be a root array");
        for (JsonNode item : root) {
            if (!item.has("id")) throw new ValidationException("mpims.json entry must have id");
            if (!item.has("name")) throw new ValidationException("mpims.json entry must have name");
            if (!item.has("created")) throw new ValidationException("mpims.json entry must have created");
            if (!item.has("members") || !item.get("members").isArray()) throw new ValidationException("mpims.json entry must have members array");
            String creator = item.has("creator") ? item.get("creator").asText() : null;
            if (creator != null && !creator.isBlank()) {
                boolean creatorInMembers = false;
                for (JsonNode m : item.get("members")) if (creator.equals(m.asText())) { creatorInMembers = true; break; }
                if (!creatorInMembers) throw new ValidationException("mpims.json entry creator must be in members");
            }
            if (!item.has("topic")) throw new ValidationException("mpims.json entry must have topic");
            if (!item.has("purpose")) throw new ValidationException("mpims.json entry must have purpose");
        }
    }

    private void validateDateFileStructure(byte[] jsonBytes) throws IOException {
        JsonNode root = objectMapper.readTree(jsonBytes);
        if (root == null) throw new ValidationException("Invalid JSON: null");
        if (!root.isArray()) throw new ValidationException("Date file must be a root array of messages");
        double lastTs = 0;
        for (int i = 0; i < root.size(); i++) {
            JsonNode msg = root.get(i);
            if (msg.has("ts")) {
                try {
                    double ts = Double.parseDouble(msg.get("ts").asText());
                    if (ts < lastTs) throw new ValidationException("Message ordering violation at index " + i);
                    lastTs = ts;
                } catch (NumberFormatException e) {
                    throw new ValidationException("Invalid ts at index " + i);
                }
            }
            if (msg.has("blocks") && msg.get("blocks").isArray()) {
                for (JsonNode block : msg.get("blocks")) {
                    if (!block.has("type")) throw new ValidationException("Block must have type");
                }
            }
        }
    }
}
