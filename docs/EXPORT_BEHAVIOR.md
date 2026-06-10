# Export Behavior: All Channels & DMs with Rate Limit Handling

## Goal

Download **all channels and DMs** with **all messages**, handle **rate limits** when they occur, and generate a **single ZIP file** containing everything.

---

## How It Works

### 1. Channel Processing (Sequential)

- **DMs & Group DMs**: Channels are processed **one at a time** (no parallel burst).
- **Private Channels**: Same sequential processing.
- **Delay between channels**: 1.2 seconds by default (`export.slack-channel-delay-ms`) so Slack rate limits can recover.

### 2. Rate Limit Handling

| Layer | Behavior |
|-------|----------|
| **Per API call** | Up to 100 retries with backoff when Slack returns 429. Waits `Retry-After` (usually 10s) between retries. |
| **Per message (reactions/files)** | If rate limit exhausts retries: include message **without** reactions/file details instead of failing. Channel is never skipped. |
| **Per channel** | If whole channel fails with rate limit: retry the channel up to 2 times with 30s backoff. |
| **Enrichment throttle** | 800ms delay between each message's reactions.get/files.info to reduce rate limit hits. |

### 3. Final Output

- **Single ZIP file** containing:
  - `dms.json` – one-on-one DMs
  - `mpims.json` – group DMs
  - `groups.json` – private channels (when exporting private channels)
  - `users.json` – user profiles
  - Date-based message files (e.g. `2024-01-15.json`)
  - `export-summary.json` – lists succeeded/skipped channels (when any are skipped)

---

## Configuration

```yaml
# application.yml (or env vars)

slack:
  rate-limit:
    max-retries: 100                    # SLACK_RATE_LIMIT_MAX_RETRIES
    enrichment-delay-ms: 800           # SLACK_ENRICHMENT_DELAY_MS

export:
  slack-channel-delay-ms: 1200         # EXPORT_SLACK_CHANNEL_DELAY_MS
```

---

## When a Channel Is Skipped

A channel is skipped **only** when:

- **Both** admin and user tokens fail (e.g. `not_in_channel`, `channel_not_found`)
- Token user is not a participant in that DM/channel

Rate limits do **not** cause channels to be skipped; they are retried or messages are included without reactions.

---

## Export Types

| Type | Description |
|------|-------------|
| **Manual DM** | Selected one-on-one and group DM channel IDs |
| **Export All DMs** | All DMs and group DMs for configured tokens |
| **Manual Private Channels** | Selected private channel IDs |
| **Export All Private Channels** | All private channels the token can access |
