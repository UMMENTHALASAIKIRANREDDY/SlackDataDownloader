package com.cloudfuze.slackexport.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Admin and user tokens plus optional channel ID lists (input in backend config).
 * Supports: admin-only (userToken null), user-only, or multiple tokens (admin + user(s)).
 * Multiple user tokens: comma-separated in slack.tokens.users.
 */
@Component
@Getter
public class SlackTokenConfig {

    @Value("${slack.tokens.admin:}")
    private String adminToken;

    @Value("${slack.tokens.user:}")
    private String userToken;

    /** Comma-separated additional user tokens for multi-token fetch (deduplicates DMs/private channels). */
    @Value("${slack.tokens.users:}")
    private String userTokensConfig;

    @Value("${slack.export.dm-channel-ids:}")
    private String dmChannelIdsConfig;

    @Value("${slack.export.mpim-channel-ids:}")
    private String mpimChannelIdsConfig;

    /** Returns the user token. Null if not configured. */
    public String getToken() {
        return userToken != null && !userToken.isBlank() ? userToken.trim() : null;
    }

    /** Returns the admin token. Null if not configured. */
    public String getAdminToken() {
        return adminToken != null && !adminToken.isBlank() ? adminToken.trim() : null;
    }

    /**
     * Returns true if at least one token (admin or user) is configured.
     */
    public boolean hasAnyToken() {
        return getAdminToken() != null || getToken() != null || !getAllUserTokens().isEmpty();
    }

    /**
     * Returns all user tokens: primary user + additional from slack.tokens.users (comma-separated).
     * Deduplicated, preserves order.
     */
    public List<String> getAllUserTokens() {
        Set<String> seen = new LinkedHashSet<>();
        if (getToken() != null) seen.add(getToken());
        if (userTokensConfig != null && !userTokensConfig.isBlank()) {
            for (String t : userTokensConfig.split(",")) {
                String trimmed = t != null ? t.trim() : "";
                if (!trimmed.isEmpty() && !seen.contains(trimmed)) seen.add(trimmed);
            }
        }
        return List.copyOf(seen);
    }

    /**
     * Returns ordered list of tokens for multi-token fetch: admin first (if set), then user tokens.
     * Use for "Export All DMs" / "Export All Private Channels" when multiple tokens.
     * Deduplicates by channel ID (first token wins).
     */
    public List<String> getAllTokensForFetch() {
        List<String> tokens = new java.util.ArrayList<>();
        if (getAdminToken() != null) tokens.add(getAdminToken());
        for (String t : getAllUserTokens()) {
            if (t != null && !t.isBlank() && !tokens.contains(t)) tokens.add(t);
        }
        return tokens;
    }

    /** DM channel IDs from config (comma-separated). Empty if not set. */
    public List<String> getDmChannelIdsFromConfig() {
        if (dmChannelIdsConfig == null || dmChannelIdsConfig.isBlank()) return Collections.emptyList();
        return Arrays.stream(dmChannelIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }

    /** MPIM channel IDs from config (comma-separated). Empty if not set. */
    public List<String> getMpimChannelIdsFromConfig() {
        if (mpimChannelIdsConfig == null || mpimChannelIdsConfig.isBlank()) return Collections.emptyList();
        return Arrays.stream(mpimChannelIdsConfig.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
    }
}
