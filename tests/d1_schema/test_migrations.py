import pathlib
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "migrations" / "0001_initial_schema.sql"
HARDENING_MIGRATION = ROOT / "migrations" / "0002_posted_document_immutability.sql"
FINANCIAL_GUARDS_MIGRATION = ROOT / "migrations" / "0003_financial_allocation_guards.sql"
STOCK_ATOMICITY_MIGRATION = ROOT / "migrations" / "0004_stock_ledger_projection_atomicity.sql"
AUDIT_CURSOR_MIGRATION = ROOT / "migrations" / "0005_audit_chain_cursor_atomicity.sql"
MAX_MGA = 9_007_199_254_740_991
TS = "2026-11-17T00:00:00Z"


class D1SchemaMigrationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(MIGRATION.read_text(encoding="utf-8"))
        self.db.executescript(HARDENING_MIGRATION.read_text(encoding="utf-8"))
        self.db.executescript(FINANCIAL_GUARDS_MIGRATION.read_text(encoding="utf-8"))
        self.db.executescript(STOCK_ATOMICITY_MIGRATION.read_text(encoding="utf-8"))
        self.db.executescript(AUDIT_CURSOR_MIGRATION.read_text(encoding="utf-8"))

    def tearDown(self):
        self.db.close()

    def must_fail(self, sql, params=()):
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(sql, params)

    def add_tenant(self, tenant_id):
        self.db.execute(
            "INSERT INTO tenants(id,code,display_name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?)",
            (tenant_id, tenant_id.upper(), "Synthetic tenant", "ACTIVE", TS, TS),
        )

    def add_store(self, tenant_id, store_id):
        self.db.execute(
            "INSERT INTO stores(id,tenant_id,code,name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?,?)",
            (store_id, tenant_id, store_id.upper(), "Synthetic store", "ACTIVE", TS, TS),
        )

    def add_account(self, tenant_id, account_id, username):
        self.db.execute(
            "INSERT INTO accounts(id,tenant_id,username,role,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?,?)",
            (account_id, tenant_id, username, "ADMIN", "ACTIVE", TS, TS),
        )

    def add_customer(self, tenant_id, customer_id):
        self.db.execute(
            "INSERT INTO customers(id,tenant_id,display_name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?)",
            (customer_id, tenant_id, "Synthetic customer", "ACTIVE", TS, TS),
        )

    def add_product(self, tenant_id, product_id):
        self.db.execute(
            "INSERT INTO products(id,tenant_id,code,name,unit,active,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?,?,?)",
            (product_id, tenant_id, product_id.upper(), "Synthetic product", "piece", 1, TS, TS),
        )

    def add_base(self):
        self.add_tenant("t1")
        self.add_tenant("t2")
        self.add_store("t1", "a1")
        self.add_store("t2", "b1")
        self.add_account("t1", "admin1", "admin1")
        self.add_account("t2", "admin2", "admin2")
        self.add_customer("t1", "c1")
        self.add_customer("t1", "c2")
        self.add_customer("t2", "c-other")
        self.add_product("t1", "p1")
        self.db.execute(
            "INSERT INTO suppliers(id,tenant_id,display_name,status,created_at_utc,updated_at_utc) VALUES(?,?,?,?,?,?)",
            ("sup1", "t1", "Synthetic supplier", "ACTIVE", TS, TS),
        )

    def add_sale(self, sale_id, sale_type, customer_id, status="POSTED"):
        self.db.execute(
            """INSERT INTO sales
               (id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (sale_id, "t1", "a1", sale_type, customer_id, sale_id, status, 1000, TS, "admin1"),
        )

    def test_fresh_migration_creates_expected_tables_and_valid_foreign_keys(self):
        tables = {
            row[0] for row in self.db.execute(
                "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'"
            )
        }
        expected = {
            "tenants", "stores", "accounts", "account_store_access", "devices",
            "device_enrollment_challenges", "products", "customers", "suppliers",
            "stock_balances", "stock_movements", "purchases", "purchase_lines",
            "sales", "sale_lines", "credit_notes", "credit_note_lines",
            "financial_adjustments", "financial_adjustment_sources", "debt_payments",
            "debt_payment_allocations", "customer_credit_ledger", "customer_credit_allocations",
            "supplier_refunds", "mutation_receipts", "tenant_sync_state", "change_log",
            "device_cursors", "bootstrap_imports", "audit_events",
        }
        self.assertEqual(tables, expected)
        self.assertEqual(self.db.execute("PRAGMA foreign_key_check").fetchall(), [])

    def test_tenant_safe_foreign_keys_and_store_isolation(self):
        self.add_base()
        self.must_fail(
            """INSERT INTO stock_balances(tenant_id,store_id,product_id,quantity,updated_at_utc)
               VALUES('t1','b1','p1',5,?)""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES('bad','t1','a1','CREDIT','c-other','bad','POSTED',10,?,'admin1')""",
            (TS,),
        )

    def test_cash_and_credit_customer_invariant(self):
        self.add_base()
        self.add_sale("cash-no-customer", "CASH", None)
        self.add_sale("cash-with-customer", "CASH", "c1")
        self.add_sale("credit-with-customer", "CREDIT", "c1")
        self.must_fail(
            """INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES('credit-no-customer','t1','a1','CREDIT',NULL,'cnc','POSTED',10,?,'admin1')""",
            (TS,),
        )

    def test_mga_integer_bounds(self):
        self.add_base()
        self.must_fail(
            """INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES('too-large','t1','a1','CASH',NULL,'large','POSTED',?,?,'admin1')""",
            (MAX_MGA + 1, TS),
        )
        self.must_fail(
            """INSERT INTO sales(id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES('fraction','t1','a1','CASH',NULL,'fraction','POSTED',10.5,?,'admin1')""",
            (TS,),
        )

    def test_exactly_one_opening_movement_per_store_product(self):
        self.add_base()
        row = ("open1", "t1", "a1", "p1", "OPENING", 10, "BOOTSTRAP", "seed1", TS, "admin1")
        self.db.execute(
            """INSERT INTO stock_movements(id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES(?,?,?,?,?,?,?,?,?,?)""", row
        )
        duplicate = ("open2", "t1", "a1", "p1", "OPENING", 12, "BOOTSTRAP", "seed2", TS, "admin1")
        self.must_fail(
            """INSERT INTO stock_movements(id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES(?,?,?,?,?,?,?,?,?,?)""", duplicate
        )
        bad_sign = ("sale-bad", "t1", "a1", "p1", "SALE", 2, "SALE", "sale1", TS, "admin1")
        self.must_fail(
            """INSERT INTO stock_movements(id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES(?,?,?,?,?,?,?,?,?,?)""", bad_sign
        )

    def test_credit_note_lines_must_match_original_invoice_and_product(self):
        self.add_base()
        self.add_sale("sale1", "CREDIT", "c1", status="DRAFT")
        self.add_sale("sale2", "CREDIT", "c2", status="DRAFT")
        self.db.execute(
            """INSERT INTO sale_lines(id,tenant_id,sale_id,product_id,quantity,unit_price_mga,line_total_mga)
               VALUES('line1','t1','sale1','p1',2,500,1000)"""
        )
        self.db.execute("UPDATE sales SET status='POSTED' WHERE tenant_id='t1' AND id='sale1'")
        self.db.execute(
            """INSERT INTO credit_notes(id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn1','t1','a1','sale1','CN-1','DRAFT',500,?,'admin1')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cnl1','t1','cn1','sale1','line1','p1',1,500,500)"""
        )
        self.must_fail(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cnl2','t1','cn1','sale2','line1','p1',1,500,500)"""
        )

    def test_payment_allocation_customer_must_match_payment_and_invoice(self):
        self.add_base()
        self.add_sale("sale-c1", "CREDIT", "c1")
        self.add_sale("sale-c2", "CREDIT", "c2")
        self.db.execute(
            """INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES('pay1','t1','a1','c1',1000,'POSTED',?,'admin1','idem1')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('alloc1','t1','pay1','sale-c2','c2',500,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_append_only_evidence_is_not_cascade_deleted(self):
        self.add_base()
        self.add_sale("sale1", "CREDIT", "c1")
        self.must_fail("DELETE FROM tenants WHERE id='t1'")



    def test_audit_and_ledger_rows_are_append_only(self):
        self.add_base()
        self.db.execute(
            """INSERT INTO stock_movements(id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES('move1','t1','a1','p1','OPENING',5,'BOOTSTRAP','seed1',?,'admin1')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO audit_events(tenant_id,environment,sequence,id,actor_id,event_type,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
               VALUES('t1','STAGING',1,'audit1','admin1','GENESIS',?, ?, ?, 'SHA-256','FANEVA-AUDIT-V1',?)""",
            (bytes(32), bytes(32), bytes(32), TS),
        )
        self.db.execute(
            """INSERT INTO change_log(tenant_id,cursor,event_id,entity_type,entity_id,store_id,event_type,payload_json,created_at_utc)
               VALUES('t1',1,'event1','STOCK_MOVEMENT','move1','a1','CREATED','{}',?)""",
            (TS,),
        )
        self.must_fail("UPDATE audit_events SET event_type='TAMPERED' WHERE tenant_id='t1' AND sequence=1")
        self.must_fail("DELETE FROM audit_events WHERE tenant_id='t1' AND sequence=1")
        self.must_fail("UPDATE stock_movements SET quantity_delta=99 WHERE tenant_id='t1' AND id='move1'")
        self.must_fail("DELETE FROM stock_movements WHERE tenant_id='t1' AND id='move1'")
        self.must_fail("UPDATE change_log SET payload_json='{}' WHERE tenant_id='t1' AND cursor=1")
        self.must_fail("DELETE FROM change_log WHERE tenant_id='t1' AND cursor=1")

    def test_mutation_receipts_are_append_only(self):
        self.add_base()
        self.db.execute(
            """INSERT INTO devices(id,tenant_id,account_id,token_hash,status,created_at_utc)
               VALUES('device1','t1','admin1',?,'ACTIVE',?)""",
            (bytes(32), TS),
        )
        self.db.execute(
            """INSERT INTO mutation_receipts(id,tenant_id,device_id,idempotency_key,mutation_id,request_hash,outcome,response_json,created_at_utc)
               VALUES('receipt1','t1','device1','idem1','mutation1',?,'ACKNOWLEDGED','{}',?)""",
            (bytes(32), TS),
        )
        self.must_fail("UPDATE mutation_receipts SET outcome='REJECTED' WHERE tenant_id='t1' AND id='receipt1'")
        self.must_fail("DELETE FROM mutation_receipts WHERE tenant_id='t1' AND id='receipt1'")

    def test_debt_payment_cannot_allocate_to_cash_invoice(self):
        self.add_base()
        self.add_sale("cash-sale", "CASH", "c1")
        self.db.execute(
            """INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES('pay-cash','t1','a1','c1',1000,'POSTED',?,'admin1','idem-cash')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO debt_payment_allocations(id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('alloc-cash','t1','pay-cash','cash-sale','c1',500,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_customer_credit_cannot_be_applied_to_cash_invoice_or_negative_entry(self):
        self.add_base()
        self.add_sale("cash-sale", "CASH", "c1")
        self.db.execute(
            """INSERT INTO customer_credit_ledger(id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,created_at_utc,created_by)
               VALUES('credit1','t1','c1','a1','CREDIT_NOTE_ISSUED',500,'CREDIT_NOTE','cn1','POSTED',?,'admin1')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO customer_credit_allocations(id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('credit-alloc1','t1','credit1','cash-sale','c1',200,'POSTED',?,'admin1')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO customer_credit_ledger(id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,created_at_utc,created_by)
               VALUES('negative1','t1','c1','a1','APPLIED_TO_INVOICE',-100,'ALLOCATION','alloc0','POSTED',?,'admin1')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO customer_credit_allocations(id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('credit-alloc2','t1','negative1','cash-sale','c1',50,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_credit_note_price_and_cumulative_quantity_are_bounded(self):
        self.add_base()
        self.add_sale("sale1", "CREDIT", "c1", status="DRAFT")
        self.db.execute(
            """INSERT INTO sale_lines(id,tenant_id,sale_id,product_id,quantity,unit_price_mga,line_total_mga)
               VALUES('line1','t1','sale1','p1',2,500,1000)"""
        )
        self.db.execute("UPDATE sales SET status='POSTED' WHERE tenant_id='t1' AND id='sale1'")
        self.db.execute(
            """INSERT INTO credit_notes(id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn1','t1','a1','sale1','CN-1','DRAFT',500,?,'admin1')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cnl1','t1','cn1','sale1','line1','p1',1,500,500)"""
        )
        self.db.execute(
            """INSERT INTO credit_notes(id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn2','t1','a1','sale1','CN-2','DRAFT',1000,?,'admin1')""",
            (TS,),
        )
        self.must_fail(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cnl2','t1','cn2','sale1','line1','p1',2,500,1000)"""
        )
        self.must_fail(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cnl3','t1','cn2','sale1','line1','p1',1,501,501)"""
        )


    def test_posted_sales_and_lines_are_immutable(self):
        self.add_base()
        self.add_sale("sale-immutable", "CREDIT", "c1")
        self.must_fail("UPDATE sales SET total_mga=900 WHERE tenant_id='t1' AND id='sale-immutable'")
        self.must_fail("DELETE FROM sales WHERE tenant_id='t1' AND id='sale-immutable'")
        self.must_fail(
            """INSERT INTO sale_lines(id,tenant_id,sale_id,product_id,quantity,unit_price_mga,line_total_mga)
               VALUES('late-line','t1','sale-immutable','p1',1,10,10)"""
        )

    def test_posted_purchase_and_lines_are_immutable(self):
        self.add_base()
        self.db.execute(
            """INSERT INTO purchases(id,tenant_id,store_id,supplier_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES('purchase1','t1','a1','sup1','P-1','POSTED',100,?,'admin1')""", (TS,)
        )
        self.must_fail("UPDATE purchases SET total_mga=200 WHERE tenant_id='t1' AND id='purchase1'")
        self.must_fail("DELETE FROM purchases WHERE tenant_id='t1' AND id='purchase1'")
        self.must_fail(
            """INSERT INTO purchase_lines(id,tenant_id,purchase_id,product_id,quantity,unit_cost_mga,line_total_mga)
               VALUES('late-purchase-line','t1','purchase1','p1',1,100,100)"""
        )

    def test_posted_debt_payment_is_append_only(self):
        self.add_base()
        self.db.execute(
            """INSERT INTO debt_payments(id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES('pay-immutable','t1','a1','c1',100,'POSTED',?,'admin1','idem-immutable')""", (TS,)
        )
        self.must_fail("UPDATE debt_payments SET amount_mga=50 WHERE tenant_id='t1' AND id='pay-immutable'")
        self.must_fail("DELETE FROM debt_payments WHERE tenant_id='t1' AND id='pay-immutable'")

    def test_posted_credit_note_header_and_lines_are_immutable(self):
        self.add_base()
        self.add_sale("sale-cn-immutable", "CREDIT", "c1", status="DRAFT")
        self.db.execute(
            """INSERT INTO sale_lines(id,tenant_id,sale_id,product_id,quantity,unit_price_mga,line_total_mga)
               VALUES('sale-line-cn','t1','sale-cn-immutable','p1',2,50,100)"""
        )
        self.db.execute("UPDATE sales SET status='POSTED' WHERE tenant_id='t1' AND id='sale-cn-immutable'")
        self.db.execute(
            """INSERT INTO credit_notes(id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn-immutable','t1','a1','sale-cn-immutable','CN-IMM','DRAFT',50,?,'admin1')""", (TS,)
        )
        self.db.execute(
            """INSERT INTO credit_note_lines(id,tenant_id,credit_note_id,original_sale_id,original_sale_line_id,product_id,credited_quantity,original_unit_price_mga,line_total_mga)
               VALUES('cn-line-immutable','t1','cn-immutable','sale-cn-immutable','sale-line-cn','p1',1,50,50)"""
        )
        self.db.execute("UPDATE credit_notes SET status='POSTED' WHERE tenant_id='t1' AND id='cn-immutable'")
        self.must_fail("UPDATE credit_notes SET total_mga=1 WHERE tenant_id='t1' AND id='cn-immutable'")
        self.must_fail("DELETE FROM credit_notes WHERE tenant_id='t1' AND id='cn-immutable'")
        self.must_fail("UPDATE credit_note_lines SET credited_quantity=2 WHERE tenant_id='t1' AND id='cn-line-immutable'")
        self.must_fail("DELETE FROM credit_note_lines WHERE tenant_id='t1' AND id='cn-line-immutable'")


    def add_payment(self, payment_id, customer_id="c1", amount=1000):
        self.db.execute(
            """INSERT INTO debt_payments
               (id,tenant_id,store_id,customer_id,amount_mga,status,created_at_utc,created_by,idempotency_key)
               VALUES(?, 't1','a1', ?, ?, 'POSTED', ?, 'admin1', ?)""",
            (payment_id, customer_id, amount, TS, "idem-" + payment_id),
        )

    def add_credit_entry(self, entry_id, customer_id="c1", amount=500):
        self.db.execute(
            """INSERT INTO customer_credit_ledger
               (id,tenant_id,customer_id,store_id,entry_type,amount_delta_mga,source_type,source_id,status,created_at_utc,created_by)
               VALUES(?, 't1', ?, 'a1', 'CREDIT_NOTE_ISSUED', ?, 'CREDIT_NOTE', ?, 'POSTED', ?, 'admin1')""",
            (entry_id, customer_id, amount, "source-" + entry_id, TS),
        )

    def add_debt_allocation(self, allocation_id, payment_id, sale_id, amount, customer_id="c1"):
        self.db.execute(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES(?, 't1', ?, ?, ?, ?, 'POSTED', ?, 'admin1')""",
            (allocation_id, payment_id, sale_id, customer_id, amount, TS),
        )

    def add_credit_allocation(self, allocation_id, entry_id, sale_id, amount, customer_id="c1"):
        self.db.execute(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES(?, 't1', ?, ?, ?, ?, 'POSTED', ?, 'admin1')""",
            (allocation_id, entry_id, sale_id, customer_id, amount, TS),
        )

    def test_debt_allocations_cannot_exceed_payment_amount(self):
        self.add_base()
        self.add_sale("invoice1", "CREDIT", "c1")
        self.add_payment("payment1", amount=700)
        self.add_debt_allocation("alloc1", "payment1", "invoice1", 600)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('alloc2','t1','payment1','invoice1','c1',101,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_debt_allocations_cannot_exceed_invoice_outstanding(self):
        self.add_base()
        self.add_sale("invoice1", "CREDIT", "c1")
        self.add_payment("payment1", amount=1000)
        self.add_payment("payment2", amount=1000)
        self.add_debt_allocation("alloc1", "payment1", "invoice1", 700)
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('alloc2','t1','payment2','invoice1','c1',301,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_customer_credit_cannot_exceed_source_credit(self):
        self.add_base()
        self.add_sale("invoice1", "CREDIT", "c1")
        self.add_credit_entry("credit1", amount=500)
        self.add_credit_allocation("credit-alloc1", "credit1", "invoice1", 400)
        self.must_fail(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('credit-alloc2','t1','credit1','invoice1','c1',101,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_debt_and_customer_credit_share_invoice_outstanding_cap(self):
        self.add_base()
        self.add_sale("invoice1", "CREDIT", "c1")
        self.add_payment("payment1", amount=1000)
        self.add_credit_entry("credit1", amount=500)
        self.add_debt_allocation("alloc1", "payment1", "invoice1", 600)
        self.must_fail(
            """INSERT INTO customer_credit_allocations
               (id,tenant_id,credit_entry_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('credit-alloc1','t1','credit1','invoice1','c1',401,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_posted_credit_notes_reduce_invoice_outstanding(self):
        self.add_base()
        self.add_sale("invoice1", "CREDIT", "c1")
        self.add_payment("payment1", amount=1000)
        self.db.execute(
            """INSERT INTO credit_notes
               (id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn1','t1','a1','invoice1','CN-1','DRAFT',300,?,'admin1')""",
            (TS,),
        )
        self.db.execute("UPDATE credit_notes SET status='POSTED' WHERE tenant_id='t1' AND id='cn1'")
        self.must_fail(
            """INSERT INTO debt_payment_allocations
               (id,tenant_id,payment_id,sale_id,customer_id,amount_mga,status,created_at_utc,created_by)
               VALUES('alloc1','t1','payment1','invoice1','c1',701,'POSTED',?,'admin1')""",
            (TS,),
        )

    def test_financial_adjustment_sources_cannot_exceed_adjustment_amount(self):
        self.add_base()
        self.db.execute(
            """INSERT INTO financial_adjustments
               (id,tenant_id,store_id,customer_id,adjustment_type,amount_mga,status,created_at_utc,created_by)
               VALUES('adj1','t1','a1','c1','CORRECTION',500,'DRAFT',?,'admin1')""",
            (TS,),
        )
        self.db.execute(
            """INSERT INTO financial_adjustment_sources
               (id,tenant_id,adjustment_id,source_type,source_id,allocated_amount_mga)
               VALUES('src1','t1','adj1','INVOICE','invoice1',300)"""
        )
        self.must_fail(
            """INSERT INTO financial_adjustment_sources
               (id,tenant_id,adjustment_id,source_type,source_id,allocated_amount_mga)
               VALUES('src2','t1','adj1','PAYMENT','payment1',201)"""
        )
        self.db.execute("UPDATE financial_adjustments SET status='POSTED' WHERE tenant_id='t1' AND id='adj1'")
        self.must_fail(
            """INSERT INTO financial_adjustment_sources
               (id,tenant_id,adjustment_id,source_type,source_id,allocated_amount_mga)
               VALUES('src3','t1','adj1','INVOICE','invoice2',1)"""
        )


    def add_stock_movement(self, movement_id, movement_type, quantity_delta, source_id=None):
        self.db.execute(
            """INSERT INTO stock_movements
               (id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES(?, 't1','a1','p1', ?, ?, 'TEST', ?, ?, 'admin1')""",
            (movement_id, movement_type, quantity_delta, source_id or movement_id, TS),
        )

    def test_stock_movement_atomically_creates_and_updates_projection(self):
        self.add_base()
        self.add_stock_movement("opening-stock", "OPENING", 10)
        row = self.db.execute(
            "SELECT quantity,version,updated_at_utc FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        ).fetchone()
        self.assertEqual(row, (10, 1, TS))
        self.add_stock_movement("sale-stock", "SALE", -4)
        row = self.db.execute(
            "SELECT quantity,version,updated_at_utc FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        ).fetchone()
        self.assertEqual(row, (6, 2, TS))
        ledger_total = self.db.execute(
            "SELECT SUM(quantity_delta) FROM stock_movements WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        ).fetchone()[0]
        self.assertEqual(row[0], ledger_total)

    def test_stock_movement_rejects_negative_and_leaves_no_partial_rows(self):
        self.add_base()
        self.add_stock_movement("opening-stock", "OPENING", 3)
        self.must_fail(
            """INSERT INTO stock_movements
               (id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES('oversell','t1','a1','p1','SALE',-4,'SALE','sale-oversell',?,'admin1')""",
            (TS,),
        )
        self.assertEqual(
            self.db.execute("SELECT COUNT(*) FROM stock_movements WHERE tenant_id='t1' AND id='oversell'").fetchone()[0],
            0,
        )
        self.assertEqual(
            self.db.execute("SELECT quantity FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'").fetchone()[0],
            3,
        )

    def test_stock_balance_cannot_be_written_outside_movement_ledger(self):
        self.add_base()
        self.add_stock_movement("opening-stock", "OPENING", 10)
        self.must_fail(
            "UPDATE stock_balances SET quantity=99 WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        )
        self.must_fail(
            """INSERT INTO stock_balances(tenant_id,store_id,product_id,quantity,version,updated_at_utc)
               VALUES('t1','a1','p1',99,1,?)""",
            (TS,),
        )
        self.must_fail(
            "DELETE FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        )

    def test_stock_movement_and_projection_rollback_together(self):
        self.add_base()
        self.db.commit()
        self.db.execute("BEGIN")
        self.add_stock_movement("opening-rollback", "OPENING", 8)
        inside_tx = self.db.execute(
            "SELECT quantity FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        ).fetchone()
        self.assertEqual(inside_tx, (8,))
        self.db.rollback()
        movement_count = self.db.execute(
            "SELECT COUNT(*) FROM stock_movements WHERE tenant_id='t1' AND id='opening-rollback'"
        ).fetchone()[0]
        balance_count = self.db.execute(
            "SELECT COUNT(*) FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'"
        ).fetchone()[0]
        self.assertEqual(movement_count, 0)
        self.assertEqual(balance_count, 0)


    def add_audit_event(self, sequence, event_id, event_type, previous_hash, event_hash):
        self.db.execute(
            """INSERT INTO audit_events
               (tenant_id,environment,sequence,id,actor_id,event_type,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
               VALUES('t1','STAGING',?,?,'admin1',?,?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)""",
            (sequence, event_id, event_type, bytes([sequence % 251]) * 32, previous_hash, event_hash, TS),
        )

    def add_change_log(self, cursor, event_id):
        self.db.execute(
            """INSERT INTO change_log
               (tenant_id,cursor,event_id,entity_type,entity_id,store_id,event_type,payload_json,created_at_utc)
               VALUES('t1',? ,?,'TEST','entity1','a1','CREATED','{}',?)""",
            (cursor, event_id, TS),
        )

    def test_tenant_sync_state_is_created_and_cursor_starts_at_zero(self):
        self.add_base()
        row = self.db.execute(
            "SELECT last_cursor,version FROM tenant_sync_state WHERE tenant_id='t1'"
        ).fetchone()
        self.assertEqual(row, (0, 1))

    def test_audit_chain_requires_genesis_at_sequence_one(self):
        self.add_base()
        self.must_fail(
            """INSERT INTO audit_events
               (tenant_id,environment,sequence,id,event_type,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
               VALUES('t1','STAGING',2,'bad-genesis','EVENT',?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)""",
            (bytes(32), bytes(32), bytes(32), TS),
        )
        self.add_audit_event(1, "genesis", "GENESIS", bytes(32), b"g" * 32)

    def test_audit_chain_enforces_sequence_and_previous_hash_link(self):
        self.add_base()
        self.add_audit_event(1, "genesis", "GENESIS", bytes(32), b"a" * 32)
        self.add_audit_event(2, "event2", "SALE_POSTED", b"a" * 32, b"b" * 32)
        self.must_fail(
            """INSERT INTO audit_events
               (tenant_id,environment,sequence,id,event_type,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
               VALUES('t1','STAGING',4,'gap','SALE_POSTED',?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)""",
            (bytes(32), b"b" * 32, b"c" * 32, TS),
        )
        self.must_fail(
            """INSERT INTO audit_events
               (tenant_id,environment,sequence,id,event_type,payload_hash,previous_hash,event_hash,hash_algorithm,hash_version,occurred_at_utc)
               VALUES('t1','STAGING',3,'broken-link','SALE_POSTED',?,?,?,'SHA-256','FANEVA-AUDIT-V1',?)""",
            (bytes(32), b"x" * 32, b"c" * 32, TS),
        )
        self.add_audit_event(3, "event3", "SALE_POSTED", b"b" * 32, b"c" * 32)

    def test_change_log_advances_tenant_cursor_atomically_and_contiguously(self):
        self.add_base()
        self.add_change_log(1, "change1")
        self.assertEqual(
            self.db.execute("SELECT last_cursor,version FROM tenant_sync_state WHERE tenant_id='t1'").fetchone(),
            (1, 2),
        )
        self.must_fail(
            """INSERT INTO change_log
               (tenant_id,cursor,event_id,entity_type,entity_id,event_type,payload_json,created_at_utc)
               VALUES('t1',3,'change3','TEST','entity3','CREATED','{}',?)""",
            (TS,),
        )
        self.add_change_log(2, "change2")
        self.assertEqual(
            self.db.execute("SELECT last_cursor,version FROM tenant_sync_state WHERE tenant_id='t1'").fetchone(),
            (2, 3),
        )

    def test_change_log_and_cursor_roll_back_together(self):
        self.add_base()
        self.db.commit()
        self.db.execute("BEGIN")
        self.add_change_log(1, "change-rollback")
        self.assertEqual(
            self.db.execute("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id='t1'").fetchone(),
            (1,),
        )
        self.db.rollback()
        self.assertEqual(
            self.db.execute("SELECT last_cursor FROM tenant_sync_state WHERE tenant_id='t1'").fetchone(),
            (0,),
        )
        self.assertEqual(
            self.db.execute("SELECT COUNT(*) FROM change_log WHERE tenant_id='t1'").fetchone()[0],
            0,
        )

    def test_tenant_cursor_cannot_be_manually_advanced_or_deleted(self):
        self.add_base()
        self.must_fail(
            "UPDATE tenant_sync_state SET last_cursor=1,version=2 WHERE tenant_id='t1'"
        )
        self.must_fail("DELETE FROM tenant_sync_state WHERE tenant_id='t1'")



    def test_duplicate_stock_source_is_rejected_without_changing_projection(self):
        self.add_base()
        self.add_stock_movement("opening-stock", "OPENING", 5)
        self.add_stock_movement("sale-once", "SALE", -2, source_id="sale-42")
        self.must_fail(
            """INSERT INTO stock_movements
               (id,tenant_id,store_id,product_id,movement_type,quantity_delta,source_type,source_id,created_at_utc,created_by)
               VALUES('sale-duplicate','t1','a1','p1','SALE',-2,'TEST','sale-42',?,'admin1')""",
            (TS,),
        )
        self.assertEqual(
            self.db.execute("SELECT COUNT(*) FROM stock_movements WHERE tenant_id='t1' AND source_type='TEST' AND source_id='sale-42'").fetchone()[0],
            1,
        )
        self.assertEqual(
            self.db.execute("SELECT quantity FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'").fetchone()[0],
            3,
        )

    def test_stock_movement_ledger_is_append_only(self):
        self.add_base()
        self.add_stock_movement("opening-stock", "OPENING", 5)
        self.must_fail("UPDATE stock_movements SET quantity_delta=4 WHERE tenant_id='t1' AND id='opening-stock'")
        self.must_fail("DELETE FROM stock_movements WHERE tenant_id='t1' AND id='opening-stock'")
        self.assertEqual(
            self.db.execute("SELECT quantity FROM stock_balances WHERE tenant_id='t1' AND store_id='a1' AND product_id='p1'").fetchone()[0],
            5,
        )

if __name__ == "__main__":
    unittest.main()
