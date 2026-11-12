-- 2026-11-11 (Will): the website shows how many people clicked its Download
-- button, live. One running total, plus who already counted today so one person
-- clicking ten times counts once (downloads.js).
CREATE TABLE counters (name TEXT PRIMARY KEY, n INTEGER NOT NULL DEFAULT 0);
INSERT INTO counters (name, n) VALUES ('downloads', 0);

-- who = SHA-256 of the day's random salt plus the clicker's IP address (IPv6 by
-- its /64), never the address itself. Salts and rows from earlier days are
-- deleted by the cron (downloads.js), after which no code can be turned back
-- into an address.
CREATE TABLE download_clicks (who TEXT PRIMARY KEY, day INTEGER NOT NULL);
CREATE INDEX download_clicks_day ON download_clicks (day);
CREATE TABLE download_salt (day INTEGER PRIMARY KEY, salt TEXT NOT NULL);
