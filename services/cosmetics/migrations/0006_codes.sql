-- Free store codes (Will, 2026-10-18; src/codes.js). Each works once: used_*
-- are filled in by the one redeem that claims it.
CREATE TABLE IF NOT EXISTS codes (
  code      TEXT PRIMARY KEY,   -- bare, upper case: shown as ORIGIN-XXXX-XXXX-XXXX
  kind      TEXT NOT NULL,      -- 'bag' (whatever is in the bag) | 'plus' (Origin+ for good)
  made_at   INTEGER NOT NULL,   -- ms
  used_by   TEXT,               -- Minecraft UUID it went to
  used_name TEXT,               -- the name typed, for support lookups
  used_at   INTEGER,            -- ms; NULL = not used yet
  items     TEXT                -- comma-separated items it gave (bag codes)
);
