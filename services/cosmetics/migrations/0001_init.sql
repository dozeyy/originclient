-- Who owns which cosmetic, by permanent Minecraft UUID (32 hex, no dashes).
CREATE TABLE IF NOT EXISTS owned (
  uuid    TEXT NOT NULL,
  item    TEXT NOT NULL,      -- e.g. cape-orbit (ids: website/js/store.js ITEMS)
  payment TEXT,               -- Tebex payment id, for support
  at      INTEGER NOT NULL,   -- ms since epoch
  PRIMARY KEY (uuid, item)
);

-- What each player is wearing. NULL = nothing in that slot.
CREATE TABLE IF NOT EXISTS equipped (
  uuid TEXT PRIMARY KEY,
  cape TEXT,
  head TEXT,
  at   INTEGER NOT NULL
);

-- Mojang server-join checks already used for /v1/equip (each works once).
CREATE TABLE IF NOT EXISTS joins (
  server_id TEXT PRIMARY KEY,
  at        INTEGER NOT NULL
);

-- Small bookkeeping values (e.g. when Tebex next allows a queue check).
CREATE TABLE IF NOT EXISTS meta (
  k TEXT PRIMARY KEY,
  v TEXT
);
