package com.cloudfuze.slackexport.service;

import com.cloudfuze.slackexport.validation.ExportValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Single ZIP: root dms.json + mpims.json; folders = DM channel ID or Group name; date files only inside folders.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    private final ObjectMapper objectMapper;
    private final ExportValidator exportValidator;
    private final ExportMessageNormalizer exportMessageNormalizer;

    private static final DateTimeFormatter DATE_FILE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final String DMS_JSON = "dms.json";
    private static final String MPIMS_JSON = "mpims.json";
    private static final Pattern UNSAFE_FOLDER_CHARS = Pattern.compile("[\\\\/:*?\"<>|]+");

    /**
     * Build single ZIP: root dms.json, root mpims.json; folders only for channels with messages; date files only when non-empty.
     * Only channels with at least one message in date range get a folder and an entry in dms.json/mpims.json. No empty folders.
     * Messages preserve full Slack API structure (no field pruning).
     * @param exportAsDmChannelIds when non-null, channel IDs in this set are written to dms.json (one-on-one) and use channelId as folder name, regardless of Slack's is_im/is_mpim
     */
    public byte[] buildZip(List<ChannelExportData> channels, String authUserId, String fromDate, String toDate, Set<String> exportAsDmChannelIds) throws IOException {
        return buildZip(channels, authUserId, fromDate, toDate, exportAsDmChannelIds, null);
    }

    /**
     * Same as buildZip but optionally adds export-summary.json when skippedChannels is non-empty.
     * export-summary.json lists succeeded and skipped channels with reasons.
     */
    public byte[] buildZip(List<ChannelExportData> channels, String authUserId, String fromDate, String toDate, Set<String> exportAsDmChannelIds, List<Map<String, String>> skippedChannels) throws IOException {
        if (channels == null || channels.isEmpty()) {
            throw new IllegalArgumentException("No channels to export: fetch must return at least one channel");
        }
        if (fromDate == null || fromDate.isBlank() || toDate == null || toDate.isBlank()) {
            throw new IllegalArgumentException("fromDate and toDate are required");
        }
        LocalDate from = LocalDate.parse(fromDate.trim());
        LocalDate to = LocalDate.parse(toDate.trim());
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("toDate must be on or after fromDate");
        }
        int number_of_days = (int) java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1;

        // Avoid duplicate channel IDs (e.g. from merge) and duplicate ZIP paths (same folder name for different MPIMs)
        List<ChannelExportData> dedupedChannels = deduplicateByChannelId(channels);
        Set<String> usedFolderNames = new HashSet<>();

        var writer = objectMapper.writerWithDefaultPrettyPrinter();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ArrayNode dmsRoot = objectMapper.createArrayNode();
        ArrayNode mpimsRoot = objectMapper.createArrayNode();
        int dateFilesAdded = 0;

        try (ZipOutputStream zos = new ZipOutputStream(baos)) {

            for (ChannelExportData ch : dedupedChannels) {
                JsonNode channelInfo = ch.getChannelInfo();
                String channelId = ch.getChannelId();
                // Honor user's form choice: if they submitted this channel as one-on-one DM, export as DM
                boolean isDm = (exportAsDmChannelIds != null && exportAsDmChannelIds.contains(channelId)) || ch.isDm();

                List<JsonNode> messagesList = ch.getMessages() != null ? ch.getMessages() : Collections.emptyList();
                List<JsonNode> normalized = new ArrayList<>();
                for (JsonNode m : messagesList) {
                    JsonNode n = exportMessageNormalizer.normalizeMessage(m);
                    normalized.add(n);
                }
                sortMessagesByTs(normalized);
                attachRepliesToParents(normalized);

                Map<LocalDate, List<JsonNode>> messagesByDate = new LinkedHashMap<>();
                for (int i = 0; i < number_of_days; i++) {
                    LocalDate date = from.plusDays(i);
                    List<JsonNode> forDate = new ArrayList<>();
                    for (JsonNode msg : normalized) {
                        if (!msg.has("ts")) continue;
                        if (tsToLocalDate(msg.get("ts").asText()).equals(date)) forDate.add(msg);
                    }
                    if (!forDate.isEmpty()) messagesByDate.put(date, forDate);
                }

                // Include channel even when it has no messages (add to dms/mpims and one empty date file)
                if (messagesByDate.isEmpty()) {
                    messagesByDate.put(from, new ArrayList<>());
                }

                if (isDm) {
                    ObjectNode entry = objectMapper.createObjectNode();
                    entry.put("id", channelId);
                    entry.put("created", channelInfo != null && channelInfo.has("created") ? channelInfo.get("created").asLong() : 0L);
                    ArrayNode membersArr = objectMapper.createArrayNode();
                    if (ch.getMembers() != null && !ch.getMembers().isEmpty()) {
                        for (String uid : ch.getMembers()) membersArr.add(uid);
                    } else {
                        if (authUserId != null && !authUserId.isBlank()) membersArr.add(authUserId);
                        if (channelInfo != null && channelInfo.has("user")) membersArr.add(channelInfo.get("user").asText());
                    }
                    entry.set("members", membersArr);
                    dmsRoot.add(entry);
                } else {
                    String rawName = ch.getGroupName() != null ? ch.getGroupName() : channelId;
                    String nameFromApi = normalizeMpimName(rawName);
                    ObjectNode entry = buildMpimEntry(channelId, nameFromApi, channelInfo, ch.getMembers());
                    mpimsRoot.add(entry);
                }

                String folderName = isDm ? channelId : ensureUniqueFolderName(
                        sanitizeFolderName(normalizeMpimName(ch.getGroupName() != null ? ch.getGroupName() : channelId)),
                        channelId,
                        usedFolderNames);
                String folderPrefix = folderName + "/";

                for (Map.Entry<LocalDate, List<JsonNode>> e : messagesByDate.entrySet()) {
                    String dateKey = e.getKey().format(DATE_FILE_FORMAT);
                    ArrayNode rootArray = objectMapper.createArrayNode();
                    for (JsonNode m : e.getValue()) rootArray.add(m);
                    byte[] content = writer.writeValueAsBytes(rootArray);
                    String path = folderPrefix + dateKey + ".json";
                    putZipEntry(zos, path, content);
                    dateFilesAdded++;
                }
            }

            if (dateFilesAdded == 0) {
                throw new IllegalStateException("No message data: zero date files. At least one channel must have been exported.");
            }

            byte[] dmsBytes = writer.writeValueAsBytes(dmsRoot);
            byte[] mpimsBytes = writer.writeValueAsBytes(mpimsRoot);
            putZipEntry(zos, DMS_JSON, dmsBytes);
            putZipEntry(zos, MPIMS_JSON, mpimsBytes);

            if (skippedChannels != null && !skippedChannels.isEmpty()) {
                ObjectNode summary = objectMapper.createObjectNode();
                ArrayNode succeeded = objectMapper.createArrayNode();
                for (ChannelExportData ch : dedupedChannels) {
                    if (ch.getChannelId() != null) succeeded.add(ch.getChannelId());
                }
                summary.set("succeeded", succeeded);
                ArrayNode skipped = objectMapper.createArrayNode();
                for (Map<String, String> s : skippedChannels) {
                    ObjectNode entry = objectMapper.createObjectNode();
                    entry.put("channelId", s.getOrDefault("channelId", ""));
                    entry.put("reason", s.getOrDefault("reason", ""));
                    skipped.add(entry);
                }
                summary.set("skipped", skipped);
                putZipEntry(zos, "export-summary.json", writer.writeValueAsBytes(summary));
                log.info("ZIP: date files={}, dms={}, mpims={}, skipped={}", dateFilesAdded, dmsRoot.size(), mpimsRoot.size(), skippedChannels.size());
            } else {
                log.info("ZIP: date files={}, dms={}, mpims={}", dateFilesAdded, dmsRoot.size(), mpimsRoot.size());
            }
        }

        byte[] zipBytes = baos.toByteArray();
        if (zipBytes.length == 0) {
            throw new IllegalStateException("ZIP is empty after build.");
        }
        exportValidator.validateExportZip(channels, zipBytes, fromDate, toDate);
        return zipBytes;
    }

    /** MPIM entry. When overrideMembers is non-null and non-empty, use it; else use channelInfo (creator + members). */
    private ObjectNode buildMpimEntry(String channelId, String nameFromApi, JsonNode channelInfo, List<String> overrideMembers) {
        ObjectNode entry = objectMapper.createObjectNode();
        entry.put("id", channelId);
        entry.put("name", nameFromApi != null && !nameFromApi.isBlank() ? nameFromApi : channelId);
        entry.put("created", channelInfo != null && channelInfo.has("created") ? channelInfo.get("created").asLong() : 0L);
        String creator = null;
        if (channelInfo != null && channelInfo.has("creator")) creator = channelInfo.get("creator").asText();
        entry.put("creator", creator != null ? creator : "");
        entry.put("is_archived", channelInfo != null && channelInfo.has("is_archived") && channelInfo.get("is_archived").asBoolean());

        List<String> members = new ArrayList<>();
        if (overrideMembers != null && !overrideMembers.isEmpty()) {
            members.addAll(overrideMembers);
        } else {
            if (creator != null && !creator.isBlank()) members.add(creator);
            if (channelInfo != null && channelInfo.has("members") && channelInfo.get("members").isArray()) {
                for (JsonNode m : channelInfo.get("members")) {
                    String uid = m.asText();
                    if (!members.contains(uid)) members.add(uid);
                }
            }
        }
        ArrayNode membersArr = objectMapper.createArrayNode();
        members.forEach(membersArr::add);
        entry.set("members", membersArr);

        if (channelInfo != null && channelInfo.has("topic")) {
            entry.set("topic", channelInfo.get("topic"));
        } else {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("value", "");
            empty.put("creator", "");
            empty.put("last_set", 0);
            entry.set("topic", empty);
        }
        if (channelInfo != null && channelInfo.has("purpose")) {
            entry.set("purpose", channelInfo.get("purpose"));
        } else {
            ObjectNode empty = objectMapper.createObjectNode();
            empty.put("value", "");
            empty.put("creator", "");
            empty.put("last_set", 0);
            entry.set("purpose", empty);
        }
        return entry;
    }

    /** Keep first occurrence of each channelId to avoid duplicate ZIP entries. */
    private static List<ChannelExportData> deduplicateByChannelId(List<ChannelExportData> channels) {
        if (channels == null || channels.isEmpty()) return channels;
        LinkedHashMap<String, ChannelExportData> byId = new LinkedHashMap<>();
        for (ChannelExportData ch : channels) {
            String id = ch.getChannelId();
            if (id != null && !byId.containsKey(id)) {
                byId.put(id, ch);
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Return a folder name unique within this ZIP; if baseName is already used, append channelId. */
    private static String ensureUniqueFolderName(String baseName, String channelId, Set<String> usedFolderNames) {
        String name = baseName;
        if (channelId != null && !channelId.isBlank() && usedFolderNames.contains(name)) {
            name = name + "-" + channelId;
        }
        usedFolderNames.add(name);
        return name;
    }

    /** Group DM name: end with "-1", not "mpim". Replace trailing "mpim" with "-1"; otherwise append "-1" if missing. */
    private static String normalizeMpimName(String name) {
        if (name == null || name.isBlank()) return "group-dm-1";
        String s = name.trim();
        if (s.endsWith("-1")) return s;
        if (s.toLowerCase().endsWith("mpim")) return s.substring(0, s.length() - 4) + "-1";
        return s + "-1";
    }

    private static String sanitizeFolderName(String name) {
        if (name == null || name.isBlank()) return "group-dm";
        String s = UNSAFE_FOLDER_CHARS.matcher(name.trim()).replaceAll("-");
        return s.replaceAll("\\s+", "--");
    }

    private void putZipEntry(ZipOutputStream zos, String name, byte[] content) throws IOException {
        ZipEntry e = new ZipEntry(name);
        zos.putNextEntry(e);
        zos.write(content);
        zos.closeEntry();
    }

    private static LocalDate tsToLocalDate(String ts) {
        try {
            double sec = Double.parseDouble(ts);
            return Instant.ofEpochSecond((long) sec).atOffset(ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            return LocalDate.EPOCH;
        }
    }

    private void sortMessagesByTs(List<JsonNode> messages) {
        messages.sort(Comparator.comparing(m -> m.has("ts") ? m.get("ts").asText() : "0",
                Comparator.comparingDouble(ExportService::parseTs)));
    }

    private static double parseTs(String ts) {
        try {
            return Double.parseDouble(ts);
        } catch (Exception e) {
            return 0;
        }
    }

    private void attachRepliesToParents(List<JsonNode> messages) {
        for (JsonNode msg : messages) {
            if (!msg.has("ts") || !msg.has("thread_ts")) continue;
            String ts = msg.get("ts").asText();
            String threadTs = msg.get("thread_ts").asText();
            if (!ts.equals(threadTs)) continue;
            if (!(msg instanceof ObjectNode parent)) continue;
            ArrayNode replies = objectMapper.createArrayNode();
            for (JsonNode other : messages) {
                if (!other.has("ts") || !other.has("thread_ts")) continue;
                if (!other.get("thread_ts").asText().equals(ts) || other.get("ts").asText().equals(ts)) continue;
                ObjectNode ref = objectMapper.createObjectNode();
                ref.put("ts", other.get("ts").asText());
                ref.put("user", other.has("user") ? other.get("user").asText() : "");
                replies.add(ref);
            }
            if (replies.size() > 0) parent.set("replies", replies);
        }
    }

}
