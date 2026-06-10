package com.cloudfuze.slackexport.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

/**
 * Prepares Slack API messages for export. Matches real Slack export format.
 * <p>
 * Full API message is preserved (deep copy). No field stripping. Output matches
 * official Slack export so that:
 * <ul>
 *   <li><b>File messages</b> keep full files[] (id, created, timestamp, name, title,
 *       mimetype, filetype, pretty_type, user, size, mode, url_private, url_private_download,
 *       permalink, etc.).</li>
 *   <li><b>GIF / image messages</b> keep blocks[] with type "image" and "context"
 *       (image_url, alt_text, title, image_width, image_height, image_bytes, is_animated,
 *       fallback), bot_profile (id, app_id, name, icons, team_id), and full attachments
 *       when present.</li>
 *   <li>user_profile, user_team, source_team, attachments (incl. message_blocks) are kept.</li>
 * </ul>
 * replies[] is filled by ExportService.attachRepliesToParents with { user, ts } per reply.
 */
@Component
public class ExportMessageNormalizer {

    /**
     * Return a deep copy of the message. All fields preserved so file/gif messages
     * match reference Slack export (e.g. cfqamsg-files-pub-channel, cfqamsg-gifs-pub-channel).
     */
    public JsonNode normalizeMessage(JsonNode message) {
        if (message == null) return null;
        if (!message.isObject()) return message;
        return message.deepCopy();
    }
}
