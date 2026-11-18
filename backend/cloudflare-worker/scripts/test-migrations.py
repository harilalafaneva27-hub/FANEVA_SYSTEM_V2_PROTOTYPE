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
        "SELECT name FROM sqlite_master WHERE type = 'table'"
    )
}
required = {
    "device_registry", "request_nonces", "owner_bootstrap",
    "auth_principals", "auth_sessions", "auth_store_grants",
    "sync_mutations", "sync_events", "sync_entity_versions",
}
assert required <= tables, f"Missing tables: {required - tables}"

indexes = {
    row[0] for row in db.execute(
        "SELECT name FROM sqlite_master WHERE type = 'index'"
    )
}
assert "idx_auth_single_active_admin" in indexes

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
        created_by_bootstrap_id, role, display_name)
       VALUES ('admin_test', 'admin', 'salt:verifier', 'ACTIVE', 101,
               'boot_test', 'ADMIN', 'Owner')"""
)
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
            role, display_name)
           VALUES ('admin_second', 'admin2', 'salt:verifier', 'ACTIVE', 102,
                   'ADMIN', 'Second Admin')"""
    )
except sqlite3.IntegrityError:
    pass
else:
    raise AssertionError("A second active ADMIN principal was accepted")

assert db.execute("PRAGMA foreign_key_check").fetchall() == []
print(f"MIGRATIONS_OK count={len(MIGRATIONS)}")
print("BOOTSTRAP_SCHEMA_OK")
print("SINGLE_ACTIVE_ADMIN_CONSTRAINT_OK")
print("FOREIGN_KEYS_OK")
