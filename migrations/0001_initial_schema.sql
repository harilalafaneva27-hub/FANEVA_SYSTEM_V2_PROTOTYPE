-- FANEVA SYSTEM V2 — D1 schema v1 (STAGING-only migration candidate)
-- SQLite/D1-compatible. Apply only to a newly created, verified-empty V2 STAGING DB.
-- This migration creates schema only: no tenant, accounts, seed data, secrets, or resources.
-- V1 and Android Room databases are explicitly unrelated and must never be used here.

CREATE TABLE tenants (
  id TEXT PRIMARY KEY NOT NULL,
  code TEXT NOT NULL UNIQUE,
  display_name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED','CLOSED')),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (id)
);

CREATE TABLE stores (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  code TEXT NOT NULL,
  name TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, code),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE accounts (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  username TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('ADMIN','GERANT','VENDEUR')),
  status TEXT NOT NULL CHECK (status IN ('PENDING','ACTIVE','SUSPENDED','REVOKED')),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, username),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE account_store_access (
  tenant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  granted_at_utc TEXT NOT NULL,
  granted_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, account_id, store_id),
  FOREIGN KEY (tenant_id, account_id) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, granted_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE devices (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  account_id TEXT NOT NULL,
  token_hash BLOB NOT NULL CHECK (length(token_hash) = 32),
  status TEXT NOT NULL CHECK (status IN ('PENDING','ACTIVE','REVOKED')),
  created_at_utc TEXT NOT NULL,
  last_seen_at_utc TEXT,
  revoked_at_utc TEXT,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, token_hash),
  FOREIGN KEY (tenant_id, account_id) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE device_enrollment_challenges (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  target_device_id TEXT NOT NULL,
  proof_hash BLOB NOT NULL CHECK (length(proof_hash) = 32),
  expires_at_utc TEXT NOT NULL,
  consumed_at_utc TEXT,
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, target_device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE products (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  code TEXT NOT NULL,
  name TEXT NOT NULL,
  category TEXT,
  unit TEXT NOT NULL,
  active INTEGER NOT NULL CHECK (active IN (0,1)),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, code),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE customers (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  contact_ref TEXT,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE suppliers (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  display_name TEXT NOT NULL,
  contact_ref TEXT,
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE stock_balances (
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (typeof(quantity)='integer' AND quantity >= 0),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, store_id, product_id),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE stock_movements (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  movement_type TEXT NOT NULL CHECK (movement_type IN ('OPENING','PURCHASE','SALE','RETURN_IN','ADJUSTMENT_IN','ADJUSTMENT_OUT')),
  quantity_delta INTEGER NOT NULL CHECK (typeof(quantity_delta)='integer'),
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, store_id, product_id, movement_type, source_type, source_id),
  CHECK (movement_type <> 'OPENING' OR quantity_delta >= 0),
  CHECK (movement_type NOT IN ('PURCHASE','RETURN_IN','ADJUSTMENT_IN') OR quantity_delta >= 0),
  CHECK (movement_type NOT IN ('SALE','ADJUSTMENT_OUT') OR quantity_delta <= 0),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE purchases (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  supplier_id TEXT NOT NULL,
  document_number TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  total_mga INTEGER NOT NULL CHECK (typeof(total_mga)='integer' AND total_mga BETWEEN 0 AND 9007199254740991),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, store_id, document_number),
  UNIQUE (tenant_id, id, store_id),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE purchase_lines (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  purchase_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (typeof(quantity)='integer' AND quantity > 0),
  unit_cost_mga INTEGER NOT NULL CHECK (typeof(unit_cost_mga)='integer' AND unit_cost_mga BETWEEN 0 AND 9007199254740991),
  line_total_mga INTEGER NOT NULL CHECK (typeof(line_total_mga)='integer' AND line_total_mga BETWEEN 0 AND 9007199254740991),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, purchase_id) REFERENCES purchases(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE sales (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  sale_type TEXT NOT NULL CHECK (sale_type IN ('CASH','CREDIT')),
  customer_id TEXT,
  document_number TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  total_mga INTEGER NOT NULL CHECK (typeof(total_mga)='integer' AND total_mga BETWEEN 0 AND 9007199254740991),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, store_id, document_number),
  UNIQUE (tenant_id, id, customer_id),
  UNIQUE (tenant_id, id, store_id),
  CHECK ((sale_type='CASH') OR (sale_type='CREDIT' AND customer_id IS NOT NULL)),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE sale_lines (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  quantity INTEGER NOT NULL CHECK (typeof(quantity)='integer' AND quantity > 0),
  unit_price_mga INTEGER NOT NULL CHECK (typeof(unit_price_mga)='integer' AND unit_price_mga BETWEEN 0 AND 9007199254740991),
  line_total_mga INTEGER NOT NULL CHECK (typeof(line_total_mga)='integer' AND line_total_mga BETWEEN 0 AND 9007199254740991),
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, sale_id, id),
  UNIQUE (tenant_id, sale_id, id, product_id),
  FOREIGN KEY (tenant_id, sale_id) REFERENCES sales(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE credit_notes (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  original_sale_id TEXT NOT NULL,
  credit_note_number TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  total_mga INTEGER NOT NULL CHECK (typeof(total_mga)='integer' AND total_mga BETWEEN 0 AND 9007199254740991),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  reversal_of_id TEXT,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, store_id, credit_note_number),
  UNIQUE (tenant_id, id, original_sale_id),
  FOREIGN KEY (tenant_id, original_sale_id, store_id) REFERENCES sales(tenant_id, id, store_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reversal_of_id) REFERENCES credit_notes(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE credit_note_lines (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  credit_note_id TEXT NOT NULL,
  original_sale_id TEXT NOT NULL,
  original_sale_line_id TEXT NOT NULL,
  product_id TEXT NOT NULL,
  credited_quantity INTEGER NOT NULL CHECK (typeof(credited_quantity)='integer' AND credited_quantity > 0),
  original_unit_price_mga INTEGER NOT NULL CHECK (typeof(original_unit_price_mga)='integer' AND original_unit_price_mga BETWEEN 0 AND 9007199254740991),
  line_total_mga INTEGER NOT NULL CHECK (typeof(line_total_mga)='integer' AND line_total_mga BETWEEN 0 AND 9007199254740991),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, credit_note_id, original_sale_id) REFERENCES credit_notes(tenant_id, id, original_sale_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, original_sale_id, original_sale_line_id, product_id) REFERENCES sale_lines(tenant_id, sale_id, id, product_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, product_id) REFERENCES products(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE financial_adjustments (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  customer_id TEXT,
  adjustment_type TEXT NOT NULL,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 0 AND 9007199254740991),
  status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE financial_adjustment_sources (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  adjustment_id TEXT NOT NULL,
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  allocated_amount_mga INTEGER NOT NULL CHECK (typeof(allocated_amount_mga)='integer' AND allocated_amount_mga BETWEEN 0 AND 9007199254740991),
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, adjustment_id) REFERENCES financial_adjustments(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE debt_payments (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  status TEXT NOT NULL CHECK (status IN ('POSTED','REVERSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, created_by, idempotency_key),
  UNIQUE (tenant_id, id, customer_id),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE debt_payment_allocations (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  payment_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  status TEXT NOT NULL CHECK (status IN ('POSTED','REVERSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  reversal_of_id TEXT,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, payment_id, customer_id) REFERENCES debt_payments(tenant_id, id, customer_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, sale_id, customer_id) REFERENCES sales(tenant_id, id, customer_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reversal_of_id) REFERENCES debt_payment_allocations(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE customer_credit_ledger (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  entry_type TEXT NOT NULL CHECK (entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED','APPLIED_TO_INVOICE','CASH_REFUND','REVERSAL')),
  amount_delta_mga INTEGER NOT NULL CHECK (typeof(amount_delta_mga)='integer' AND amount_delta_mga <> 0 AND amount_delta_mga BETWEEN -9007199254740991 AND 9007199254740991),
  source_type TEXT NOT NULL,
  source_id TEXT NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('POSTED','REVERSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, id, customer_id),
  CHECK ((entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED') AND amount_delta_mga > 0) OR (entry_type IN ('APPLIED_TO_INVOICE','CASH_REFUND') AND amount_delta_mga < 0) OR entry_type='REVERSAL'),
  FOREIGN KEY (tenant_id, customer_id) REFERENCES customers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE customer_credit_allocations (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  credit_entry_id TEXT NOT NULL,
  sale_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  status TEXT NOT NULL CHECK (status IN ('POSTED','REVERSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  reversal_of_id TEXT,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, credit_entry_id, customer_id) REFERENCES customer_credit_ledger(tenant_id, id, customer_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, sale_id, customer_id) REFERENCES sales(tenant_id, id, customer_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reversal_of_id) REFERENCES customer_credit_allocations(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE supplier_refunds (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  supplier_id TEXT NOT NULL,
  source_purchase_id TEXT,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, supplier_id) REFERENCES suppliers(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, source_purchase_id) REFERENCES purchases(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE mutation_receipts (
  id TEXT NOT NULL,
  tenant_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  mutation_id TEXT NOT NULL,
  request_hash BLOB NOT NULL CHECK (length(request_hash)=32),
  outcome TEXT NOT NULL CHECK (outcome IN ('ACKNOWLEDGED','REJECTED')),
  response_json TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, id),
  UNIQUE (tenant_id, device_id, idempotency_key),
  UNIQUE (tenant_id, mutation_id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE tenant_sync_state (
  tenant_id TEXT PRIMARY KEY NOT NULL,
  last_cursor INTEGER NOT NULL DEFAULT 0 CHECK (last_cursor >= 0),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at_utc TEXT NOT NULL,
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE change_log (
  tenant_id TEXT NOT NULL,
  cursor INTEGER NOT NULL CHECK (cursor > 0),
  event_id TEXT NOT NULL,
  entity_type TEXT NOT NULL,
  entity_id TEXT NOT NULL,
  store_id TEXT,
  event_type TEXT NOT NULL,
  payload_json TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, cursor),
  UNIQUE (tenant_id, event_id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE device_cursors (
  tenant_id TEXT NOT NULL,
  device_id TEXT NOT NULL,
  last_cursor INTEGER NOT NULL DEFAULT 0 CHECK (last_cursor >= 0),
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, device_id),
  FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT
);

CREATE TABLE bootstrap_imports (
  tenant_id TEXT NOT NULL,
  environment TEXT NOT NULL CHECK (environment='STAGING'),
  seed_id TEXT NOT NULL,
  seed_version INTEGER NOT NULL CHECK (seed_version > 0),
  seed_checksum BLOB NOT NULL CHECK (length(seed_checksum)=32),
  import_status TEXT NOT NULL CHECK (import_status IN ('PREPARED','VALIDATED','COMMITTED','REJECTED')),
  record_counts_json TEXT NOT NULL,
  created_at_utc TEXT NOT NULL,
  committed_at_utc TEXT,
  PRIMARY KEY (tenant_id, environment),
  UNIQUE (tenant_id, seed_id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT
);

CREATE TABLE audit_events (
  tenant_id TEXT NOT NULL,
  environment TEXT NOT NULL CHECK (environment='STAGING'),
  sequence INTEGER NOT NULL CHECK (sequence > 0),
  id TEXT NOT NULL,
  actor_id TEXT,
  device_id TEXT,
  event_type TEXT NOT NULL,
  entity_type TEXT,
  entity_id TEXT,
  payload_hash BLOB NOT NULL CHECK (length(payload_hash)=32),
  previous_hash BLOB NOT NULL CHECK (length(previous_hash)=32),
  event_hash BLOB NOT NULL CHECK (length(event_hash)=32),
  hash_algorithm TEXT NOT NULL CHECK (hash_algorithm='SHA-256'),
  hash_version TEXT NOT NULL CHECK (hash_version='FANEVA-AUDIT-V1'),
  occurred_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, environment, sequence),
  UNIQUE (tenant_id, environment, id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, actor_id) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT
);

CREATE UNIQUE INDEX uq_stock_one_opening_per_store_product ON stock_movements(tenant_id, store_id, product_id) WHERE movement_type='OPENING';
CREATE INDEX idx_accounts_tenant_role_status ON accounts(tenant_id, role, status);
CREATE INDEX idx_devices_tenant_status ON devices(tenant_id, status);
CREATE INDEX idx_products_tenant_active_name ON products(tenant_id, active, name);
CREATE INDEX idx_customers_tenant_name ON customers(tenant_id, display_name);
CREATE INDEX idx_suppliers_tenant_name ON suppliers(tenant_id, display_name);
CREATE INDEX idx_stock_movements_store_product_time ON stock_movements(tenant_id, store_id, product_id, created_at_utc);
CREATE INDEX idx_purchases_store_time ON purchases(tenant_id, store_id, created_at_utc);
CREATE INDEX idx_sales_store_time ON sales(tenant_id, store_id, created_at_utc);
CREATE INDEX idx_sales_customer_time ON sales(tenant_id, customer_id, created_at_utc);
CREATE INDEX idx_debt_payments_customer_time ON debt_payments(tenant_id, customer_id, created_at_utc);
CREATE INDEX idx_credit_notes_invoice ON credit_notes(tenant_id, original_sale_id);
CREATE INDEX idx_change_log_store_cursor ON change_log(tenant_id, store_id, cursor);
CREATE INDEX idx_audit_events_tenant_sequence ON audit_events(tenant_id, environment, sequence);

-- Append-only evidence and ledger rows: corrections are represented by new events.
CREATE TRIGGER trg_audit_events_no_update
BEFORE UPDATE ON audit_events BEGIN
  SELECT RAISE(ABORT, 'AUDIT_EVENTS_APPEND_ONLY');
END;
CREATE TRIGGER trg_audit_events_no_delete
BEFORE DELETE ON audit_events BEGIN
  SELECT RAISE(ABORT, 'AUDIT_EVENTS_APPEND_ONLY');
END;

CREATE TRIGGER trg_stock_movements_no_update
BEFORE UPDATE ON stock_movements BEGIN
  SELECT RAISE(ABORT, 'STOCK_MOVEMENTS_APPEND_ONLY');
END;
CREATE TRIGGER trg_stock_movements_no_delete
BEFORE DELETE ON stock_movements BEGIN
  SELECT RAISE(ABORT, 'STOCK_MOVEMENTS_APPEND_ONLY');
END;

CREATE TRIGGER trg_change_log_no_update
BEFORE UPDATE ON change_log BEGIN
  SELECT RAISE(ABORT, 'CHANGE_LOG_APPEND_ONLY');
END;
CREATE TRIGGER trg_change_log_no_delete
BEFORE DELETE ON change_log BEGIN
  SELECT RAISE(ABORT, 'CHANGE_LOG_APPEND_ONLY');
END;

CREATE TRIGGER trg_mutation_receipts_no_update
BEFORE UPDATE ON mutation_receipts BEGIN
  SELECT RAISE(ABORT, 'MUTATION_RECEIPTS_APPEND_ONLY');
END;
CREATE TRIGGER trg_mutation_receipts_no_delete
BEFORE DELETE ON mutation_receipts BEGIN
  SELECT RAISE(ABORT, 'MUTATION_RECEIPTS_APPEND_ONLY');
END;

CREATE TRIGGER trg_debt_payment_allocations_no_update
BEFORE UPDATE ON debt_payment_allocations BEGIN
  SELECT RAISE(ABORT, 'DEBT_PAYMENT_ALLOCATIONS_APPEND_ONLY');
END;
CREATE TRIGGER trg_debt_payment_allocations_no_delete
BEFORE DELETE ON debt_payment_allocations BEGIN
  SELECT RAISE(ABORT, 'DEBT_PAYMENT_ALLOCATIONS_APPEND_ONLY');
END;

CREATE TRIGGER trg_customer_credit_ledger_no_update
BEFORE UPDATE ON customer_credit_ledger BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_LEDGER_APPEND_ONLY');
END;
CREATE TRIGGER trg_customer_credit_ledger_no_delete
BEFORE DELETE ON customer_credit_ledger BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_LEDGER_APPEND_ONLY');
END;

CREATE TRIGGER trg_customer_credit_allocations_no_update
BEFORE UPDATE ON customer_credit_allocations BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATIONS_APPEND_ONLY');
END;
CREATE TRIGGER trg_customer_credit_allocations_no_delete
BEFORE DELETE ON customer_credit_allocations BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATIONS_APPEND_ONLY');
END;

CREATE TRIGGER trg_financial_adjustment_sources_no_update
BEFORE UPDATE ON financial_adjustment_sources BEGIN
  SELECT RAISE(ABORT, 'FINANCIAL_ADJUSTMENT_SOURCES_APPEND_ONLY');
END;
CREATE TRIGGER trg_financial_adjustment_sources_no_delete
BEFORE DELETE ON financial_adjustment_sources BEGIN
  SELECT RAISE(ABORT, 'FINANCIAL_ADJUSTMENT_SOURCES_APPEND_ONLY');
END;

-- Debt payments may allocate only to a posted CREDIT invoice for the same customer.
CREATE TRIGGER trg_debt_allocation_requires_posted_credit_invoice
BEFORE INSERT ON debt_payment_allocations
WHEN NOT EXISTS (
  SELECT 1 FROM sales s
  WHERE s.tenant_id=NEW.tenant_id
    AND s.id=NEW.sale_id
    AND s.customer_id=NEW.customer_id
    AND s.sale_type='CREDIT'
    AND s.status='POSTED'
)
OR NOT EXISTS (
  SELECT 1 FROM debt_payments p
  WHERE p.tenant_id=NEW.tenant_id
    AND p.id=NEW.payment_id
    AND p.customer_id=NEW.customer_id
    AND p.status='POSTED'
)
BEGIN
  SELECT RAISE(ABORT, 'ALLOCATION_REQUIRES_POSTED_CREDIT_INVOICE_AND_PAYMENT');
END;

-- Customer credit can be applied only from a positive credit source to a posted CREDIT invoice.
CREATE TRIGGER trg_customer_credit_allocation_requires_credit_source
BEFORE INSERT ON customer_credit_allocations
WHEN NOT EXISTS (
  SELECT 1 FROM customer_credit_ledger c
  WHERE c.tenant_id=NEW.tenant_id
    AND c.id=NEW.credit_entry_id
    AND c.customer_id=NEW.customer_id
    AND c.entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED')
    AND c.amount_delta_mga > 0
    AND c.status='POSTED'
)
OR NOT EXISTS (
  SELECT 1 FROM sales s
  WHERE s.tenant_id=NEW.tenant_id
    AND s.id=NEW.sale_id
    AND s.customer_id=NEW.customer_id
    AND s.sale_type='CREDIT'
    AND s.status='POSTED'
)
BEGIN
  SELECT RAISE(ABORT, 'CREDIT_ALLOCATION_REQUIRES_POSITIVE_CREDIT_AND_POSTED_CREDIT_INVOICE');
END;

-- Credit note lines must preserve the original invoice price and not exceed sold quantity.
CREATE TRIGGER trg_credit_note_line_matches_original_sale_line
BEFORE INSERT ON credit_note_lines
WHEN NOT EXISTS (
  SELECT 1
  FROM sale_lines sl
  JOIN credit_notes cn
    ON cn.tenant_id=NEW.tenant_id
   AND cn.id=NEW.credit_note_id
   AND cn.original_sale_id=NEW.original_sale_id
  WHERE sl.tenant_id=NEW.tenant_id
    AND sl.sale_id=NEW.original_sale_id
    AND sl.id=NEW.original_sale_line_id
    AND sl.product_id=NEW.product_id
    AND sl.unit_price_mga=NEW.original_unit_price_mga
    AND NEW.credited_quantity <= sl.quantity
    AND NEW.line_total_mga = NEW.credited_quantity * NEW.original_unit_price_mga
    AND (
      SELECT COALESCE(SUM(existing.credited_quantity),0)
      FROM credit_note_lines existing
      JOIN credit_notes existing_cn
        ON existing_cn.tenant_id=existing.tenant_id
       AND existing_cn.id=existing.credit_note_id
      WHERE existing.tenant_id=NEW.tenant_id
        AND existing.original_sale_id=NEW.original_sale_id
        AND existing.original_sale_line_id=NEW.original_sale_line_id
        AND existing_cn.status IN ('DRAFT','POSTED')
    ) + NEW.credited_quantity <= sl.quantity
)
BEGIN
  SELECT RAISE(ABORT, 'CREDIT_NOTE_LINE_EXCEEDS_ORIGINAL_SALE_LINE');
END;
