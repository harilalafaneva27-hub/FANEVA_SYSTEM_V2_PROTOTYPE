-- Store attribution for customer-ledger writes.
-- Historical rows remain NULL: do not infer a store or backfill to A1.
-- New SALE and DEBT_PAYMENT handlers MUST set store_id explicitly and reject
-- writes without an authorized store grant. The Worker emits store-scoped
-- sync_events from this same attribution.
PRAGMA foreign_keys = ON;

ALTER TABLE business_debt_payments
  ADD COLUMN store_id TEXT
  CHECK (store_id IS NULL OR store_id IN ('A1', 'A2', 'A3', 'A4', 'A5'));

ALTER TABLE business_customer_ledger
  ADD COLUMN store_id TEXT
  CHECK (store_id IS NULL OR store_id IN ('A1', 'A2', 'A3', 'A4', 'A5'));

CREATE INDEX IF NOT EXISTS idx_debt_payments_store_customer_time
  ON business_debt_payments(store_id, customer_id, created_at);

CREATE INDEX IF NOT EXISTS idx_customer_ledger_store_customer_time
  ON business_customer_ledger(store_id, customer_id, created_at);
