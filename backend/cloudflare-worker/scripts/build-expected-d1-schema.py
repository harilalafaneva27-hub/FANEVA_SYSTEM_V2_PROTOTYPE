#!/usr/bin/env python3
"""Apply the reviewed migrations to in-memory SQLite and emit full schema definitions."""
import json
from pathlib import Path
import sqlite3

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = [
    "0001_device_registry.sql",
    "0002_owner_bootstrap_sessions.sql",
    "0003_authoritative_business_ledger.sql",
    "0004_auth_credential_hardening.sql",
    "0005_store_attributed_customer_ledger.sql",
    "0006_security_audit_chain.sql",
    "0007_auth_password_hash_iterations.sql",
]

migration_dir = ROOT / "migrations"
actual_names = sorted(path.name for path in migration_dir.glob("*.sql"))
assert actual_names == sorted(MIGRATIONS), "Expected exactly reviewed migrations 0001-0007"

db = sqlite3.connect(":memory:")
db.execute("PRAGMA foreign_keys = ON")
for name in MIGRATIONS:
    db.executescript((migration_dir / name).read_text(encoding="utf-8"))

rows = db.execute(
    """SELECT type, name, tbl_name, sql
       FROM sqlite_master
       WHERE name NOT LIKE 'sqlite_%'
         AND name NOT LIKE '_cf_%'
         AND name <> 'd1_migrations'
       ORDER BY type, name, tbl_name"""
).fetchall()
print(json.dumps([
    {"type": row[0], "name": row[1], "tbl_name": row[2], "sql": row[3]}
    for row in rows
], separators=(",", ":")))
