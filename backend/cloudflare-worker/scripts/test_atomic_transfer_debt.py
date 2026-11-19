from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "migrations"

def connect():
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys = ON")
    for migration in sorted(MIGRATIONS.glob("*.sql")):
        db.executescript(migration.read_text(encoding="utf-8"))
    db.execute("""INSERT INTO device_registry
        (device_id,public_key_spki_b64url,key_algorithm,status,created_at)
        VALUES ('dev_test','key','ECDSA_P256_SHA256','ACTIVE',1)""")
    db.execute("""INSERT INTO auth_principals
        (principal_id,username_normalized,password_hash,status,created_at,role)
        VALUES ('admin_test','admin','hash','ACTIVE',1,'ADMIN')""")
    db.execute("""INSERT INTO business_customers
        (customer_id,display_name,credit_limit_mga,version,created_at,updated_at)
        VALUES ('customer_test','Customer Test',100000,1,1,1)""")
    db.execute("""INSERT INTO business_products
        (product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,created_at,updated_at)
        VALUES ('product_test','P-1','Product One','',100,200,1,1,1,1)""")
    db.commit()
    return db

def insert_idempotency(db, key, entity_type, status="APPLIED"):
    db.execute("""INSERT INTO sync_mutations
        (mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at)
        VALUES (?,?,?,?,?,?, 'CREATE', ?, '{}', 2)""",
        ("mutation_" + key,key,"admin_test","dev_test","a"*64,entity_type,status))

def test_transfer_atomic_rollback():
    db = connect()
    db.execute("INSERT INTO business_stock VALUES ('A1','product_test',10,1,1)")
    db.execute("INSERT INTO business_stock VALUES ('A2','product_test',3,4,1)")
    db.commit()
    # Preflight sees both rows, then a concurrent source writer advances its version.
    db.execute("UPDATE business_stock SET quantity=8,version=2 WHERE store_id='A1' AND product_id='product_test'")
    db.commit()
    try:
        db.execute("BEGIN")
        db.execute("""INSERT INTO business_transfers
            (transfer_id,product_id,from_store_id,to_store_id,quantity,actor_principal_id,reason,status,created_at)
            VALUES ('transfer_test','product_test','A1','A2',4,'admin_test','','COMPLETED',2)""")
        cur = db.execute("""UPDATE business_stock SET quantity=quantity-4,version=version+1,updated_at=2
            WHERE store_id='A1' AND product_id='product_test' AND version=1 AND quantity>=4""")
        if cur.rowcount != 1:
            db.execute("""INSERT INTO business_stock_movements
                (movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
                VALUES ('guard_source','A1','product_test','TRANSFER_OUT',0,'transfer_test','admin_test',2)""")
        raise AssertionError("conditional source update unexpectedly passed")
    except sqlite3.IntegrityError:
        db.rollback()
    assert db.execute("SELECT quantity,version FROM business_stock WHERE store_id='A1' AND product_id='product_test'").fetchone() == (8,2)
    assert db.execute("SELECT quantity,version FROM business_stock WHERE store_id='A2' AND product_id='product_test'").fetchone() == (3,4)
    for table in ("business_transfers","business_stock_movements","sync_events","sync_mutations"):
        assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone() == (0,), f"{table} leaked after TRANSFER rollback"

def test_transfer_success():
    db = connect()
    db.execute("INSERT INTO business_stock VALUES ('A1','product_test',10,1,1)")
    db.execute("INSERT INTO business_stock VALUES ('A2','product_test',3,4,1)")
    db.commit()
    db.execute("BEGIN")
    db.execute("""INSERT INTO business_transfers
        (transfer_id,product_id,from_store_id,to_store_id,quantity,actor_principal_id,reason,status,created_at)
        VALUES ('transfer_ok','product_test','A1','A2',4,'admin_test','','COMPLETED',2)""")
    db.execute("""UPDATE business_stock SET quantity=quantity-4,version=version+1,updated_at=2
        WHERE store_id='A1' AND product_id='product_test' AND version=1 AND quantity>=4""")
    db.execute("""INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at)
        VALUES ('A2','product_test',4,1,2)
        ON CONFLICT(store_id,product_id) DO UPDATE SET quantity=business_stock.quantity+excluded.quantity,
          version=business_stock.version+1,updated_at=excluded.updated_at
        WHERE business_stock.version=4 AND business_stock.quantity<=2147483647-excluded.quantity""")
    db.execute("""INSERT INTO business_stock_movements
        (movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
        VALUES ('out','A1','product_test','TRANSFER_OUT',-4,'transfer_ok','admin_test',2)""")
    db.execute("""INSERT INTO business_stock_movements
        (movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
        VALUES ('in','A2','product_test','TRANSFER_IN',4,'transfer_ok','admin_test',2)""")
    db.execute("""INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
        VALUES ('event_out','A1','TRANSFER','transfer_ok','UPSERT','{}',2)""")
    db.execute("""INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
        VALUES ('event_in','A2','TRANSFER','transfer_ok','UPSERT','{}',2)""")
    insert_idempotency(db,"transfer_key","TRANSFER")
    db.commit()
    assert db.execute("SELECT quantity,version FROM business_stock WHERE store_id='A1' AND product_id='product_test'").fetchone() == (6,2)
    assert db.execute("SELECT quantity,version FROM business_stock WHERE store_id='A2' AND product_id='product_test'").fetchone() == (7,5)
    assert db.execute("SELECT COUNT(*) FROM business_stock_movements WHERE reference_id='transfer_ok'").fetchone() == (2,)
    assert db.execute("SELECT COUNT(*) FROM sync_events WHERE entity_id='transfer_ok'").fetchone() == (2,)
    assert db.execute("SELECT status FROM sync_mutations WHERE idempotency_key='transfer_key'").fetchone() == ("APPLIED",)

def test_debt_payment_overpayment_race_rolls_back():
    db = connect()
    db.execute("""INSERT INTO business_customer_ledger
        (ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
        VALUES ('credit','customer_test','CREDIT_SALE',1000,'admin_test',1,'A1')""")
    # Preflight could have observed 1,000 MGA; another payment commits 700 MGA first.
    db.execute("""INSERT INTO business_debt_payments
        (payment_id,customer_id,amount_mga,payment_method,note,actor_principal_id,created_at,store_id)
        VALUES ('prior_payment','customer_test',700,'CASH','','admin_test',2,'A1')""")
    db.execute("""INSERT INTO business_customer_ledger
        (ledger_entry_id,customer_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
        VALUES ('prior_ledger','customer_test','prior_payment','DEBT_PAYMENT',-700,'admin_test',2,'A1')""")
    db.commit()
    assert db.execute("SELECT SUM(delta_mga) FROM business_customer_ledger WHERE customer_id='customer_test'").fetchone() == (300,)
    try:
        db.execute("BEGIN")
        # A positive DEBT_PAYMENT delta violates the ledger CHECK if latest debt < requested amount.
        db.execute("""INSERT INTO business_customer_ledger
            (ledger_entry_id,customer_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
            SELECT 'guard','customer_test','attempted_payment','DEBT_PAYMENT',1,'admin_test',3,'A1'
            WHERE (SELECT COALESCE(SUM(delta_mga),0) FROM business_customer_ledger WHERE customer_id='customer_test') < 500""")
        raise AssertionError("overpayment guard did not fire")
    except sqlite3.IntegrityError:
        db.rollback()
    assert db.execute("SELECT COUNT(*) FROM business_debt_payments WHERE payment_id='attempted_payment'").fetchone() == (0,)
    assert db.execute("SELECT SUM(delta_mga) FROM business_customer_ledger WHERE customer_id='customer_test'").fetchone() == (300,)
    assert db.execute("SELECT COUNT(*) FROM sync_events").fetchone() == (0,)
    assert db.execute("SELECT COUNT(*) FROM sync_mutations").fetchone() == (0,)

def test_debt_payment_success():
    db = connect()
    db.execute("""INSERT INTO business_customer_ledger
        (ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
        VALUES ('credit','customer_test','CREDIT_SALE',1000,'admin_test',1,'A1')""")
    db.commit()
    db.execute("BEGIN")
    db.execute("""INSERT INTO business_debt_payments
        (payment_id,customer_id,amount_mga,payment_method,note,actor_principal_id,created_at,store_id)
        VALUES ('payment_ok','customer_test',600,'MVOLA','receipt','admin_test',2,'A1')""")
    db.execute("""INSERT INTO business_customer_ledger
        (ledger_entry_id,customer_id,payment_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
        VALUES ('ledger_ok','customer_test','payment_ok','DEBT_PAYMENT',-600,'admin_test',2,'A1')""")
    db.execute("""INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
        VALUES ('event_payment','A1','DEBT_PAYMENT','payment_ok','UPSERT','{}',2)""")
    insert_idempotency(db,"payment_key","DEBT_PAYMENT")
    db.commit()
    assert db.execute("SELECT SUM(delta_mga) FROM business_customer_ledger WHERE customer_id='customer_test'").fetchone() == (400,)
    assert db.execute("SELECT store_id FROM business_debt_payments WHERE payment_id='payment_ok'").fetchone() == ("A1",)
    assert db.execute("SELECT store_id FROM business_customer_ledger WHERE payment_id='payment_ok'").fetchone() == ("A1",)
    assert db.execute("SELECT status FROM sync_mutations WHERE idempotency_key='payment_key'").fetchone() == ("APPLIED",)

def main():
    test_transfer_atomic_rollback()
    test_transfer_success()
    test_debt_payment_overpayment_race_rolls_back()
    test_debt_payment_success()
    print("PASS: TRANSFER source-version rollback, two-store atomic stock/movement/events, and DEBT_PAYMENT overpayment race rollback/store attribution/idempotent commit")

if __name__ == "__main__":
    main()
