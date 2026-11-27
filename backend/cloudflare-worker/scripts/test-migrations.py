#!/usr/bin/env python3
"""Run the D1 migration chain against SQLite and verify critical constraints."""
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = sorted((ROOT / "migrations").glob("*.sql"))
assert MIGRATIONS, "No migrations found"

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
for migration in MIGRATIONS:
    db.executescript(migration.read_text(encoding="utf-8"))

tables = {
    row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type IN ('table', 'view')"
    )
}
required = {
    "device_registry", "request_nonces", "owner_bootstrap",
    "auth_principals", "auth_sessions", "auth_store_grants", "auth_login_throttles",
    "sync_mutations", "sync_events", "sync_entity_versions",
    "business_products", "business_customers", "business_stock",
    "business_sales", "business_sale_lines", "business_customer_ledger",
    "business_debt_payments", "business_transfers",
    "business_stock_movements", "business_customer_balances",
}
assert required <= tables, f"Missing tables/views: {required - tables}"

indexes = {
    row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type = 'index'"
    )
}
assert "idx_auth_single_active_admin" in indexes
assert "idx_auth_login_throttles_locked" in indexes
assert "idx_auth_sessions_active_expiry" in indexes

columns = {row[1]: row for row in db.execute("PRAGMA table_info(auth_principals)")}
assert "password_hash_iterations" in columns
assert columns["password_hash_iterations"][4] == "310000"
assert "idx_customer_ledger_sale_once" in indexes
assert "idx_customer_ledger_payment_once" in indexes

db.execute(
    """INSERT INTO device_registry
       (device_id, public_key_spki_b64url, key_algorithm, status, key_version, created_at)
       VALUES ('dev_test', 'c3BraQ', 'ECDSA_P256_SHA256', 'ACTIVE', 1, 100)"""
)
db.execute(
    """INSERT INTO owner_bootstrap
       (bootstrap_id, code_sha256_hex, status, issued_at, expires_at,
        failed_attempts, max_attempts, consumed_at, consumed_by_principal_id)
       VALUES ('boot_test', ?, 'CONSUMED', 100, 1900, 0, 5, 101, 'admin_test')""",
    ("a" * 64,),
)
db.execute(
    """INSERT INTO auth_principals
       (principal_id, username_normalized, password_hash, status, created_at,
        created_by_bootstrap_id, role, display_name, password_salt_b64url, password_hash_iterations)
       VALUES ('admin_test', 'admin', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'ACTIVE', 101,
               'boot_test', 'ADMIN', 'Owner', 'AAAAAAAAAAAAAAAAAAAAAA', 100000)"""
)
assert db.execute("SELECT password_hash_iterations FROM auth_principals WHERE principal_id='admin_test'").fetchone()[0] == 100000

# Rows created before the migration retain their historical 310,000 default.
db.execute(
    """INSERT INTO auth_principals
       (principal_id, username_normalized, password_hash, status, created_at, role, display_name, password_salt_b64url)
       VALUES ('legacy_fixture', 'legacy', 'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc', 'DISABLED', 102, 'VENDEUR', 'Legacy', 'CCCCCCCCCCCCCCCCCCCCCC')"""
)
assert db.execute("SELECT password_hash_iterations FROM auth_principals WHERE principal_id='legacy_fixture'").fetchone()[0] == 310000

db.execute(
    """INSERT INTO auth_sessions
       (session_id, token_sha256_hex, principal_id, device_id, created_at, expires_at)
       VALUES ('session_test', ?, 'admin_test', 'dev_test', 101, 200)""",
    ("b" * 64,),
)

try:
    db.execute(
        """INSERT INTO auth_principals
           (principal_id, username_normalized, password_hash, status, created_at,
            role, display_name, password_salt_b64url)
           VALUES ('admin_second', 'admin2', 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb', 'ACTIVE', 102,
                   'ADMIN', 'Second Admin', 'BBBBBBBBBBBBBBBBBBBBBB')"""
    )
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("A second active ADMIN principal was accepted")

# Canonical business rows require an authenticated actor and stable server IDs.
db.execute(
    """INSERT INTO business_products
       (product_id, product_code, name, purchase_price_mga, selling_price_mga, created_at, updated_at)
       VALUES ('prd_server_1', 'CODE-1', 'Produit test', 1200, 1800, 100, 100)"""
)
db.execute(
    """INSERT INTO business_customers
       (customer_id, display_name, credit_limit_mga, created_at, updated_at)
       VALUES ('cus_server_1', 'Client test', 50000, 100, 100)"""
)
db.execute(
    """INSERT INTO business_stock (store_id, product_id, quantity, version, updated_at)
       VALUES ('A1', 'prd_server_1', 8, 1, 100)"""
)
db.execute(
    """INSERT INTO business_sales
       (sale_id, sale_number, store_id, customer_id, actor_principal_id, sale_type,
        total_mga, paid_mga, remaining_mga, payment_method, status, created_at)
       VALUES ('sale_server_1', 'VNT-TEST-1', 'A1', 'cus_server_1', 'admin_test',
               'CREDIT', 3600, 1000, 2600, 'CASH', 'COMPLETED', 101)"""
)
db.execute(
    """INSERT INTO business_sale_lines
       (sale_line_id, sale_id, product_id, product_code_snapshot, product_name_snapshot,
        unit_price_mga, quantity, line_total_mga)
       VALUES ('line_1', 'sale_server_1', 'prd_server_1', 'CODE-1', 'Produit test', 1800, 2, 3600)"""
)
db.execute(
    """INSERT INTO business_customer_ledger
       (ledger_entry_id, customer_id, sale_id, entry_type, delta_mga, actor_principal_id, created_at)
       VALUES ('ledger_sale_1', 'cus_server_1', 'sale_server_1', 'CREDIT_SALE', 2600, 'admin_test', 101)"""
)
balance = db.execute(
    "SELECT current_debt_mga FROM business_customer_balances WHERE customer_id = 'cus_server_1'"
).fetchone()[0]
assert balance == 2600, f"Expected server-derived debt 2600, got {balance}"

def must_reject(sql, params=()):
    try:
        db.execute(sql, params)
    except sqlite3.IntegrityError:
        return
    raise AssertionError(f"Constraint unexpectedly accepted: {sql}")

must_reject(
    """INSERT INTO business_stock (store_id, product_id, quantity, version, updated_at)
       VALUES ('A6', 'prd_server_1', 1, 1, 101)"""
)
must_reject(
    """INSERT INTO business_stock (store_id, product_id, quantity, version, updated_at)
       VALUES ('A1', 'prd_server_1', -1, 1, 101)"""
)
must_reject(
    """INSERT INTO business_stock (store_id, product_id, quantity, version, updated_at)
       VALUES ('A2', 'prd_server_1', 1.5, 1, 101)"""
)
must_reject(
    """INSERT INTO business_products
       (product_id, product_code, name, purchase_price_mga, selling_price_mga, created_at, updated_at)
       VALUES ('prd_decimal', 'CODE-D', 'Decimal', 1.5, 10, 100, 100)"""
)
must_reject(
    """INSERT INTO business_products
       (product_id, product_code, name, purchase_price_mga, selling_price_mga, created_at, updated_at)
       VALUES ('prd_negative', 'CODE-N', 'Negative', -1, 10, 100, 100)"""
)
must_reject(
    """INSERT INTO business_sale_lines
       (sale_line_id, sale_id, product_id, product_code_snapshot, product_name_snapshot,
        unit_price_mga, quantity, line_total_mga)
       VALUES ('line_bad', 'sale_server_1', 'prd_server_1', 'CODE-1', 'Produit test', 1800, 2, 3599)"""
)

# The idempotency ledger is unique per principal + device + caller key.
db.execute(
    """INSERT INTO sync_mutations
       (mutation_id, idempotency_key, principal_id, device_id, request_sha256_hex,
        entity_type, action, status, response_json, created_at)
       VALUES ('mut_1', 'idem-1', 'admin_test', 'dev_test', ?, 'PRODUCT', 'UPSERT',
               'APPLIED', '{"status":"APPLIED"}', 102)""",
    ("c" * 64,),
)
must_reject(
    """INSERT INTO sync_mutations
       (mutation_id, idempotency_key, principal_id, device_id, request_sha256_hex,
        entity_type, action, status, response_json, created_at)
       VALUES ('mut_2', 'idem-1', 'admin_test', 'dev_test', ?, 'PRODUCT', 'UPSERT',
               'APPLIED', '{"status":"APPLIED"}', 103)""",
    ("d" * 64,),
)
# Optimistic concurrency: only the expected version may update the canonical row.
updated = db.execute(
    "UPDATE business_products SET version=2 WHERE product_id='prd_server_1' AND version=1"
)
assert updated.rowcount == 1
stale = db.execute(
    "UPDATE business_products SET version=3 WHERE product_id='prd_server_1' AND version=1"
)
assert stale.rowcount == 0
# The event cursor is a monotonic server-owned sequence.
db.execute(
    """INSERT INTO sync_events(event_id, store_id, entity_type, entity_id, event_type, payload_json, created_at)
       VALUES ('evt_1', 'A1', 'PRODUCT', 'prd_server_1', 'UPSERT', '{}', 104)"""
)
db.execute(
    """INSERT INTO sync_events(event_id, store_id, entity_type, entity_id, event_type, payload_json, created_at)
       VALUES ('evt_2', 'A2', 'PRODUCT', 'prd_server_1', 'UPSERT', '{}', 105)"""
)
cursors = [row[0] for row in db.execute("SELECT cursor FROM sync_events ORDER BY cursor")]
assert cursors == sorted(cursors) and len(set(cursors)) == 2

assert db.execute("PRAGMA foreign_key_check").fetchall() == []
print(f"MIGRATIONS_OK count={len(MIGRATIONS)}")
print("BOOTSTRAP_SCHEMA_OK")
print("SINGLE_ACTIVE_ADMIN_CONSTRAINT_OK")
print("BUSINESS_MGA_INTEGER_CONSTRAINTS_OK")
print("BUSINESS_STORE_AND_STOCK_CONSTRAINTS_OK")
print("SERVER_LEDGER_BALANCE_VIEW_OK")
print("IDEMPOTENCY_UNIQUENESS_OK")
print("OPTIMISTIC_VERSION_CONFLICT_OK")
print("SERVER_CURSOR_MONOTONIC_OK")
print("FOREIGN_KEYS_OK")
