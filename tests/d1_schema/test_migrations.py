import pathlib
import sqlite3
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
MIGRATION = ROOT / "migrations" / "0001_initial_schema.sql"
MAX_MGA = 9_007_199_254_740_991
TS = "2026-11-17T00:00:00Z"


class D1SchemaMigrationTests(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.executescript(MIGRATION.read_text(encoding="utf-8"))

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

    def add_sale(self, sale_id, sale_type, customer_id):
        self.db.execute(
            """INSERT INTO sales
               (id,tenant_id,store_id,sale_type,customer_id,document_number,status,total_mga,created_at_utc,created_by)
               VALUES(?,?,?,?,?,?,?,?,?,?)""",
            (sale_id, "t1", "a1", sale_type, customer_id, sale_id, "POSTED", 1000, TS, "admin1"),
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
        self.add_sale("sale1", "CREDIT", "c1")
        self.add_sale("sale2", "CREDIT", "c2")
        self.db.execute(
            """INSERT INTO sale_lines(id,tenant_id,sale_id,product_id,quantity,unit_price_mga,line_total_mga)
               VALUES('line1','t1','sale1','p1',2,500,1000)"""
        )
        self.db.execute(
            """INSERT INTO credit_notes(id,tenant_id,store_id,original_sale_id,credit_note_number,status,total_mga,created_at_utc,created_by)
               VALUES('cn1','t1','a1','sale1','CN-1','POSTED',500,?,'admin1')""",
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


if __name__ == "__main__":
    unittest.main()
