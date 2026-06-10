# Slack Export JSON vs API-Fetched Data

Comparison based on **official Slack export** samples (pepperwood export ZIP) vs **Slack Web API** responses and our current transformation.

---

## 1. Date file root structure

| | Real Slack export | Our current output | API (conversations.history) |
|--|-------------------|--------------------|-----------------------------|
| **Shape** | **Root array** `[{ msg1 }, { msg2 }, ...]` | Object `{ "messages": [ ... ] }` | N/A (API returns `{ "messages": [...] }`) |

**Fix:** Date files should be a **root-level array** of messages, not `{ "messages": [...] }`.

---

## 2. Message top-level keys

**Present in real Slack export:**

- `subtype`, `user`, `text`, `inviter`, `type`, `ts`
- `client_msg_id`, `team`, **`user_team`**, **`source_team`**, **`user_profile`** (full object)
- `blocks`, `reactions`, `edited`, `attachments`
- `thread_ts`, **`replies`** (array of `{ "user", "ts" }`)
- For thread parents: `hidden`, `parent_user_id`, `reply_count`, `reply_users_count`, `latest_reply`, `reply_users`, `is_locked`, `subscribed`, `last_read`

So the **real** export **keeps** `user_profile`, `user_team`, `source_team` (unlike the earlier “strict 14-field” schema).

**Our current output:** Only 14 keys; we drop `user_profile`, `user_team`, `source_team`, `subtype`, `inviter`, and thread metadata.

---

## 3. Blocks

**Real Slack export:**

- Block types: `rich_text`, `rich_text_section`, `rich_text_preformatted`, `text`, `emoji`, **`link`**, **`color`**
- **`text`** elements can have **`style`**: `{ "bold", "italic", "strike", "underline", "code" }`
- **`emoji`** can have **`unicode`**: e.g. `"unicode": "1f44b"`
- **`rich_text_preformatted`** can have **`border`**: e.g. `"border": 0`

**Our current output:** We allow the same block types but **do not copy `style`, `unicode`, `border`** from elements, so we lose formatting and metadata.

---

## 4. Attachments

**Real Slack export keeps full attachment objects:**

- `from_url`, `ts`, `author_id`, `channel_id`, `channel_team`, `is_msg_unfurl`
- **`message_blocks`** (nested message/block structure)
- `id`, `original_url`, **`fallback`**, `text`, **`author_name`**, **`author_link`**, **`author_icon`**, `author_subname`, `mrkdwn_in`, `footer`

So **real** export **does include** `message_blocks`, `from_url`, `author_icon`, `fallback`.

**Our current output:** We flatten/drop `message_blocks`, `from_url`, `author_icon`, `fallback` and only keep a subset of fields.

---

## 5. Reactions

**Real Slack export:** `{ "name", "users", "count" }` — same as API and our output.

---

## 6. Replies

**Real Slack export:**  
`"replies": [ { "user": "U01Q6BUB4NQ", "ts": "1769609909.431999" } ]`  
Each item has **both** `user` and `ts`.

**Our current output:** We only set `ts` in each reply ref; we omit `user`.

---

## 7. Summary

| Aspect | Real Slack export | Our current transform |
|--------|--------------------|------------------------|
| Date file root | Array `[]` | Object `{ "messages": [] }` |
| user_profile, user_team, source_team | **Included** | Stripped |
| subtype, inviter, thread metadata | **Included** | Omitted |
| Attachments | Full (incl. message_blocks, author_icon, fallback) | Flattened / reduced |
| Block elements | style, unicode, color, border | Missing |
| replies[] | `{ user, ts }` | `{ ts }` only |

To match the **exact** JSON from Slack’s own export, the transformation layer should:

1. Write each date file as a **root array** of messages.
2. **Preserve** `user_profile`, `user_team`, `source_team`, `subtype`, `inviter`, and thread-related fields.
3. **Preserve** full attachments (including `message_blocks`, `from_url`, `author_icon`, `fallback`).
4. **Preserve** in blocks: `style` on text, `unicode` on emoji, `color` elements, `border` on rich_text_preformatted.
5. Set **replies** to `{ "user", "ts" }` per reply (using reply message `user` when available).

---

## 8. File and GIF messages (reference format)

Export output is aligned with Slack's own export so that:

- **File messages** (e.g. `cfqamsg-files-pub-channel/YYYY-MM-DD.json`): Each message keeps a full **`files[]`** array. Each file object includes `id`, `created`, `timestamp`, `name`, `title`, `mimetype`, `filetype`, `pretty_type`, `user`, `user_team`, `editable`, `size`, `mode`, `url_private`, `url_private_download`, `permalink`, `permalink_public`, `edit_link`, etc. File messages are enriched via `files.info` when scope allows; otherwise the snippet from `conversations.history` is kept.

- **GIF / image messages** (e.g. `cfqamsg-gifs-pub-channel/YYYY-MM-DD.json`): Messages keep full **`blocks[]`** (e.g. `type: "image"` with `image_url`, `alt_text`, `title`, `image_width`, `image_height`, `image_bytes`, `is_animated`, `fallback`; `type: "context"` with elements), **`bot_profile`** (id, app_id, name, icons, team_id), and full **`attachments`** when present (e.g. link unfurls). No stripping of these fields; output matches the reference JSON shape.
