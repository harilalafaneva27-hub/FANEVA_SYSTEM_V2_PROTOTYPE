from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "migrations"

def main() -> None:
    db = sqlite3.connect(":memory:")
    db.execute("PRAGMA foreign_keys = ON")
    for migration in sorted(MIGRATIONS.glob("*.sql")):
        db.executescript(migration.read_text(encoding="utf-8"))

    payment_columns = {row[1] for row in db.execute("PRAGMA table_info(business_debt_payments)")}
    ledger_columns = {row[1] for row in db.execute("PRAGMA table_info(business_customer_ledger)")}
    assert "store_id" in payment_columns
    assert "store_id" in ledger_columns

    # Legacy rows must remain explicitly unattributed; never silently assign A1.
    db.execute("PRAGMA foreign_keys = OFF")
    db.execute("""INSERT INTO business_debt_payments
        (payment_id, customer_id, amount_mga, payment_method, note,
         actor_principal_id, created_at)
        VALUES ('pay_legacy', 'customer_legacy', 100, 'CASH', '', 'admin_legacy', 1)""")
    db.execute("""INSERT INTO business_customer_ledger
        (ledger_entry_id, customer_id, payment_id, entry_type, delta_mga,
         actor_principal_id, created_at)
        VALUES ('led_legacy', 'customer_legacy', 'pay_legacy',
                'DEBT_PAYMENT', -100, 'admin_legacy', 1)""")
    assert db.execute("SELECT store_id FROM business_debt_payments WHERE payment_id='pay_legacy'").fetchone() == (None,)
    assert db.execute("SELECT store_id FROM business_customer_ledger WHERE ledger_entry_id='led_legacy'").fetchone() == (None,)

    for table, key, columns, values in [
        ("business_debt_payments", "pay_bad", "(payment_id,customer_id,amount_mga,payment_method,actor_principal_id,created_at,store_id)", "('pay_bad','c',1,'CASH','a',1,'A6')"),
        ("business_customer_ledger", "led_bad", "(ledger_entry_id,customer_id,entry_type,delta_mga,actor_principal_id,created_at,store_id)", "('led_bad','c','DEBT_PAYMENT',-1,'a',1,'A6')"),
    ]:
        try:
            db.execute(f"INSERT INTO {table} {columns} VALUES {values}")
        except sqlite3.IntegrityError:
            continue
        raise AssertionError(f"{table} accepted invalid store_id A6")

    indexes = {row[1] for row in db.execute("PRAGMA index_list(business_debt_payments)")}
    assert "idx_debt_payments_store_customer_time" in indexes
    indexes = {row[1] for row in db.execute("PRAGMA index_list(business_customer_ledger)")}
    assert "idx_customer_ledger_store_customer_time" in indexes
    print("PASS: migration chain, explicit store attribution, legacy NULL preservation, and store CHECK constraints")

if __name__ == "__main__":
    main()
