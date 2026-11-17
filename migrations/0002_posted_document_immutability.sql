-- FANEVA SYSTEM V2 — P0 hardening migration 0002
-- Freeze posted business documents and their line items.
-- Corrections/reversals must be represented by separate append-only records.
-- Apply only after 0001 on an isolated V2 STAGING database.

-- Posted or reversed document headers are immutable.
CREATE TRIGGER trg_sales_posted_no_update
BEFORE UPDATE ON sales
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_SALE_IMMUTABLE'); END;
CREATE TRIGGER trg_sales_posted_no_delete
BEFORE DELETE ON sales
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_SALE_IMMUTABLE'); END;

CREATE TRIGGER trg_purchases_posted_no_update
BEFORE UPDATE ON purchases
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_PURCHASE_IMMUTABLE'); END;
CREATE TRIGGER trg_purchases_posted_no_delete
BEFORE DELETE ON purchases
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_PURCHASE_IMMUTABLE'); END;

CREATE TRIGGER trg_credit_notes_posted_no_update
BEFORE UPDATE ON credit_notes
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_CREDIT_NOTE_IMMUTABLE'); END;
CREATE TRIGGER trg_credit_notes_posted_no_delete
BEFORE DELETE ON credit_notes
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_CREDIT_NOTE_IMMUTABLE'); END;

CREATE TRIGGER trg_financial_adjustments_posted_no_update
BEFORE UPDATE ON financial_adjustments
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_FINANCIAL_ADJUSTMENT_IMMUTABLE'); END;
CREATE TRIGGER trg_financial_adjustments_posted_no_delete
BEFORE DELETE ON financial_adjustments
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_FINANCIAL_ADJUSTMENT_IMMUTABLE'); END;

CREATE TRIGGER trg_supplier_refunds_posted_no_update
BEFORE UPDATE ON supplier_refunds
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_SUPPLIER_REFUND_IMMUTABLE'); END;
CREATE TRIGGER trg_supplier_refunds_posted_no_delete
BEFORE DELETE ON supplier_refunds
WHEN OLD.status IN ('POSTED','REVERSED')
BEGIN SELECT RAISE(ABORT, 'POSTED_SUPPLIER_REFUND_IMMUTABLE'); END;

CREATE TRIGGER trg_debt_payments_no_update
BEFORE UPDATE ON debt_payments
BEGIN SELECT RAISE(ABORT, 'DEBT_PAYMENTS_APPEND_ONLY'); END;
CREATE TRIGGER trg_debt_payments_no_delete
BEFORE DELETE ON debt_payments
BEGIN SELECT RAISE(ABORT, 'DEBT_PAYMENTS_APPEND_ONLY'); END;

-- Child lines are immutable once their parent is posted or reversed.
CREATE TRIGGER trg_sale_lines_posted_parent_no_insert
BEFORE INSERT ON sale_lines
WHEN EXISTS (SELECT 1 FROM sales p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.sale_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_SALE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_sale_lines_posted_parent_no_update
BEFORE UPDATE ON sale_lines
WHEN EXISTS (SELECT 1 FROM sales p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.sale_id AND p.status IN ('POSTED','REVERSED'))
 OR EXISTS (SELECT 1 FROM sales p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.sale_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_SALE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_sale_lines_posted_parent_no_delete
BEFORE DELETE ON sale_lines
WHEN EXISTS (SELECT 1 FROM sales p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.sale_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_SALE_LINES_IMMUTABLE'); END;

CREATE TRIGGER trg_purchase_lines_posted_parent_no_insert
BEFORE INSERT ON purchase_lines
WHEN EXISTS (SELECT 1 FROM purchases p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.purchase_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_PURCHASE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_purchase_lines_posted_parent_no_update
BEFORE UPDATE ON purchase_lines
WHEN EXISTS (SELECT 1 FROM purchases p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.purchase_id AND p.status IN ('POSTED','REVERSED'))
 OR EXISTS (SELECT 1 FROM purchases p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.purchase_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_PURCHASE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_purchase_lines_posted_parent_no_delete
BEFORE DELETE ON purchase_lines
WHEN EXISTS (SELECT 1 FROM purchases p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.purchase_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_PURCHASE_LINES_IMMUTABLE'); END;

CREATE TRIGGER trg_credit_note_lines_posted_parent_no_insert
BEFORE INSERT ON credit_note_lines
WHEN EXISTS (SELECT 1 FROM credit_notes p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.credit_note_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_CREDIT_NOTE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_credit_note_lines_posted_parent_no_update
BEFORE UPDATE ON credit_note_lines
WHEN EXISTS (SELECT 1 FROM credit_notes p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.credit_note_id AND p.status IN ('POSTED','REVERSED'))
 OR EXISTS (SELECT 1 FROM credit_notes p WHERE p.tenant_id=NEW.tenant_id AND p.id=NEW.credit_note_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_CREDIT_NOTE_LINES_IMMUTABLE'); END;
CREATE TRIGGER trg_credit_note_lines_posted_parent_no_delete
BEFORE DELETE ON credit_note_lines
WHEN EXISTS (SELECT 1 FROM credit_notes p WHERE p.tenant_id=OLD.tenant_id AND p.id=OLD.credit_note_id AND p.status IN ('POSTED','REVERSED'))
BEGIN SELECT RAISE(ABORT, 'POSTED_CREDIT_NOTE_LINES_IMMUTABLE'); END;
