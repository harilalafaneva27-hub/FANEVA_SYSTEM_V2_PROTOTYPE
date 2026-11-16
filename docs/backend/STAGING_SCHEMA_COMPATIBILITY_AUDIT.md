# FANEVA SYSTEM V2 — STAGING seed / D1 schema compatibility audit

## Audit status

**BLOCKED — the concrete D1 SQL schema is not yet available for a column-level compatibility sign-off.**

This document records a conservative comparison between the checked-in synthetic fixture and the intended STAGING domain model. It does not invent D1 table names, columns, SQL types, constraints, or indexes. It is an audit gate, not a claim that a D1 schema exists.

## Scope and evidence

Reviewed artifacts on `feature/staging-backend-foundation`:
- `docs/backend/STAGING_ARCHITECTURE.md`
- `docs/backend/STAGING_BOOTSTRAP_PROTOCOL.md`
- `docs/backend/STAGING_SEED_MANIFEST.md`
- `staging/seed/staging-seed-v1.json`
- `staging/seed/validate_seed.py`
- `staging/seed/test_validate_seed.py`

The seed is a schema-neutral JSON fixture. The architecture describes the intended Worker/D1 topology and broad domain areas, but the artifacts reviewed do not provide an authoritative, versioned D1 migration set or a complete SQL data dictionary. Therefore no direct SQL insert mapping can be approved yet.

## 1. Domain coverage comparison

| Fixture section | Intended D1 domain | Current audit result | Required evidence before mapping |
|---|---|---|---|
| stores | Tenant/store registry | Conceptually aligned | Exact tenant/store tables, keys, uniqueness and tenant ownership |
| products | Product catalog | Conceptually aligned | Product columns, SKU/name uniqueness rules, units and active-state representation |
| customers | Customer registry | Conceptually aligned | Customer identity fields, privacy rules, store/tenant ownership |
| suppliers | Supplier registry | Conceptually aligned | Supplier identity fields and tenant ownership |
| actors | Accounts, roles and store access | Incomplete by design | Authentication/account tables, role enum, account-store join table, owner-verified enrollment model |
| openingStock | Store-product stock state | Incomplete by design | Stock balance key, quantity type, versioning and opening-balance semantics |
| purchases | Purchase headers and lines | Incomplete | Exact header/line schema, supplier references, cost representation and stock posting semantics |
| sales | Sales headers and lines | Incomplete | Exact header/line schema, payment allocation, actor/store references and stock posting semantics |
| debts | Debt ledger | Incomplete | Authoritative debt source model, payment ledger, balance derivation and ADMIN-only payment authorization |
| stockMovements | Inventory movement ledger | Incomplete | Movement type enum, source reference, signed quantity rules, idempotency and balance reconciliation |
| auditEvents | Append-only audit log | Incomplete | Audit schema, actor/device attribution, canonical hash-chain fields and append-only constraints |
| bootstrap metadata | Seed/import registry | Protocol-only | Unique tenant/environment key, seed ID/checksum, transaction boundary and committed state |
| device credentials/cursors | Device registry and sync cursor | Intentionally absent from seed | Enrollment, token storage/revocation, per-device cursor and bootstrap snapshot boundary |

“Conceptually aligned” means only that the domain concept exists in the architecture. It does not mean the fixture can be inserted into a SQL table as-is.

## 2. Known fixture-to-schema gaps

1. **Tenant identity:** fixture records use synthetic store IDs A1–A5, but the fixture does not yet encode a confirmed tenant ID or the exact tenant foreign-key contract.
2. **Actor versus account:** fixture actors are authorization test identities only. They are not login accounts and contain no password hashes, tokens, or enrollment secrets. A reviewed mapping must distinguish account identity, role grants, and store assignments.
3. **Opening stock — Option A APPROVED:** the immutable `OPENING` stock movement is the sole canonical source event; `stock_balances` is only a projection. The JSON `openingStock` section is an assertion and must not be imported as a second ledger. Each store/product pair has exactly one matching OPENING movement; the movement and initial projection are written atomically. Reject duplicate opening events and any resulting negative balance.
4. **Stock ledger:** the validator reconciles the canonical OPENING movements plus purchase and sale movements with the final store/product positions. This fixture-level check is not proof of server-side atomicity; D1 must still enforce transactional movement posting and projection consistency.
5. **Sales and payments:** the fixture uses a simple paid amount on a sale and a derived debt case. The actual schema must determine whether payments are separate immutable records and how partial payments, reversals, and calculated balances are represented. Do not persist a second independently editable balance if the architecture requires a derived balance.
6. **Purchases:** the fixture contains one purchase. It is sufficient for a small synthetic test but not a complete business workflow specification.
7. **Audit:** fixture events are deterministic test inputs, not production hash-chain entries. The Worker must generate trusted audit records and verify hash chaining server-side.
8. **Sync:** no device IDs, credentials, mutation outbox entries, server cursor, or change-log records are seeded. Those are created by enrollment and server operations, not by fixture import.
9. **SQL constraints:** unique constraints, foreign keys, indexes, CHECK constraints, transaction behavior, and schema version are not verifiable without the actual D1 migrations.

## 3. Required schema evidence

Before this audit can move from BLOCKED to PASS, the implementation branch must provide:

- versioned D1 SQL migrations, in execution order;
- a schema version and migration ownership policy;
- exact table/column/type/nullability definitions;
- primary keys, foreign keys, unique constraints, CHECK constraints and indexes;
- tenant and store ownership rules for every store-scoped row;
- role/account/store-scope relationship and authorization enforcement model;
- sale, purchase, debt/payment and stock movement source-of-truth rules;
- bootstrap registry and atomic import transaction design;
- device enrollment, credential revocation, mutation idempotency and pull-cursor schema;
- audit append-only/hash-chain structure.

## 4. Mandatory mapping procedure once migrations exist

1. Pin the exact migration commit SHA and schema version.
2. Extract the schema from a newly created empty V2 STAGING D1 or an equivalent reproducible local SQLite/D1-compatible migration test. Do not use an existing APK database as a substitute.
3. Build a field-by-field mapping for every fixture entity. Record source JSON path, target table/column, conversion, nullability, validation and foreign-key target.
4. Identify any fields that must be derived or generated server-side. Do not accept client-supplied authority, balances, audit hashes, cursor values, or credentials.
5. Validate all foreign keys and uniqueness constraints before import.
6. Test a complete import inside a rollback-capable transaction; force a mid-import failure and prove no partial rows remain.
7. Re-import the same seed and prove idempotency; attempt a different seed after COMMITTED and prove it is rejected.
8. Read back all rows and compare canonical counts/checksum and store-scoped stock/debt reconciliation.
9. Only then mark this audit PASS and consider a separately authorized STAGING provisioning step.

## 5. Prohibited shortcuts

- Do not invent table/column names and treat them as the real schema.
- Do not generate SQL INSERT statements until the migration set is reviewed.
- Do not map fixture actor identities into live authentication accounts.
- Do not seed device tokens, passwords, activation proofs, server cursors, or audit hash-chain values.
- Do not use V1 data, V1 infrastructure, installed APK data, or existing Room databases.
- Do not create, clear, truncate, or repurpose a D1 database to make the test pass.
- Do not deploy a Worker or install/update an APK as part of this audit.

## Conclusion

The synthetic fixture has passed a structural review, including reference checks and stock movement reconciliation. **D1 compatibility remains BLOCKED**, not failed: the required authoritative SQL schema evidence is missing. The next engineering task is to review and approve the actual D1 migration set, then perform the field-by-field mapping and transactional import tests. No Cloudflare resource or deployment action is authorized by this document.

## 6. Repository migration evidence checked on 2026-11-16

A direct recursive Git tree inspection of the PR head commit found the following local Room schema exports:
- `app/schemas/com.example.data.local.FanevaDatabase/2.json`
- `app/schemas/com.example.data.local.FanevaDatabase/4.json`

The checked-in `FanevaDatabase.kt` declares Room database version 7 and registers Room migrations through `MIGRATION_6_7`. The inspected repository tree does **not** contain a versioned Cloudflare D1 SQL migration directory or D1 SQL migration files. The Room schema exports and Room migration code describe the Android-local SQLite database; they are not a D1 schema and must not be treated as one.

Consequences:
1. The seed-to-D1 field mapping remains **BLOCKED**. No D1 tables, columns, SQL types, or insert statements are inferred from Room entities.
2. Room schema exports currently visible in this branch stop at version 4, while the Kotlin database declares version 7. This is a separate Room schema-export completeness gap to resolve for reliable local migration review; it does not supply the missing D1 schema.
3. The next backend implementation gate is to add a separately reviewed, versioned D1 migration set and tests against a disposable local D1-compatible environment. Only then can the fixture mapping be performed.
4. Do not provision Cloudflare D1, import the seed, or change Android database migrations as a shortcut for this missing evidence.

This evidence update does not change the audit conclusion: **D1 compatibility remains BLOCKED**.
