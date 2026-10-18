-- Origin+ memberships (Will, 2026-10-17): one row per Stripe subscription,
-- kept current by the customer.subscription.* webhooks (stripe.js). A player
-- is a member while one of their rows is live and its paid period hasn't run
-- out (stripe.js isMember).
CREATE TABLE IF NOT EXISTS plus (
  subscription TEXT PRIMARY KEY,   -- Stripe subscription id (sub_...)
  uuid         TEXT NOT NULL,      -- the Minecraft account the membership is for
  name         TEXT,               -- the name typed at checkout, for support lookups
  customer     TEXT,               -- Stripe customer id (cus_...)
  status       TEXT NOT NULL,      -- Stripe's subscription status
  period_end   INTEGER NOT NULL,   -- unix seconds the paid period runs to
  event_at     INTEGER NOT NULL    -- Stripe event.created of the last update; older events never overwrite newer
);
CREATE INDEX IF NOT EXISTS plus_uuid ON plus (uuid);
