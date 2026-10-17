-- Stripe payments that were fully refunded or charged back. A "paid" webhook
-- that Stripe retries after the refund must not hand the items back.
CREATE TABLE IF NOT EXISTS revoked (
  payment TEXT PRIMARY KEY,   -- Stripe payment intent id (owned.payment)
  at      INTEGER NOT NULL
);
