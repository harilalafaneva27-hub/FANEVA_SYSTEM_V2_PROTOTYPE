-- FANEVA SYSTEM V2 — P0 financial aggregate guards (migration 0003)
-- Database-side guards are the final invariant boundary. Worker must still perform
-- authorization and execute each business mutation in one D1 transaction.
-- Append-only reversal rows are not netted here; reversal semantics need their
-- own explicitly typed, one-time reversal protocol before production use.

-- 1) A payment cannot be allocated beyond its posted payment amount.
CREATE TRIGGER trg_debt_allocation_payment_cap
BEFORE INSERT ON debt_payment_allocations
WHEN NEW.status='POSTED'
 AND NEW.amount_mga > (
   SELECT p.amount_mga - COALESCE((
     SELECT SUM(a.amount_mga)
     FROM debt_payment_allocations a
     WHERE a.tenant_id=NEW.tenant_id
       AND a.payment_id=NEW.payment_id
       AND a.status='POSTED'
   ),0)
   FROM debt_payments p
   WHERE p.tenant_id=NEW.tenant_id
     AND p.id=NEW.payment_id
     AND p.status='POSTED'
 )
BEGIN
  SELECT RAISE(ABORT, 'PAYMENT_ALLOCATION_EXCEEDS_PAYMENT_AMOUNT');
END;

-- 2) Debt payment allocations cannot exceed the invoice's collectible balance:
-- invoice total - posted Credit Notes - posted debt allocations - posted credit applications.
CREATE TRIGGER trg_debt_allocation_invoice_outstanding_cap
BEFORE INSERT ON debt_payment_allocations
WHEN NEW.status='POSTED'
 AND NEW.amount_mga > (
   SELECT s.total_mga
     - COALESCE((
       SELECT SUM(cn.total_mga)
       FROM credit_notes cn
       WHERE cn.tenant_id=NEW.tenant_id
         AND cn.original_sale_id=NEW.sale_id
         AND cn.status='POSTED'
     ),0)
     - COALESCE((
       SELECT SUM(a.amount_mga)
       FROM debt_payment_allocations a
       WHERE a.tenant_id=NEW.tenant_id
         AND a.sale_id=NEW.sale_id
         AND a.status='POSTED'
     ),0)
     - COALESCE((
       SELECT SUM(a.amount_mga)
       FROM customer_credit_allocations a
       WHERE a.tenant_id=NEW.tenant_id
         AND a.sale_id=NEW.sale_id
         AND a.status='POSTED'
     ),0)
   FROM sales s
   WHERE s.tenant_id=NEW.tenant_id
     AND s.id=NEW.sale_id
     AND s.sale_type='CREDIT'
     AND s.status='POSTED'
 )
BEGIN
  SELECT RAISE(ABORT, 'DEBT_ALLOCATION_EXCEEDS_INVOICE_OUTSTANDING');
END;

-- 3) Customer-credit applications cannot consume more than the source credit entry.
CREATE TRIGGER trg_customer_credit_source_cap
BEFORE INSERT ON customer_credit_allocations
WHEN NEW.status='POSTED'
 AND NEW.amount_mga > (
   SELECT c.amount_delta_mga - COALESCE((
     SELECT SUM(a.amount_mga)
     FROM customer_credit_allocations a
     WHERE a.tenant_id=NEW.tenant_id
       AND a.credit_entry_id=NEW.credit_entry_id
       AND a.status='POSTED'
   ),0)
   FROM customer_credit_ledger c
   WHERE c.tenant_id=NEW.tenant_id
     AND c.id=NEW.credit_entry_id
     AND c.amount_delta_mga > 0
     AND c.status='POSTED'
     AND c.entry_type IN ('CREDIT_NOTE_ISSUED','PAYMENT_UNAPPLIED')
 )
BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATION_EXCEEDS_SOURCE_CREDIT');
END;

-- 4) Customer-credit applications share the same invoice outstanding cap as debt payments.
CREATE TRIGGER trg_customer_credit_invoice_outstanding_cap
BEFORE INSERT ON customer_credit_allocations
WHEN NEW.status='POSTED'
 AND NEW.amount_mga > (
   SELECT s.total_mga
     - COALESCE((
       SELECT SUM(cn.total_mga)
       FROM credit_notes cn
       WHERE cn.tenant_id=NEW.tenant_id
         AND cn.original_sale_id=NEW.sale_id
         AND cn.status='POSTED'
     ),0)
     - COALESCE((
       SELECT SUM(a.amount_mga)
       FROM debt_payment_allocations a
       WHERE a.tenant_id=NEW.tenant_id
         AND a.sale_id=NEW.sale_id
         AND a.status='POSTED'
     ),0)
     - COALESCE((
       SELECT SUM(a.amount_mga)
       FROM customer_credit_allocations a
       WHERE a.tenant_id=NEW.tenant_id
         AND a.sale_id=NEW.sale_id
         AND a.status='POSTED'
     ),0)
   FROM sales s
   WHERE s.tenant_id=NEW.tenant_id
     AND s.id=NEW.sale_id
     AND s.sale_type='CREDIT'
     AND s.status='POSTED'
 )
BEGIN
  SELECT RAISE(ABORT, 'CUSTOMER_CREDIT_ALLOCATION_EXCEEDS_INVOICE_OUTSTANDING');
END;

-- 5) Financial adjustment sources may only be attached while the adjustment is DRAFT,
-- and their cumulative allocation cannot exceed the adjustment amount.
CREATE TRIGGER trg_financial_adjustment_source_draft_only
BEFORE INSERT ON financial_adjustment_sources
WHEN NOT EXISTS (
  SELECT 1
  FROM financial_adjustments f
  WHERE f.tenant_id=NEW.tenant_id
    AND f.id=NEW.adjustment_id
    AND f.status='DRAFT'
)
BEGIN
  SELECT RAISE(ABORT, 'FINANCIAL_ADJUSTMENT_SOURCE_REQUIRES_DRAFT');
END;

CREATE TRIGGER trg_financial_adjustment_source_amount_cap
BEFORE INSERT ON financial_adjustment_sources
WHEN NEW.allocated_amount_mga > (
  SELECT f.amount_mga - COALESCE((
    SELECT SUM(s.allocated_amount_mga)
    FROM financial_adjustment_sources s
    WHERE s.tenant_id=NEW.tenant_id
      AND s.adjustment_id=NEW.adjustment_id
  ),0)
  FROM financial_adjustments f
  WHERE f.tenant_id=NEW.tenant_id
    AND f.id=NEW.adjustment_id
    AND f.status='DRAFT'
)
BEGIN
  SELECT RAISE(ABORT, 'FINANCIAL_ADJUSTMENT_SOURCES_EXCEED_ADJUSTMENT_AMOUNT');
END;
