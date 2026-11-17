import pathlib
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATIONS = [
    "0001_initial_schema.sql",
    "0002_posted_document_immutability.sql",
    "0003_financial_allocation_guards.sql",
    "0004_stock_ledger_projection_atomicity.sql",
    "0005_audit_chain_cursor_atomicity.sql",
    "0006_financial_allocation_reversal.sql",
]
TS = "2026-11-17T00:00:00Z"
DEC_TS = "2026-12-02T00:00:00Z"
HASH = bytes([7]) * 32


class FinancialAllocationReversalMigrationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        for name in MIGRATIONS:
            self.db.executescript((ROOT / "migrations" / name).read_text(encoding="utf-8"))

    def tearDown(self):
        self.db.close()

    def must_fail(self, sql, params=()):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(sql, params)

    def add_base(self):
        self.db.execute(
            "INSERT INTO tenants(id,code,display_name,status,created_at_utc,updated_at_utc) VALUES('t1','T1','Tenant','ACTIVE',?,?)",
            (TS, TS),
        )
        self.db.execute(
            "INSERT INTO stores(id,tenant_id,code,name,status,created_at_utc,updated_at_utc) VALUES('a1','t1','A1','Store A1','ACTIVE',?,?)",
            (TS, TS),
        )
        for account_id, username, role in [
            ("admin1", "admin1", "ADMIN"),
            ("gerant1", "gerant1", "GERANT"),
        ]:
            self.db.execute(
                "INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?, 'ACTIVE',?,?)",
                (account_id, "t1", username, role, TS, TS),
            )
            self.db.execute(
                "INSERT INTO account_store_access(tenant_id,account_id,store_id,granted_at_utc,granted_by) VALUES('t1',?,'a1',?,'admin1')",
                (account_id, TS),
            )
        for device_id, account_id in [("device-admin", "admin1"), ("device-gerant", "gerant1")]:
            self.db.execute(
                "INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc) VALUES(?,'t1',?,?,'ACTIVE',?)",
                (device_id, account_id, bytes([1 if account_id == "admin1" else 2]) * 32, TS),
            )
        self.db.execute(
            "INSERT INTO customers(id,tenant_id,display_name,status,created_at_utc,updated_at_utc) VALUES('c1','t1','Customer','ACTIVE',?,?)",
            (TS, TS),
        )
        self.db.execute(
            "INSERT INTO products(id,tenant_id,code,name,unit,active,created_at_utc,updated_at_utc) VALUES('p1','t1','P1','Product','piece',1,?,?)",
            (TS, TS),
        )
        for sale_id in ("invoice1", "invoice2"):
            self.db.execute(
                """INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
                   VALUES(?,'t1','a1','CREDIT','c1',?,'POSTED',1000,?,'admin1')""",
                (sale_id, sale_id, TS),
            )
        self.db.execute(
            """INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES('pay1','t1','a1','c1',1000,'POSTED',?,'admin1','payment-idem')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO customer_credit_ledger(id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,created_at_utc,created_by)
               VALUES('credit1','t1','c1','a1','CREDIT_NOTE_ISSUED',800,'CREDIT_NOTE','credit-source-1','POSTED',?,'admin1')""",
            (TS,),
        )

    def add_period(self, period_id="period-nov", status="OPEN", start="2026-11-01", end="2026-12-01"):
        closed = status == "CLOSED"
        self.db.execute(
            """INSERT INTO accounting_periods
               (id,tenant_id,period_code,start_date,end_date,status,created_at_utc,created_by,closed_at_utc,closed_by)
               VALUES(?,'t1',?,?,?,?,?,'admin1',?,?)""",
            (period_id, period_id, start, end, status, TS, TS if closed else None, "admin1" if closed else None),
        )

    def add_debt_allocation(self, allocation_id, sale_id="invoice1", amount=500, payment_id="pay1", availability_id=None):
        self.db.execute(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,payment_availability_entry_id)
               VALUES(?,'t1',?,?, 'c1',?,'POSTED',?,'admin1',?)""",
            (allocation_id, payment_id, sale_id, amount, TS, availability_id),
        )

    def add_credit_allocation(self, allocation_id, sale_id="invoice1", amount=500, credit_entry_id="credit1"):
        self.db.execute(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES(?,'t1',?,?, 'c1',?,'POSTED',?,'admin1')""",
            (allocation_id, credit_entry_id, sale_id, amount, TS),
        )

    def reverse_debt(self, reversal_id, original_id, amount, expected_version, period_id="period-nov",
                     created_at=TS, device_id="device-admin", idem=None, sale_id="invoice1"):
        self.db.execute(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version,payment_availability_entry_id)
               SELECT ?,o.tenant_id,o.payment_id,?,o.customer_id,?,'POSTED',?,'admin1',
                      o.id,'Approved correction',?,?,?, ?,?,o.payment_availability_entry_id
               FROM debt_payment_allocations o
               WHERE o.tenant_id='t1' AND o.id=?""",
            (reversal_id, sale_id, amount, created_at, period_id, device_id, idem or reversal_id, HASH,
             expected_version, original_id),
        )

    def reverse_credit(self, reversal_id, original_id, amount, expected_version, period_id="period-nov",
                       created_at=TS, device_id="device-admin", idem=None, sale_id="invoice1"):
        self.db.execute(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT ?,o.tenant_id,o.credit_entry_id,?,o.customer_id,?,'POSTED',?,'admin1',
                      o.id,'Approved correction',?,?,?, ?,?
               FROM customer_credit_allocations o
               WHERE o.tenant_id='t1' AND o.id=?""",
            (reversal_id, sale_id, amount, created_at, period_id, device_id, idem or reversal_id, HASH,
             expected_version, original_id),
        )

    def test_multiple_partial_reversals_exact_cap_and_expected_version(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=1000)
        self.reverse_debt("debt-rev-1", "debt-original", 300, 1, idem="rev-1")
        self.reverse_debt("debt-rev-2", "debt-original", 400, 2, idem="rev-2")
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'too-much',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,301,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','rev-too-much',?,3
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )
        self.reverse_debt("debt-rev-3", "debt-original", 300, 3, idem="rev-3")
        state = self.db.execute(
            "SELECT original_amount_mga,reversed_amount_mga,version FROM allocation_reversal_state WHERE tenant_id='t1' AND allocation_type='DEBT_PAYMENT' AND original_allocation_id='debt-original'"
        ).fetchone()
        self.assertEqual(state, (1000, 1000, 4))
        self.assertEqual(
            self.db.execute("SELECT COUNT(*) FROM payment_availability_ledger WHERE tenant_id='t1' AND reversal_allocation_id IN ('debt-rev-1','debt-rev-2','debt-rev-3')").fetchone()[0],
            3,
        )

    def test_restored_payment_availability_is_a_distinct_single_use_source(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=700)
        self.reverse_debt("debt-rev-1", "debt-original", 250, 1, idem="rev-1")
        entry_id = self.db.execute(
            "SELECT id FROM payment_availability_ledger WHERE tenant_id='t1' AND reversal_allocation_id='debt-rev-1'"
        ).fetchone()[0]
        self.add_debt_allocation("reallocated-250", sale_id="invoice2", amount=250, availability_id=entry_id)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,payment_availability_entry_id)
               VALUES('reallocated-over','t1','pay1','invoice2','c1',1,'POSTED',?,'admin1',?)""",
            (TS, entry_id),
        )
        remaining = self.db.execute(
            """SELECT v.amount_mga-COALESCE(SUM(a.amount_mga),0)
               FROM payment_availability_ledger v LEFT JOIN debt_payment_allocations a
                 ON a.tenant_id=v.tenant_id AND a.payment_availability_entry_id=v.id
                 AND a.reversal_of_id IS NULL AND a.status='POSTED'
               WHERE v.tenant_id='t1' AND v.id=? GROUP BY v.id""",
            (entry_id,),
        ).fetchone()[0]
        self.assertEqual(remaining, 0)

    def test_customer_credit_reversal_creates_source_linked_credit_once(self):
        self.add_base()
        self.add_period()
        self.add_credit_allocation("credit-original", amount=600)
        self.reverse_credit("credit-rev-1", "credit-original", 250, 1, idem="credit-rev-1")
        restored = self.db.execute(
            """SELECT id,amount_delta_mga,parent_credit_entry_id,source_id
               FROM customer_credit_ledger
               WHERE tenant_id='t1' AND entry_type='REVERSAL'
                 AND source_type='CUSTOMER_CREDIT_ALLOCATION_REVERSAL'"""
        ).fetchone()
        self.assertEqual(restored[1:], (250, "credit1", "credit-rev-1"))
        self.add_credit_allocation("restored-credit-200", sale_id="invoice2", amount=200, credit_entry_id=restored[0])
        self.must_fail(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('restored-credit-over','t1',?,'invoice2','c1',51,'POSTED',?,'admin1')""",
            (restored[0], TS),
        )

    def test_invoice_outstanding_uses_net_allocations_after_reversal(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=400)
        self.reverse_debt("debt-rev-1", "debt-original", 150, 1, idem="rev-1")
        self.db.execute(
            """INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES('pay2','t1','a1','c1',1000,'POSTED',?,'admin1','payment-idem-2')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('over-invoice','t1','pay2','invoice1','c1',751,'POSTED',?,'admin1')""",
            (TS,),
        )
        self.add_debt_allocation("exact-outstanding", amount=750, payment_id="pay2")
        self.assertEqual(
            self.db.execute(
                """SELECT total_mga
                   - COALESCE((SELECT SUM(total_mga) FROM credit_notes WHERE tenant_id='t1' AND original_sale_id='invoice1' AND status='POSTED'),0)
                   - COALESCE((SELECT SUM(CASE WHEN reversal_of_id IS NULL THEN amount_mga ELSE -amount_mga END) FROM debt_payment_allocations WHERE tenant_id='t1' AND sale_id='invoice1' AND status='POSTED'),0)
                   - COALESCE((SELECT SUM(CASE WHEN reversal_of_id IS NULL THEN amount_mga ELSE -amount_mga END) FROM customer_credit_allocations WHERE tenant_id='t1' AND sale_id='invoice1' AND status='POSTED'),0)
                   FROM sales WHERE tenant_id='t1' AND id='invoice1'"""
            ).fetchone()[0],
            0,
        )

    def test_closed_period_reversal_posts_in_next_open_period_only(self):
        self.add_base()
        self.add_period("period-nov", "CLOSED")
        self.add_period("period-dec", "OPEN", "2026-12-01", "2027-01-01")
        self.add_debt_allocation("debt-original", amount=500)
        self.reverse_debt("debt-rev-dec", "debt-original", 100, 1, period_id="period-dec", created_at=DEC_TS, idem="rev-dec")
        self.must_fail("UPDATE accounting_periods SET status='OPEN',closed_at_utc=NULL,closed_by=NULL,version=3 WHERE id='period-nov'")
        self.assertEqual(
            self.db.execute("SELECT accounting_period_id FROM payment_availability_ledger WHERE reversal_allocation_id='debt-rev-dec'").fetchone()[0],
            "period-dec",
        )

    def test_closed_period_without_next_open_period_rejects_reversal(self):
        self.add_base()
        self.add_period("period-nov", "CLOSED")
        self.add_debt_allocation("debt-original", amount=500)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-none',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,100,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','rev-none',?,1
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (DEC_TS, HASH),
        )

    def test_gerant_cannot_reverse_even_with_assigned_store(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=500)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-gerant',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,100,'POSTED',?,'gerant1',
                 o.id,'Approved correction','period-nov','device-gerant','rev-gerant',?,1
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )

    def test_stale_expected_version_and_duplicate_idempotency_are_rejected(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=500)
        self.reverse_debt("debt-rev-1", "debt-original", 100, 1, idem="same-key")
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-stale',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,100,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','stale-key',?,1
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-duplicate-key',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,100,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','same-key',?,2
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )

    def test_original_and_reversal_rows_are_append_only(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=500)
        self.reverse_debt("debt-rev-1", "debt-original", 100, 1, idem="rev-1")
        self.must_fail("UPDATE debt_payment_allocations SET amount_mga=1 WHERE tenant_id='t1' AND id='debt-original'")
        self.must_fail("DELETE FROM debt_payment_allocations WHERE tenant_id='t1' AND id='debt-original'")
        self.must_fail("UPDATE debt_payment_allocations SET amount_mga=1 WHERE tenant_id='t1' AND id='debt-rev-1'")
        self.must_fail("DELETE FROM debt_payment_allocations WHERE tenant_id='t1' AND id='debt-rev-1'")

    def test_missing_period_and_cross_invoice_reversal_are_rejected(self):
        self.add_base()
        self.add_debt_allocation("debt-original", amount=500)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-no-period',o.tenant_id,o.payment_id,o.sale_id,o.customer_id,100,'POSTED',?,'admin1',
                 o.id,'Approved correction','missing-period','device-admin','rev-no-period',?,1
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )
        self.add_period()
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'debt-rev-wrong-invoice',o.tenant_id,o.payment_id,'invoice2',o.customer_id,100,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','rev-wrong-invoice',?,1
               FROM debt_payment_allocations o WHERE o.id='debt-original'""",
            (TS, HASH),
        )



    def test_payment_availability_ledger_cannot_be_forged_without_reversal(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=500)
        self.must_fail(
            """INSERT INTO payment_availability_ledger
               (id,tenant_id,payment_id,customer_id,store_id,reversal_allocation_id,parent_availability_entry_id,
                amount_mga,accounting_period_id,reason,created_at_utc,created_by,device_id,idempotency_key,request_hash)
               VALUES('forged-entry','t1','pay1','c1','a1','debt-original',NULL,100,'period-nov',
                'forged credit',?,'admin1','device-admin','forged-key',?)""",
            (TS, HASH),
        )

    def test_credit_note_posting_cannot_overdraw_net_invoice_balance(self):
        self.add_base()
        self.add_period()
        self.add_debt_allocation("debt-original", amount=600)
        self.db.execute(
            """INSERT INTO credit_notes
               (id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn-over','t1','a1','invoice1','CN-OVER','DRAFT',401,?,'admin1')""",
            (TS,),
        )
        self.must_fail("UPDATE credit_notes SET status='POSTED' WHERE tenant_id='t1' AND id='cn-over'")

    def test_forward_migration_backfills_reversal_state_for_existing_allocations(self):
        legacy = sqlite3.connect(":memory:")
        legacy.execute("PRAGMA foreign_keys = ON")
        current = self.db
        try:
            for name in MIGRATIONS[:-1]:
                legacy.executescript((ROOT / "migrations" / name).read_text(encoding="utf-8"))
            self.db = legacy
            self.add_base()
            legacy.execute(
                """INSERT INTO debt_payment_allocations
                   (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
                   VALUES('legacy-debt','t1','pay1','invoice1','c1',300,'POSTED',?,'admin1')""",
                (TS,),
            )
            legacy.execute(
                """INSERT INTO customer_credit_allocations
                   (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
                   VALUES('legacy-credit','t1','credit1','invoice1','c1',200,'POSTED',?,'admin1')""",
                (TS,),
            )
            legacy.executescript((ROOT / "migrations" / "0006_financial_allocation_reversal.sql").read_text(encoding="utf-8"))
            rows = legacy.execute(
                "SELECT allocation_type,original_allocation_id,original_amount_mga,reversed_amount_mga,version FROM allocation_reversal_state ORDER BY allocation_type"
            ).fetchall()
            self.assertEqual(rows, [
                ("CUSTOMER_CREDIT", "legacy-credit", 200, 0, 1),
                ("DEBT_PAYMENT", "legacy-debt", 300, 0, 1),
            ])
            self.assertEqual(legacy.execute("PRAGMA foreign_key_check").fetchall(), [])
        finally:
            self.db = current
            legacy.close()


    def test_customer_credit_allows_multiple_partial_reversals_with_cumulative_cap(self):
        self.add_base()
        self.add_period()
        self.add_credit_allocation("credit-original", amount=600)
        self.reverse_credit("credit-rev-1", "credit-original", 200, 1, idem="credit-rev-1")
        self.reverse_credit("credit-rev-2", "credit-original", 300, 2, idem="credit-rev-2")
        self.must_fail(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,
                reversal_of_id,reversal_reason,reversal_period_id,reversal_device_id,reversal_idempotency_key,
                reversal_request_hash,reversal_expected_version)
               SELECT 'credit-rev-too-much',o.tenant_id,o.credit_entry_id,o.sale_id,o.customer_id,101,'POSTED',?,'admin1',
                 o.id,'Approved correction','period-nov','device-admin','credit-rev-too-much',?,3
               FROM customer_credit_allocations o WHERE o.id='credit-original'""",
            (TS, HASH),
        )
        self.reverse_credit("credit-rev-3", "credit-original", 100, 3, idem="credit-rev-3")
        state = self.db.execute(
            "SELECT original_amount_mga,reversed_amount_mga,version FROM allocation_reversal_state WHERE tenant_id='t1' AND allocation_type='CUSTOMER_CREDIT' AND original_allocation_id='credit-original'"
        ).fetchone()
        self.assertEqual(state, (600, 600, 4))
        self.assertEqual(
            self.db.execute("SELECT SUM(amount_delta_mga) FROM customer_credit_ledger WHERE tenant_id='t1' AND entry_type='REVERSAL'").fetchone()[0],
            600,
        )

    def test_accounting_periods_cannot_overlap(self):
        self.add_base()
        self.add_period()
        self.must_fail(
            """INSERT INTO accounting_periods
               (id,tenant_id,period_code,start_date,end_date,status,created_at_utc,created_by)
               VALUES('period-overlap','t1','period-overlap','2026-11-15','2026-12-15','OPEN',?,'admin1')""",
            (TS,),
        )

    def test_preflight_rejects_legacy_untyped_reversal_history(self):
        legacy = sqlite3.connect(":memory:")
        legacy.execute("PRAGMA foreign_keys = ON")
        current = self.db
        try:
            for name in MIGRATIONS[:-1]:
                legacy.executescript((ROOT / "migrations" / name).read_text(encoding="utf-8"))
            self.db = legacy
            self.add_base()
            legacy.execute(
                """INSERT INTO debt_payment_allocations
                   (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
                   VALUES('legacy-original','t1','pay1','invoice1','c1',500,'POSTED',?,'admin1')""",
                (TS,),
            )
            legacy.execute(
                """INSERT INTO debt_payment_allocations
                   (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by,reversal_of_id)
                   VALUES('legacy-reversal','t1','pay1','invoice1','c1',100,'REVERSED',?,'admin1','legacy-original')""",
                (TS,),
            )
            with self.assertRaises(sqlite3.IntegrityError):
                legacy.executescript((ROOT / "migrations" / "0006_financial_allocation_reversal.sql").read_text(encoding="utf-8"))
        finally:
            self.db = current
            legacy.close()

if __name__ == "__main__":
    unittest.main()
