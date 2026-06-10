# Slack DM/MPIM Export

Spring Boot backend that **fetches real Slack DM/MPIM data** via Slack APIs and exports it to a ZIP of JSON files matching Slack’s export format. No mock data; only real API responses are used.

## Project layout

| Folder      | Contents                 |
|-------------|--------------------------|
| `backend/`  | Java, Spring Boot, Maven |
| `frontend/` | React, Vite, npm         |

## Tech stack

- **Backend:** Java 17+, Spring Boot 3.2, WebFlux (WebClient), Jackson, ZipOutputStream
- **Frontend:** React, Vite

## Build and run

**Backend** (API at http://localhost:8080):

```bash
cd backend
mvn spring-boot:run
```

**Frontend** (UI at http://localhost:5173):

```bash
cd frontend
npm install
npm run dev
```

## REST API

**POST** `/api/export/slack-dm-mpim`

**Request body (JSON):**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `slackAccessToken` | string | Yes | Slack User or Bot token (e.g. `xoxb-...` or `xoxp-...`) |
| `channelIds` | array of string | Yes | DM or MPIM channel IDs (e.g. `D123...`, `G123...`) |
| `startTs` | string | No | Optional start timestamp (e.g. `1234567890.000000`) |
| `endTs` | string | No | Optional end timestamp |
| `messageLimitPerChannel` | number | No | Optional max messages per channel |

**Example:**

```json
{
  "slackAccessToken": "xoxb-your-token",
  "channelIds": ["D01234ABCDE", "G01234ABCDE"],
  "startTs": "1609459200.000000",
  "endTs": "1640995200.000000",
  "messageLimitPerChannel": 500
}
```

**Success:** `200 OK` with `Content-Type: application/zip` and attachment `slack-dm-mpim-export.zip`.

**Errors:**

- `502 Bad Gateway` – Slack API error (invalid token, channel not found, etc.); exact Slack response is logged.
- `422 Unprocessable Entity` – Validation failed (e.g. extra date files, invalid structure).
- `500 Internal Server Error` – Export or build failure.

If any Slack API call fails, the export is aborted and no partial ZIP is returned.

### Export All DMs (when the UI times out)

**POST** `/api/export/all-dms-dual` — uses tokens from backend config (`slack.tokens.user` / `slack.tokens.admin`). No token in the body.

If the browser shows "Export timed out" but the backend is still running, you can get the ZIP **without refreshing the app** by calling the API directly with a long timeout:

**Full history (no date range):**

```bash
curl -X POST "http://localhost:8081/api/export/all-dms-dual" \
  -H "Content-Type: application/json" \
  -H "Accept: application/zip" \
  -d "{\"exportMode\":\"FULL\",\"mode\":\"ALL_HISTORY\"}" \
  --max-time 3600 \
  -o all-dms-export.zip
```

`--max-time 3600` = wait up to 60 minutes. Adjust port if you use a different `SERVER_PORT`.

**Specific date range:**

```bash
curl -X POST "http://localhost:8081/api/export/all-dms-dual" \
  -H "Content-Type: application/json" \
  -H "Accept: application/zip" \
  -d "{\"exportMode\":\"CUSTOM\",\"mode\":\"DATE_RANGE\",\"fromDate\":\"2025-01-01\",\"toDate\":\"2025-01-31\"}" \
  --max-time 3600 \
  -o all-dms-export.zip
```

**Postman:** Same URL and body; set request timeout to 60 minutes (or higher) in Postman settings so the request does not abort.

## Required Slack OAuth scopes (fix "missing_scope")

If you see **missing_scope** from Slack, add these scopes to your app and reinstall.

### Scopes to add in the Slack app

In [Slack API → Your App → OAuth & Permissions](https://api.slack.com/apps) → **Scopes** → **Bot Token Scopes** (or **User Token Scopes** if using a user token), add:

| Scope | Used by | Purpose |
|-------|--------|--------|
| `im:history` | conversations.history, conversations.replies | Read DM message history |
| `mpim:history` | conversations.history, conversations.replies | Read group DM (MPIM) history |
| `im:read` | conversations.info | View DM channel info |
| `mpim:read` | conversations.info | View MPIM channel info |
| `users:read` | users.info | Resolve user info |
| `files:read` | files.info | Read file metadata (messages with files) |
| `reactions:read` | reactions.get | Read emoji reactions on messages |

**Copy-paste list (add all 7):**

```
im:history
mpim:history
im:read
mpim:read
users:read
files:read
reactions:read
```

**After adding:** Click **Reinstall to Workspace** (or **Install to Workspace**), then use the new token in your config (`slack.tokens.user` / `slack.tokens.admin`) or in the API request. Without reinstall, the token does not get the new scopes.

### Still getting missing_scope after adding scopes?

1. **Reinstall the app**  
   Adding scopes does **not** update your existing token. In **OAuth & Permissions**, click **Reinstall to Workspace** (or **Install to Workspace**). Until you do this, the token never gets the new scopes.

2. **Use the new token**  
   After reinstalling, Slack shows a **new** token. Copy that token and set it in your config (e.g. `SLACK_USER_TOKEN` / `SLACK_ADMIN_TOKEN`) or in the API request. The old token will not have the new scopes.

3. **Bot vs User token**  
   If you use a **Bot** token (`xoxb-...`), add the 7 scopes under **Bot Token Scopes**. If you use a **User** token (`xoxp-...`), add them under **User Token Scopes**. The token in your config must match: User token in config → scopes must be under User Token Scopes.

4. **Same app, token copied after reinstall**  
   The token in `application.yml` or `SLACK_USER_TOKEN` must be from the **same** Slack app where you added the scopes. Copy the **User OAuth Token** (or Bot token) from **OAuth & Permissions** **after** you click Reinstall to Workspace—the token value can change after reinstall.

5. **Restart the backend**  
   After changing the token in config or environment, restart the app (`cd backend && mvn spring-boot:run`). The app only reads the token at startup.

6. **See exactly what Slack wants**  
   When you get `missing_scope`, look at the **terminal where the backend is running**. You should see a log line like:
   ```text
   Slack missing_scope: method=/conversations.info, required scope(s)=[im:read], full response={"ok":false,"error":"missing_scope","needed":"im:read"}
   ```
   That tells you which Slack API failed and which scope Slack says is missing. Add that scope under **User Token Scopes**, then **Reinstall to Workspace**, copy the new **User OAuth Token**, set it in config, and restart.

| If the failing method is        | Add this scope (under User Token Scopes) |
|---------------------------------|------------------------------------------|
| `/conversations.info`           | `im:read` and/or `mpim:read`             |
| `/conversations.history`        | `im:history` and/or `mpim:history`       |
| `/conversations.replies`        | `im:history` and/or `mpim:history`       |
| `/users.info`                   | `users:read`                             |
| `/files.info`                   | `files:read`                             |
| `/reactions.get`                | `reactions:read`                         |

## Slack APIs used

- `conversations.history` – channel messages (with pagination via `response_metadata.next_cursor`)
- `conversations.replies` – thread replies
- `conversations.info` – channel metadata (DM vs MPIM from `is_im` / `is_mpim`)
- `users.info` – user details (if needed)
- `files.info` – file metadata for attachments
- `reactions.get` – reactions per message

Rate limits: HTTP 429 is handled with retries using `Retry-After` and backoff.

## ZIP layout

- Root folders: `dms/`, `mpims/`
- Per DM channel: `dms/<channelId>/dms.json` (from `conversations.info`) and `YYYY-MM-DD.json` only for dates that have messages
- Per MPIM channel: `mpims/<channelId>/mpims.json` and `YYYY-MM-DD.json` only for dates that have messages  
- No nested folders; no extra date files; MPIM members are de-duplicated in metadata

## Normalization (API → Slack Export format)

Slack API responses are **normalized** to Slack downloadable export structure (not a 1:1 copy):

- **Messages:** All real data preserved. **Removed** (Slack Export omits): `user_profile`, `icons`, `display_name`, `avatar_hash`, `image_*`, `source_team`, `user_team`. All other fields kept (e.g. `type`, `ts`, `text`, `user`, `subtype`, `client_msg_id`, `edited`, `inviter`, `reactions`, `attachments`, `files`, `blocks`, `thread_ts`, `replies`).
- **Blocks:** Preserved exactly (rich_text, list_record, code, emoji, etc.). Unknown block types kept. No blocks↔text conversion.
- **Files:** All API fields preserved (filenames, special chars, list/views metadata, `initial_comment`, `permalink`, `url_private`). No binary fetch.
- **Attachments:** Preserved exactly (message_blocks, author, fallback).
- **Reactions:** Preserved exactly (`name`, `count`, `users[]`).
- **Threads:** Parent message gets `replies[]` with child `ts` only; replies stay in the same date file as the parent.
- **Subtypes:** All preserved (e.g. `channel_join`, `bot_message`, `message_changed`, `pinned_item`, `file_share`).
- No values are invented; only API-derivable data is included.

## Validation before response

Before returning the ZIP, the export is validated:

- JSON structure
- Block Kit compliance (block `type` present)
- Thread integrity
- No extra date files (only dates present in Slack data)
- Ordering by timestamp

If validation fails, an exception is thrown and the response is not returned.

## Configuration

`backend/src/main/resources/application.yml`:

- `slack.api.base-url` – Slack API base (default `https://slack.com/api`)
- `slack.rate-limit.*` – retry and backoff for 429
