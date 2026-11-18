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
    for pid, code, name, price in [
        ("product_one","P-1","Product One",1000),
        ("product_two","P-2","Product Two",2000),
    ]:
        db.execute("""INSERT INTO business_products
            (product_id,product_code,name,category,purchase_price_mga,selling_price_mga,is_active,version,created_at,updated_at)
            VALUES (?,?,?,'',100,?,1,1,1,1)""", (pid,code,name,price))
    db.execute("INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at) VALUES ('A1','product_one',5,1,1)")
    db.execute("INSERT INTO business_stock(store_id,product_id,quantity,version,updated_at) VALUES ('A1','product_two',1,1,1)")
    db.commit()
    return db

def execute_two_line_sale(db, fail_second_line):
    try:
        db.execute("BEGIN")
        db.execute("""INSERT INTO business_sales
            (sale_id,sale_number,store_id,customer_id,actor_principal_id,sale_type,total_mga,paid_mga,remaining_mga,payment_method,status,version,created_at)
            VALUES ('sale_test','SALE-1','A1','customer_test','admin_test','CREDIT',3000,0,3000,'CASH','COMPLETED',1,2)""")
        lines = [("product_one",2,1000),("product_two",2,2000)]
        for index,(pid,qty,price) in enumerate(lines):
            cur = db.execute("""UPDATE business_stock
                SET quantity=quantity-?,version=version+1,updated_at=2
                WHERE store_id='A1' AND product_id=? AND version=1 AND quantity>=?""",
                (qty,pid,qty))
            if cur.rowcount != 1:
                # Same invariant as the Worker D1 batch guard: an invalid
                # stock movement forces the whole transaction to roll back.
                db.execute("""INSERT INTO business_stock_movements
                    (movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
                    VALUES ('guard','A1',?,'SALE',0,'sale_test','admin_test',2)""", (pid,))
            db.execute("""INSERT INTO business_stock_movements
                (movement_id,store_id,product_id,movement_type,quantity_delta,reference_id,actor_principal_id,created_at)
                VALUES (?,?,?,'SALE',?,'sale_test','admin_test',2)""",
                (f"movement_{index}","A1",pid,-qty))
            db.execute("""INSERT INTO business_sale_lines
                (sale_line_id,sale_id,product_id,product_code_snapshot,product_name_snapshot,unit_price_mga,quantity,line_total_mga)
                VALUES (?,?,?,?,?,?,?,?)""",
                (f"line_{index}","sale_test",pid,"P","Product",price,qty,price*qty))
        db.execute("""INSERT INTO business_customer_ledger
            (ledger_entry_id,customer_id,sale_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)
            VALUES ('ledger_test','customer_test','sale_test','CREDIT_SALE',3000,'admin_test',2,'A1')""")
        db.execute("""INSERT INTO sync_events(event_id,store_id,entity_type,entity_id,event_type,payload_json,created_at)
            VALUES ('event_test','A1','SALE','sale_test','UPSERT','{}',2)""")
        db.execute("""INSERT INTO sync_mutations
            (mutation_id,idempotency_key,principal_id,device_id,request_sha256_hex,entity_type,action,status,response_json,created_at)
            VALUES ('mutation_test','idem_test','admin_test','dev_test',printf('%064d',0),'SALE','CREATE','APPLIED','{}',2)""")
        db.commit()
        return True
    except sqlite3.IntegrityError:
        db.rollback()
        return False

def main():
    db = connect()
    assert execute_two_line_sale(db, fail_second_line=False) is False, "second product stock is intentionally insufficient"
    assert db.execute("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id='product_one'").fetchone() == (5,)
    assert db.execute("SELECT quantity FROM business_stock WHERE store_id='A1' AND product_id='product_two'").fetchone() == (1,)
    for table in ["business_sales","business_sale_lines","business_customer_ledger","business_stock_movements","sync_events","sync_mutations"]:
        assert db.execute(f"SELECT COUNT(*) FROM {table}").fetchone() == (0,), f"{table} leaked a partial write"
    print("PASS: insufficient stock on the final SALE line rolls back earlier stock, sale, lines, ledger, movements, event and idempotency record")

if __name__ == "__main__":
    main()
