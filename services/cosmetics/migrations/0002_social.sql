-- Origin social: friends, chat, parties, players online. Contract: docs/SOCIAL.md.
-- Every uuid is a Minecraft UUID, 32 lowercase hex, no dashes. Every time is
-- ms since epoch. Code: src/social.js + src/party.js.

-- Sign-ins. The client gets a random token once; only its SHA-256 is kept,
-- so a leaked database can't be used to act as anyone.
CREATE TABLE IF NOT EXISTS sessions (
  token_hash TEXT PRIMARY KEY,        -- hex SHA-256 of the bearer token
  uuid       TEXT NOT NULL,
  client     TEXT NOT NULL,           -- 'game' | 'launcher' (who signed in)
  created    INTEGER NOT NULL,
  expires    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS sessions_expires ON sessions (expires);

-- Everyone who ever signed in to Origin social, with their current name.
-- "Not on Origin" = no row here.
CREATE TABLE IF NOT EXISTS names (
  uuid       TEXT PRIMARY KEY,
  name       TEXT NOT NULL,
  name_lower TEXT NOT NULL,           -- fallback lookup when Mojang's API is down
  updated    INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS names_lower ON names (name_lower);

-- Where each account is, per client (launcher and game report separately).
-- Written at most every 30 s per (uuid, client) unless something changed.
CREATE TABLE IF NOT EXISTS presence (
  uuid     TEXT NOT NULL,
  client   TEXT NOT NULL,             -- 'game' | 'launcher'
  activity TEXT,                      -- 'menu' | 'singleplayer' | 'server'
  server   TEXT,                      -- normalised host[:port], only for 'server'
  version  TEXT,                      -- Minecraft version, e.g. 1.21.1
  at       INTEGER NOT NULL,          -- last write (= last seen, within 30 s)
  PRIMARY KEY (uuid, client)
);
CREATE INDEX IF NOT EXISTS presence_at ON presence (at);   -- players-online count

CREATE TABLE IF NOT EXISTS settings (
  uuid           TEXT PRIMARY KEY,
  appear_offline INTEGER NOT NULL DEFAULT 0,
  offline_since  INTEGER,             -- when appear-offline went on (frozen "last seen")
  at             INTEGER NOT NULL
);

-- Friendships are stored in BOTH directions (a->b and b->a), so "my friends"
-- is one index range.
CREATE TABLE IF NOT EXISTS friends (
  a  TEXT NOT NULL,
  b  TEXT NOT NULL,
  at INTEGER NOT NULL,
  PRIMARY KEY (a, b)
);

CREATE TABLE IF NOT EXISTS friend_requests (
  from_uuid TEXT NOT NULL,
  to_uuid   TEXT NOT NULL,
  at        INTEGER NOT NULL,
  PRIMARY KEY (from_uuid, to_uuid)
);
CREATE INDEX IF NOT EXISTS friend_requests_to ON friend_requests (to_uuid);

CREATE TABLE IF NOT EXISTS blocks (
  blocker TEXT NOT NULL,
  blocked TEXT NOT NULL,
  at      INTEGER NOT NULL,
  PRIMARY KEY (blocker, blocked)
);
CREATE INDEX IF NOT EXISTS blocks_blocked ON blocks (blocked);

-- DMs and party chat share ONE table so they share one id sequence: the
-- /v1/sync cursor is simply the highest id a client has seen. AUTOINCREMENT
-- never reuses an id (even after the 30-day prune), and D1 runs writes one at
-- a time, so id order is commit order: nothing skipped, nothing repeated.
CREATE TABLE IF NOT EXISTS chat (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  party_id  TEXT,                     -- set for party chat, NULL for a DM
  from_uuid TEXT NOT NULL,
  to_uuid   TEXT,                     -- set for a DM, NULL for party chat
  text      TEXT NOT NULL,
  at        INTEGER NOT NULL          -- never goes backwards (see sendChat)
);
CREATE INDEX IF NOT EXISTS chat_to    ON chat (to_uuid, id);
CREATE INDEX IF NOT EXISTS chat_from  ON chat (from_uuid, id);
CREATE INDEX IF NOT EXISTS chat_party ON chat (party_id, id);
CREATE INDEX IF NOT EXISTS chat_at    ON chat (at);

CREATE TABLE IF NOT EXISTS parties (
  id      TEXT PRIMARY KEY,           -- random hex
  leader  TEXT NOT NULL,
  created INTEGER NOT NULL
);

-- uuid is the key: one party per player.
CREATE TABLE IF NOT EXISTS party_members (
  uuid         TEXT PRIMARY KEY,
  party_id     TEXT NOT NULL,
  joined       INTEGER NOT NULL,      -- longest member = smallest joined
  chat_from    INTEGER NOT NULL,      -- chat id at join: older party chat stays hidden
  state        TEXT,                  -- JSON partyState { server, dim, x, y, z, health, maxHealth }
  state_server TEXT,                  -- copy of state.server, compared on read
  state_at     INTEGER
);
CREATE INDEX IF NOT EXISTS party_members_party ON party_members (party_id, joined);

CREATE TABLE IF NOT EXISTS party_invites (
  party_id  TEXT NOT NULL,
  to_uuid   TEXT NOT NULL,
  from_uuid TEXT NOT NULL,
  at        INTEGER NOT NULL,
  PRIMARY KEY (party_id, to_uuid)
);
CREATE INDEX IF NOT EXISTS party_invites_to ON party_invites (to_uuid);
CREATE INDEX IF NOT EXISTS party_invites_at ON party_invites (at);

CREATE TABLE IF NOT EXISTS pings (
  id        INTEGER PRIMARY KEY AUTOINCREMENT,
  party_id  TEXT NOT NULL,
  from_uuid TEXT NOT NULL,
  server    TEXT NOT NULL,
  dim       TEXT NOT NULL,
  x REAL NOT NULL, y REAL NOT NULL, z REAL NOT NULL,
  label     TEXT,
  at        INTEGER NOT NULL,
  expires   INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS pings_party   ON pings (party_id, expires);
CREATE INDEX IF NOT EXISTS pings_expires ON pings (expires);

-- The leader's latest "join me" per party (members see it for 2 minutes).
CREATE TABLE IF NOT EXISTS warps (
  party_id  TEXT PRIMARY KEY,
  from_uuid TEXT NOT NULL,
  server    TEXT NOT NULL,
  version   TEXT NOT NULL,
  at        INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS warps_at ON warps (at);

-- 1-per-second limits: the last time each account did each kind of action.
CREATE TABLE IF NOT EXISTS rate (
  uuid TEXT NOT NULL,
  kind TEXT NOT NULL,                 -- 'dm' | 'party' | 'ping' | 'invite' | 'friend'
  at   INTEGER NOT NULL,
  PRIMARY KEY (uuid, kind)
);
CREATE INDEX IF NOT EXISTS rate_at ON rate (at);
