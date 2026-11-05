-- Read markers and per-conversation history (docs/SOCIAL.md "Read markers",
-- "History"; code: src/social.js). ADDITIVE ONLY: one new table, two new
-- indexes, and a one-time fill of the new table. Nothing existing is changed or
-- dropped, so it is safe to apply to the live database while the old Worker is
-- still serving (it never reads chat_read).

-- Per account and conversation, the newest chat id the player has read there,
-- in the launcher or the game. The other client learns it through /v1/sync
-- (`readsSince` / `reads`), so a chat read in one stops counting as unread in
-- the other, and messages that arrived while you were offline count as unread.
CREATE TABLE IF NOT EXISTS chat_read (
  uuid  TEXT NOT NULL,                -- the reader
  peer  TEXT NOT NULL,                -- the friend's uuid (a DM conversation) or 'party'
  upto  INTEGER NOT NULL,             -- newest chat.id read in that conversation (only grows)
  at    INTEGER NOT NULL,             -- ms when it last moved; strictly rises per reader (the sync cursor)
  PRIMARY KEY (uuid, peer)
);
CREATE INDEX IF NOT EXISTS chat_read_at ON chat_read (uuid, at);

-- /v1/messages/history: one conversation's lines, newest first, by id.
CREATE INDEX IF NOT EXISTS chat_pair ON chat (to_uuid, from_uuid, id);

-- Everything received before this update counts as read: without it, the first
-- start of an updated launcher or game would flag up to 30 days of old DMs as
-- new. Only fills conversations with no marker yet, so running it twice (or after
-- the new Worker already wrote markers) changes nothing.
INSERT OR IGNORE INTO chat_read (uuid, peer, upto, at)
  SELECT to_uuid, from_uuid, MAX(id), CAST(strftime('%s', 'now') AS INTEGER) * 1000
  FROM chat WHERE party_id IS NULL AND to_uuid IS NOT NULL
  GROUP BY to_uuid, from_uuid;
