-- FANEVA SYSTEM V2 — P0-3 stock ledger / projection consistency (migration 0004)
-- Canonical stock truth is the append-only stock_movements ledger.
-- stock_balances is a transactionally maintained projection, never an independent source.
-- Apply only after migrations 0001-0003 on a fresh isolated V2 STAGING database.

-- Reject any movement that would make the canonical ledger quantity negative.
CREATE TRIGGER trg_stock_movement_prevent_negative
BEFORE INSERT ON stock_movements
WHEN COALESCE((
  SELECT SUM(m.quantity_delta)
  FROM stock_movements m
  WHERE m.tenant_id=NEW.tenant_id
    AND m.store_id=NEW.store_id
    AND m.product_id=NEW.product_id
),0) + NEW.quantity_delta < 0
BEGIN
  SELECT RAISE(ABORT, 'STOCK_NEGATIVE_NOT_ALLOWED');
END;

-- Every accepted movement updates the projection in the same SQLite/D1 statement
-- and therefore in the same enclosing D1 transaction.
CREATE TRIGGER trg_stock_movement_updates_balance
AFTER INSERT ON stock_movements
BEGIN
  INSERT INTO stock_balances
    (tenant_id,store_id,product_id,quantity,version,updated_at_utc)
  SELECT
    m.tenant_id,
    m.store_id,
    m.product_id,
    SUM(m.quantity_delta),
    COUNT(*),
    MAX(m.created_at_utc)
  FROM stock_movements m
  WHERE m.tenant_id=NEW.tenant_id
    AND m.store_id=NEW.store_id
    AND m.product_id=NEW.product_id
  GROUP BY m.tenant_id,m.store_id,m.product_id
  ON CONFLICT(tenant_id,store_id,product_id) DO UPDATE SET
    quantity=excluded.quantity,
    version=excluded.version,
    updated_at_utc=excluded.updated_at_utc;
END;

-- A projection row must exactly match the canonical movement ledger.
-- This also prevents ad-hoc INSERT/UPDATE of stock quantities outside ledger events.
CREATE TRIGGER trg_stock_balance_insert_must_match_ledger
BEFORE INSERT ON stock_balances
WHEN NEW.quantity <> COALESCE((
       SELECT SUM(m.quantity_delta) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     ),0)
  OR NEW.version <> COALESCE((
       SELECT COUNT(*) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     ),0)
  OR NEW.updated_at_utc IS NOT (
       SELECT MAX(m.created_at_utc) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     )
BEGIN
  SELECT RAISE(ABORT, 'STOCK_BALANCE_MUST_MATCH_MOVEMENT_LEDGER');
END;

CREATE TRIGGER trg_stock_balance_update_must_match_ledger
BEFORE UPDATE ON stock_balances
WHEN NEW.quantity <> COALESCE((
       SELECT SUM(m.quantity_delta) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     ),0)
  OR NEW.version <> COALESCE((
       SELECT COUNT(*) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     ),0)
  OR NEW.updated_at_utc IS NOT (
       SELECT MAX(m.created_at_utc) FROM stock_movements m
       WHERE m.tenant_id=NEW.tenant_id AND m.store_id=NEW.store_id AND m.product_id=NEW.product_id
     )
BEGIN
  SELECT RAISE(ABORT, 'STOCK_BALANCE_MUST_MATCH_MOVEMENT_LEDGER');
END;

CREATE TRIGGER trg_stock_balances_no_delete
BEFORE DELETE ON stock_balances
BEGIN
  SELECT RAISE(ABORT, 'STOCK_BALANCE_PROJECTION_CANNOT_BE_DELETED');
END;
