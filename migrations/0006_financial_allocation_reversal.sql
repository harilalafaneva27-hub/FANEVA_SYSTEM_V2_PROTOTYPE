-- FANEVA SYSTEM V2 — P0 financial allocation reversal (forward-only migration 0006)
-- Adds append-only, source-linked reversal availability and accounting-period guards.
-- Apply only after migrations 0001-0005 on isolated V2 STAGING. Never apply to V1/production.

-- Existing reversal_of_id rows predate this protocol and have ambiguous semantics.
-- Fail closed rather than silently reinterpret historical rows.
CREATE TABLE migration_0006_preflight (ok INTEGER NOT NULL CHECK (ok = 1));
INSERT INTO migration_0006_preflight(ok)
SELECT CASE WHEN
  NOT EXISTS (SELECT 1 FROM debt_payment_allocations WHERE reversal_of_id IS NOT NULL)
  AND NOT EXISTS (SELECT 1 FROM customer_credit_allocations WHERE reversal_of_id IS NOT NULL)
THEN 1 ELSE 0 END;
DROP TABLE migration_0006_preflight;

CREATE TABLE accounting_periods (
  id TEXT PRIMARY KEY NOT NULL,
  tenant_id TEXT NOT NULL,
  period_code TEXT NOT NULL,
  start_date TEXT NOT NULL CHECK (length(start_date)=10),
  end_date TEXT NOT NULL CHECK (length(end_date)=10 AND end_date > start_date),
  status TEXT NOT NULL CHECK (status IN ('OPEN','CLOSED')),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  closed_at_utc TEXT,
  closed_by TEXT,
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, period_code),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, closed_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  CHECK (
    (status='OPEN' AND closed_at_utc IS NULL AND closed_by IS NULL)
    OR (status='CLOSED' AND closed_at_utc IS NOT NULL AND closed_by IS NOT NULL)
  )
);

CREATE TRIGGER trg_accounting_period_no_overlap_insert
BEFORE INSERT ON accounting_periods
WHEN EXISTS (
  SELECT 1 FROM accounting_periods p
  WHERE p.tenant_id=NEW.tenant_id
    AND date(NEW.start_date) < date(p.end_date)
    AND date(p.start_date) < date(NEW.end_date)
)
BEGIN SELECT RAISE(ABORT, 'ACCOUNTING_PERIOD_OVERLAP'); END;

CREATE TRIGGER trg_accounting_period_no_overlap_update
BEFORE UPDATE ON accounting_periods
WHEN EXISTS (
  SELECT 1 FROM accounting_periods p
  WHERE p.tenant_id=NEW.tenant_id AND p.id<>OLD.id
    AND date(NEW.start_date) < date(p.end_date)
    AND date(p.start_date) < date(NEW.end_date)
)
BEGIN SELECT RAISE(ABORT, 'ACCOUNTING_PERIOD_OVERLAP'); END;

CREATE TRIGGER trg_accounting_period_closed_immutable
BEFORE UPDATE ON accounting_periods
WHEN OLD.status='CLOSED'
BEGIN SELECT RAISE(ABORT, 'CLOSED_ACCOUNTING_PERIOD_IMMUTABLE'); END;

CREATE TRIGGER trg_accounting_period_closed_no_delete
BEFORE DELETE ON accounting_periods
WHEN OLD.status='CLOSED'
BEGIN SELECT RAISE(ABORT, 'CLOSED_ACCOUNTING_PERIOD_IMMUTABLE'); END;

CREATE TRIGGER trg_accounting_period_close_metadata
BEFORE UPDATE ON accounting_periods
WHEN OLD.status='OPEN' AND NEW.status='CLOSED'
 AND (NEW.closed_at_utc IS NULL OR NEW.closed_by IS NULL OR NEW.version<>OLD.version+1)
BEGIN SELECT RAISE(ABORT, 'ACCOUNTING_PERIOD_CLOSE_METADATA_REQUIRED'); END;

CREATE TABLE payment_availability_ledger (
  id TEXT PRIMARY KEY NOT NULL,
  tenant_id TEXT NOT NULL,
  payment_id TEXT NOT NULL,
  customer_id TEXT NOT NULL,
  store_id TEXT NOT NULL,
  reversal_allocation_id TEXT NOT NULL,
  parent_availability_entry_id TEXT,
  amount_mga INTEGER NOT NULL CHECK (typeof(amount_mga)='integer' AND amount_mga BETWEEN 1 AND 9007199254740991),
  accounting_period_id TEXT NOT NULL,
  reason TEXT NOT NULL CHECK (length(trim(reason)) BETWEEN 3 AND 500),
  created_at_utc TEXT NOT NULL,
  created_by TEXT NOT NULL,
  device_id TEXT NOT NULL,
  idempotency_key TEXT NOT NULL,
  request_hash BLOB NOT NULL CHECK (length(request_hash)=32),
  UNIQUE (tenant_id, id),
  UNIQUE (tenant_id, reversal_allocation_id),
  UNIQUE (tenant_id, device_id, idempotency_key),
  FOREIGN KEY (tenant_id, payment_id, customer_id) REFERENCES debt_payments(tenant_id, id, customer_id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, store_id) REFERENCES stores(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, reversal_allocation_id) REFERENCES debt_payment_allocations(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, parent_availability_entry_id) REFERENCES payment_availability_ledger(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, accounting_period_id) REFERENCES accounting_periods(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, created_by) REFERENCES accounts(tenant_id, id) ON DELETE RESTRICT,
  FOREIGN KEY (tenant_id, device_id) REFERENCES devices(tenant_id, id) ON DELETE RESTRICT
);

ALTER TABLE debt_payment_allocations ADD COLUMN payment_availability_entry_id TEXT REFERENCES payment_availability_ledger(id) ON DELETE RESTRICT;
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_reason TEXT CHECK (reversal_reason IS NULL OR length(trim(reversal_reason)) BETWEEN 3 AND 500);
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_period_id TEXT REFERENCES accounting_periods(id) ON DELETE RESTRICT;
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_device_id TEXT;
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_idempotency_key TEXT;
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_request_hash BLOB CHECK (reversal_request_hash IS NULL OR length(reversal_request_hash)=32);
ALTER TABLE debt_payment_allocations ADD COLUMN reversal_expected_version INTEGER;

ALTER TABLE customer_credit_allocations ADD COLUMN reversal_reason TEXT CHECK (reversal_reason IS NULL OR length(trim(reversal_reason)) BETWEEN 3 AND 500);
ALTER TABLE customer_credit_allocations ADD COLUMN reversal_period_id TEXT REFERENCES accounting_periods(id) ON DELETE RESTRICT;
ALTER TABLE customer_credit_allocations ADD COLUMN reversal_device_id TEXT;
ALTER TABLE customer_credit_allocations ADD COLUMN reversal_idempotency_key TEXT;
ALTER TABLE customer_credit_allocations ADD COLUMN reversal_request_hash BLOB CHECK (reversal_request_hash IS NULL OR length(reversal_request_hash)=32);
ALTER TABLE customer_credit_allocations ADD COLUMN reversal_expected_version INTEGER;

ALTER TABLE customer_credit_ledger ADD COLUMN parent_credit_entry_id TEXT;
ALTER TABLE customer_credit_ledger ADD COLUMN accounting_period_id TEXT REFERENCES accounting_periods(id) ON DELETE RESTRICT;
ALTER TABLE customer_credit_ledger ADD COLUMN device_id TEXT;
ALTER TABLE customer_credit_ledger ADD COLUMN idempotency_key TEXT;
ALTER TABLE customer_credit_ledger ADD COLUMN request_hash BLOB CHECK (request_hash IS NULL OR length(request_hash)=32);

CREATE TABLE allocation_reversal_state (
  tenant_id TEXT NOT NULL,
  allocation_type TEXT NOT NULL CHECK (allocation_type IN ('DEBT_PAYMENT','CUSTOMER_CREDIT')),
  original_allocation_id TEXT NOT NULL,
  original_amount_mga INTEGER NOT NULL CHECK (typeof(original_amount_mga)='integer' AND original_amount_mga > 0),
  reversed_amount_mga INTEGER NOT NULL DEFAULT 0 CHECK (typeof(reversed_amount_mga)='integer' AND reversed_amount_mga >= 0),
  version INTEGER NOT NULL DEFAULT 1 CHECK (version >= 1),
  updated_at_utc TEXT NOT NULL,
  PRIMARY KEY (tenant_id, allocation_type, original_allocation_id),
  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE RESTRICT,
  CHECK (reversed_amount_mga <= original_amount_mga)
);

INSERT INTO allocation_reversal_state
  (tenant_id,allocation_type,original_allocation_id,original_amount_mga,reversed_amount_mga,version,updated_at_utc)
SELECT tenant_id,'DEBT_PAYMENT',id,amount_mga,0,1,created_at_utc
FROM debt_payment_allocations WHERE reversal_of_id IS NULL AND status='POSTED';

INSERT INTO allocation_reversal_state
  (tenant_id,allocation_type,original_allocation_id,original_amount_mga,reversed_amount_mga,version,updated_at_utc)
SELECT tenant_id,'CUSTOMER_CREDIT',id,amount_mga,0,1,created_at_utc
FROM customer_credit_allocations WHERE reversal_of_id IS NULL AND status='POSTED';

CREATE UNIQUE INDEX uq_debt_allocation_reversal_idempotency
ON debt_payment_allocations(tenant_id,reversal_device_id,reversal_idempotency_key)
WHERE reversal_of_id IS NOT NULL;

CREATE UNIQUE INDEX uq_customer_credit_reversal_idempotency
ON customer_credit_allocations(tenant_id,reversal_device_id,reversal_idempotency_key)
WHERE reversal_of_id IS NOT NULL;

CREATE UNIQUE INDEX uq_customer_credit_reversal_ledger_source
ON customer_credit_ledger(tenant_id,source_type,source_id)
WHERE entry_type='REVERSAL' AND source_type='CUSTOMER_CREDIT_ALLOCATION_REVERSAL';

CREATE UNIQUE INDEX uq_customer_credit_reversal_idempotency
ON customer_credit_ledger(tenant_id,device_id,idempotency_key)
WHERE entry_type='REVERSAL';

CREATE INDEX idx_debt_allocations_reversal
ON debt_payment_allocations(tenant_id,reversal_of_id,status);

CREATE INDEX idx_customer_credit_allocations_reversal
ON customer_credit_allocations(tenant_id,reversal_of_id,status);

CREATE INDEX idx_payment_availability_payment
ON payment_availability_ledger(tenant_id,payment_id,created_at_utc);

DROP TRIGGER IF EXISTS trg_debt_allocation_payment_cap;
DROP TRIGGER IF EXISTS trg_debt_allocation_invoice_outstanding_cap;
DROP TRIGGER IF EXISTS trg_customer_credit_source_cap;
DROP TRIGGER IF EXISTS trg_customer_credit_invoice_outstanding_cap;
DROP TRIGGER IF EXISTS trg_customer_credit_allocation_requires_credit_source;

CREATE TRIGGER trg_debt_allocation_v6_guard
BEFORE INSERT ON debt_payment_allocations
BEGIN
  SELECT CASE WHEN NEW.status<>'POSTED'
    THEN RAISE(ABORT, 'NEW_ALLOCATION_MUST_BE_POSTED') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND (
       NEW.reversal_reason IS NOT NULL OR NEW.reversal_period_id IS NOT NULL
       OR NEW.reversal_device_id IS NOT NULL OR NEW.reversal_idempotency_key IS NOT NULL
       OR NEW.reversal_request_hash IS NOT NULL OR NEW.reversal_expected_version IS NOT NULL
    )
    THEN RAISE(ABORT, 'REVERSAL_METADATA_REQUIRES_ORIGINAL_ALLOCATION_LINK') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND NEW.payment_availability_entry_id IS NULL
    AND NEW.amount_mga > (
      SELECT p.amount_mga - COALESCE((
        SELECT SUM(a.amount_mga) FROM debt_payment_allocations a
        WHERE a.tenant_id=NEW.tenant_id AND a.payment_id=NEW.payment_id
          AND a.status='POSTED' AND a.reversal_of_id IS NULL
          AND a.payment_availability_entry_id IS NULL
      ),0)
      FROM debt_payments p
      WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.payment_id AND p.status='POSTED'
    )
    THEN RAISE(ABORT, 'PAYMENT_ALLOCATION_EXCEEDS_UNAPPLIED_PAYMENT') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND NEW.payment_availability_entry_id IS NOT NULL
    AND NOT EXISTS (
      SELECT 1 FROM payment_availability_ledger v
      WHERE v.tenant_id=NEW.tenant_id AND v.id=NEW.payment_availability_entry_id
        AND v.payment_id=NEW.payment_id AND v.customer_id=NEW.customer_id
        AND v.store_id=(SELECT p.store_id FROM debt_payments p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.payment_id)
        AND NEW.amount_mga <= v.amount_mga - COALESCE((
          SELECT SUM(a.amount_mga) FROM debt_payment_allocations a
          WHERE a.tenant_id=NEW.tenant_id AND a.payment_availability_entry_id=v.id
            AND a.status='POSTED' AND a.reversal_of_id IS NULL
        ),0)
    )
    THEN RAISE(ABORT, 'PAYMENT_AVAILABILITY_ENTRY_INVALID_OR_EXHAUSTED') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND NEW.amount_mga > (
    SELECT s.total_mga
      - COALESCE((SELECT SUM(cn.total_mga) FROM credit_notes cn
        WHERE cn.tenant_id=NEW.tenant_id AND cn.original_sale_id=NEW.sale_id AND cn.status='POSTED'),0)
      - COALESCE((SELECT SUM(CASE WHEN a.reversal_of_id IS NULL THEN a.amount_mga ELSE -a.amount_mga END)
        FROM debt_payment_allocations a WHERE a.tenant_id=NEW.tenant_id AND a.sale_id=NEW.sale_id AND a.status='POSTED'),0)
      - COALESCE((SELECT SUM(CASE WHEN a.reversal_of_id IS NULL THEN a.amount_mga ELSE -a.amount_mga END)
        FROM customer_credit_allocations a WHERE a.tenant_id=NEW.tenant_id AND a.sale_id=NEW.sale_id AND a.status='POSTED'),0)
    FROM sales s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sale_id
      AND s.sale_type='CREDIT' AND s.status='POSTED'
  )
    THEN RAISE(ABORT, 'DEBT_ALLOCATION_EXCEEDS_NET_INVOICE_OUTSTANDING') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NOT NULL AND NOT EXISTS (
    SELECT 1
    FROM debt_payment_allocations o
    JOIN allocation_reversal_state st
      ON st.tenant_id=o.tenant_id AND st.allocation_type='DEBT_PAYMENT' AND st.original_allocation_id=o.id
    JOIN accounting_periods source_period
      ON source_period.tenant_id=o.tenant_id
     AND date(o.created_at_utc,'+3 hours')>=date(source_period.start_date)
     AND date(o.created_at_utc,'+3 hours')<date(source_period.end_date)
    JOIN accounting_periods target_period
      ON target_period.tenant_id=NEW.tenant_id AND target_period.id=NEW.reversal_period_id AND target_period.status='OPEN'
    JOIN devices d ON d.tenant_id=NEW.tenant_id AND d.id=NEW.reversal_device_id
      AND d.account_id=NEW.created_by AND d.status='ACTIVE'
    JOIN accounts a ON a.tenant_id=d.tenant_id AND a.id=d.account_id
      AND a.status='ACTIVE' AND a.role='ADMIN'
    JOIN account_store_access asa ON asa.tenant_id=o.tenant_id
      AND asa.account_id=NEW.created_by AND asa.store_id=(SELECT p.store_id FROM debt_payments p WHERE p.tenant_id=o.tenant_id AND p.id=o.payment_id)
    WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.reversal_of_id
      AND o.reversal_of_id IS NULL AND o.status='POSTED'
      AND o.payment_id=NEW.payment_id AND o.sale_id=NEW.sale_id
      AND o.customer_id=NEW.customer_id
      AND o.payment_availability_entry_id IS NEW.payment_availability_entry_id
      AND NEW.amount_mga <= o.amount_mga-st.reversed_amount_mga
      AND NEW.reversal_expected_version=st.version
      AND length(trim(COALESCE(NEW.reversal_reason,''))) BETWEEN 3 AND 500
      AND length(COALESCE(NEW.reversal_idempotency_key,'')) BETWEEN 1 AND 200
      AND length(NEW.reversal_request_hash)=32
      AND (
        (source_period.status='OPEN' AND target_period.id=source_period.id)
        OR
        (source_period.status='CLOSED' AND target_period.id=(
          SELECT p.id FROM accounting_periods p
          WHERE p.tenant_id=source_period.tenant_id AND p.status='OPEN'
            AND p.start_date>=source_period.end_date
          ORDER BY p.start_date LIMIT 1
        ))
      )
      AND date(NEW.created_at_utc,'+3 hours')>=date(target_period.start_date)
      AND date(NEW.created_at_utc,'+3 hours')<date(target_period.end_date)
  )
    THEN RAISE(ABORT, 'DEBT_ALLOCATION_REVERSAL_INVALID_OR_STALE') END;
END;

CREATE TRIGGER trg_customer_credit_allocation_v6_guard
BEFORE INSERT ON customer_credit_allocations
BEGIN
  SELECT CASE WHEN NEW.status<>'POSTED'
    THEN RAISE(ABORT, 'NEW_ALLOCATION_MUST_BE_POSTED') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND (
       NEW.reversal_reason IS NOT NULL OR NEW.reversal_period_id IS NOT NULL
       OR NEW.reversal_device_id IS NOT NULL OR NEW.reversal_idempotency_key IS NOT NULL
       OR NEW.reversal_request_hash IS NOT NULL OR NEW.reversal_expected_version IS NOT NULL
    )
    THEN RAISE(ABORT, 'REVERSAL_METADATA_REQUIRES_ORIGINAL_ALLOCATION_LINK') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND NEW.amount_mga > (
    SELECT c.amount_delta_mga - COALESCE((
      SELECT SUM(a.amount_mga) FROM customer_credit_allocations a
      WHERE a.tenant_id=NEW.tenant_id AND a.credit_entry_id=NEW.credit_entry_id
        AND a.status='POSTED' AND a.reversal_of_id IS NULL
    ),0)
    FROM customer_credit_ledger c
    WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.credit_entry_id
      AND c.amount_delta_mga>0 AND c.status='POSTED'
      AND c.entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED','REVERSAL')
  )
    THEN RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATION_EXCEEDS_SOURCE_AVAILABILITY') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NULL AND NEW.amount_mga > (
    SELECT s.total_mga
      - COALESCE((SELECT SUM(cn.total_mga) FROM credit_notes cn
        WHERE cn.tenant_id=NEW.tenant_id AND cn.original_sale_id=NEW.sale_id AND cn.status='POSTED'),0)
      - COALESCE((SELECT SUM(CASE WHEN a.reversal_of_id IS NULL THEN a.amount_mga ELSE -a.amount_mga END)
        FROM debt_payment_allocations a WHERE a.tenant_id=NEW.tenant_id AND a.sale_id=NEW.sale_id AND a.status='POSTED'),0)
      - COALESCE((SELECT SUM(CASE WHEN a.reversal_of_id IS NULL THEN a.amount_mga ELSE -a.amount_mga END)
        FROM customer_credit_allocations a WHERE a.tenant_id=NEW.tenant_id AND a.sale_id=NEW.sale_id AND a.status='POSTED'),0)
    FROM sales s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sale_id
      AND s.sale_type='CREDIT' AND s.status='POSTED'
  )
    THEN RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATION_EXCEEDS_NET_INVOICE_OUTSTANDING') END;

  SELECT CASE WHEN NEW.reversal_of_id IS NOT NULL AND NOT EXISTS (
    SELECT 1
    FROM customer_credit_allocations o
    JOIN allocation_reversal_state st
      ON st.tenant_id=o.tenant_id AND st.allocation_type='CUSTOMER_CREDIT' AND st.original_allocation_id=o.id
    JOIN accounting_periods source_period
      ON source_period.tenant_id=o.tenant_id
     AND date(o.created_at_utc,'+3 hours')>=date(source_period.start_date)
     AND date(o.created_at_utc,'+3 hours')<date(source_period.end_date)
    JOIN accounting_periods target_period
      ON target_period.tenant_id=NEW.tenant_id AND target_period.id=NEW.reversal_period_id AND target_period.status='OPEN'
    JOIN devices d ON d.tenant_id=NEW.tenant_id AND d.id=NEW.reversal_device_id
      AND d.account_id=NEW.created_by AND d.status='ACTIVE'
    JOIN accounts a ON a.tenant_id=d.tenant_id AND a.id=d.account_id
      AND a.status='ACTIVE' AND a.role='ADMIN'
    JOIN account_store_access asa ON asa.tenant_id=o.tenant_id
      AND asa.account_id=NEW.created_by AND asa.store_id=(SELECT s.store_id FROM sales s WHERE s.tenant_id=o.tenant_id AND s.id=o.sale_id)
    WHERE o.tenant_id=NEW.tenant_id AND o.id=NEW.reversal_of_id
      AND o.reversal_of_id IS NULL AND o.status='POSTED'
      AND o.credit_entry_id=NEW.credit_entry_id AND o.sale_id=NEW.sale_id
      AND o.customer_id=NEW.customer_id
      AND NEW.amount_mga <= o.amount_mga-st.reversed_amount_mga
      AND NEW.reversal_expected_version=st.version
      AND length(trim(COALESCE(NEW.reversal_reason,''))) BETWEEN 3 AND 500
      AND length(COALESCE(NEW.reversal_idempotency_key,'')) BETWEEN 1 AND 200
      AND length(NEW.reversal_request_hash)=32
      AND (
        (source_period.status='OPEN' AND target_period.id=source_period.id)
        OR
        (source_period.status='CLOSED' AND target_period.id=(
          SELECT p.id FROM accounting_periods p
          WHERE p.tenant_id=source_period.tenant_id AND p.status='OPEN'
            AND p.start_date>=source_period.end_date
          ORDER BY p.start_date LIMIT 1
        ))
      )
      AND date(NEW.created_at_utc,'+3 hours')>=date(target_period.start_date)
      AND date(NEW.created_at_utc,'+3 hours')<date(target_period.end_date)
  )
    THEN RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATION_REVERSAL_INVALID_OR_STALE') END;
END;

CREATE TRIGGER trg_customer_credit_allocation_requires_valid_source_v6
BEFORE INSERT ON customer_credit_allocations
WHEN NEW.reversal_of_id IS NULL
 AND NOT EXISTS (
   SELECT 1 FROM customer_credit_ledger c
   WHERE c.tenant_id=NEW.tenant_id AND c.id=NEW.credit_entry_id
     AND c.customer_id=NEW.customer_id AND c.store_id=(SELECT s.store_id FROM sales s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sale_id)
     AND c.amount_delta_mga>0 AND c.status='POSTED'
     AND c.entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED','REVERSAL')
 )
BEGIN SELECT RAISE(ABORT, 'CREDIT_ALLOCATION_REQUIRES_POSITIVE_SOURCE_CREDIT'); END;

CREATE TRIGGER trg_customer_credit_reversal_ledger_guard
BEFORE INSERT ON customer_credit_ledger
WHEN NEW.entry_type='REVERSAL'
 AND (
   NEW.amount_delta_mga<=0
   OR NEW.source_type<>'CUSTOMER_CREDIT_ALLOCATION_REVERSAL'
   OR NEW.parent_credit_entry_id IS NULL
   OR NEW.accounting_period_id IS NULL
   OR NEW.device_id IS NULL
   OR NEW.idempotency_key IS NULL
   OR length(NEW.request_hash)<>32
   OR NOT EXISTS (
     SELECT 1 FROM customer_credit_allocations r
     JOIN customer_credit_allocations o
       ON o.tenant_id=r.tenant_id AND o.id=r.reversal_of_id
     WHERE r.tenant_id=NEW.tenant_id AND r.id=NEW.source_id
       AND r.reversal_of_id IS NOT NULL AND r.status='POSTED'
       AND r.customer_id=NEW.customer_id AND r.credit_entry_id=NEW.parent_credit_entry_id
       AND r.amount_mga=NEW.amount_delta_mga AND r.store_id IS NULL
   )
 )
BEGIN SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_REVERSAL_LEDGER_SOURCE_INVALID'); END;

-- State rows are projections of successful posted reversals; only a matching reversal
-- insert may advance the version and cumulative amount.
CREATE TRIGGER trg_debt_allocation_create_reversal_state
AFTER INSERT ON debt_payment_allocations
WHEN NEW.reversal_of_id IS NULL AND NEW.status='POSTED'
BEGIN
  INSERT INTO allocation_reversal_state
    (tenant_id,allocation_type,original_allocation_id,original_amount_mga,reversed_amount_mga,version,updated_at_utc)
  VALUES(NEW.tenant_id,'DEBT_PAYMENT',NEW.id,NEW.amount_mga,0,1,NEW.created_at_utc);
END;

CREATE TRIGGER trg_customer_credit_allocation_create_reversal_state
AFTER INSERT ON customer_credit_allocations
WHEN NEW.reversal_of_id IS NULL AND NEW.status='POSTED'
BEGIN
  INSERT INTO allocation_reversal_state
    (tenant_id,allocation_type,original_allocation_id,original_amount_mga,reversed_amount_mga,version,updated_at_utc)
  VALUES(NEW.tenant_id,'CUSTOMER_CREDIT',NEW.id,NEW.amount_mga,0,1,NEW.created_at_utc);
END;

CREATE TRIGGER trg_allocation_reversal_state_update_guard
BEFORE UPDATE ON allocation_reversal_state
WHEN NEW.version<>OLD.version+1
  OR NEW.reversed_amount_mga<=OLD.reversed_amount_mga
  OR NEW.reversed_amount_mga>NEW.original_amount_mga
  OR NOT (
    EXISTS (SELECT 1 FROM debt_payment_allocations r
      WHERE NEW.allocation_type='DEBT_PAYMENT' AND r.tenant_id=NEW.tenant_id
        AND r.reversal_of_id=NEW.original_allocation_id AND r.status='POSTED'
        AND r.amount_mga=NEW.reversed_amount_mga-OLD.reversed_amount_mga)
    OR EXISTS (SELECT 1 FROM customer_credit_allocations r
      WHERE NEW.allocation_type='CUSTOMER_CREDIT' AND r.tenant_id=NEW.tenant_id
        AND r.reversal_of_id=NEW.original_allocation_id AND r.status='POSTED'
        AND r.amount_mga=NEW.reversed_amount_mga-OLD.reversed_amount_mga)
  )
BEGIN SELECT RAISE(ABORT, 'REVERSAL_STATE_MUST_ADVANCE_WITH_POSTED_REVERSAL'); END;

CREATE TRIGGER trg_allocation_reversal_state_no_delete
BEFORE DELETE ON allocation_reversal_state
BEGIN SELECT RAISE(ABORT, 'ALLOCATION_REVERSAL_STATE_CANNOT_BE_DELETED'); END;

CREATE TRIGGER trg_debt_allocation_apply_reversal
AFTER INSERT ON debt_payment_allocations
WHEN NEW.reversal_of_id IS NOT NULL AND NEW.status='POSTED'
BEGIN
  UPDATE allocation_reversal_state
  SET reversed_amount_mga=reversed_amount_mga+NEW.amount_mga,
      version=version+1,
      updated_at_utc=NEW.created_at_utc
  WHERE tenant_id=NEW.tenant_id AND allocation_type='DEBT_PAYMENT'
    AND original_allocation_id=NEW.reversal_of_id;

  INSERT INTO payment_availability_ledger
    (id,tenant_id,payment_id,customer_id,store_id,reversal_allocation_id,parent_availability_entry_id,
     amount_mga,accounting_period_id,reason,created_at_utc,created_by,device_id,idempotency_key,request_hash)
  SELECT lower(hex(randomblob(16))),NEW.tenant_id,NEW.payment_id,NEW.customer_id,p.store_id,NEW.id,
     NEW.payment_availability_entry_id,NEW.amount_mga,NEW.reversal_period_id,NEW.reversal_reason,
     NEW.created_at_utc,NEW.created_by,NEW.reversal_device_id,NEW.reversal_idempotency_key,NEW.reversal_request_hash
  FROM debt_payments p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.payment_id;
END;

CREATE TRIGGER trg_customer_credit_allocation_apply_reversal
AFTER INSERT ON customer_credit_allocations
WHEN NEW.reversal_of_id IS NOT NULL AND NEW.status='POSTED'
BEGIN
  UPDATE allocation_reversal_state
  SET reversed_amount_mga=reversed_amount_mga+NEW.amount_mga,
      version=version+1,
      updated_at_utc=NEW.created_at_utc
  WHERE tenant_id=NEW.tenant_id AND allocation_type='CUSTOMER_CREDIT'
    AND original_allocation_id=NEW.reversal_of_id;

  INSERT INTO customer_credit_ledger
    (id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,
     created_at_utc,created_by,parent_credit_entry_id,accounting_period_id,device_id,idempotency_key,request_hash)
  SELECT lower(hex(randomblob(16))),NEW.tenant_id,NEW.customer_id,s.store_id,'REVERSAL',NEW.amount_mga,
     'CUSTOMER_CREDIT_ALLOCATION_REVERSAL',NEW.id,'POSTED',NEW.created_at_utc,NEW.created_by,
     NEW.credit_entry_id,NEW.reversal_period_id,NEW.reversal_device_id,
     NEW.reversal_idempotency_key,NEW.reversal_request_hash
  FROM sales s WHERE s.tenant_id=NEW.tenant_id AND s.id=NEW.sale_id;
END;

CREATE TRIGGER trg_payment_availability_no_update
BEFORE UPDATE ON payment_availability_ledger
BEGIN SELECT RAISE(ABORT, 'PAYMENT_AVAILABILITY_LEDGER_APPEND_ONLY'); END;

CREATE TRIGGER trg_payment_availability_no_delete
BEFORE DELETE ON payment_availability_ledger
BEGIN SELECT RAISE(ABORT, 'PAYMENT_AVAILABILITY_LEDGER_APPEND_ONLY'); END;

CREATE TRIGGER trg_debt_allocation_no_direct_reversed_status
BEFORE INSERT ON debt_payment_allocations
WHEN NEW.status<>'POSTED'
BEGIN SELECT RAISE(ABORT, 'USE_APPEND_ONLY_REVERSAL_RECORD'); END;

CREATE TRIGGER trg_customer_credit_allocation_no_direct_reversed_status
BEFORE INSERT ON customer_credit_allocations
WHEN NEW.status<>'POSTED'
BEGIN SELECT RAISE(ABORT, 'USE_APPEND_ONLY_REVERSAL_RECORD'); END;
