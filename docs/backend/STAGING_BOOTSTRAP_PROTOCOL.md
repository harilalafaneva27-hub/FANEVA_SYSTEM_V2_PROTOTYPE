# FANEVA SYSTEM V2 — STAGING snapshot bootstrap protocol

## Status

- Decision: Option A approved — a newly provisioned, empty V2 STAGING D1 database and a newly generated synthetic test dataset.
- Scope of this change: protocol and acceptance criteria only. This document does not create Cloudflare resources, generate/import data, modify Android code, deploy a Worker, or install an APK.
- V1 is completely excluded. No V1 package, data, API, Worker, D1, credentials, identifiers, or backup may be read or reused.
- Existing V2 APK/Room data is also not a bootstrap source in this protocol. The first STAGING dataset is synthetic and independent.

## 1. Non-negotiable invariants

1. Provision a dedicated V2 STAGING D1 database that is demonstrably empty before schema migration or seed import.
2. Generate all bootstrap records specifically for STAGING. Use fictional names, contacts, suppliers, products, sales, purchases, balances, and movements.
3. Never import records from an installed APK, Room database, export, backup, or production environment.
4. A bootstrap is one-time and idempotent. A repeated request with the same committed seed identity returns the original result without inserting duplicates.
5. A failed or interrupted import must leave no partially committed dataset.
6. No device may treat a local demo cursor as a server cursor. The server issues the initial pull checkpoint only after successful bootstrap and device enrollment.
7. No credential, password, token, activation proof, or private key belongs in the seed file, repository, logs, or this document.
8. All amounts are whole, non-negative MGA integers, bounded by 9,007,199,254,740,991. Reject decimals, negatives, non-numeric values, and overflow.
9. ADMIN #1 and ADMIN #2 have equal authority. Device order does not confer authority.
10. Store isolation and role authorization are enforced by the Worker, not trusted from client-supplied seed fields.

## 2. Bootstrap lifecycle

### Phase 0 — explicit environment confirmation

Before provisioning, record the V2 STAGING account owner, account identity, Worker name, D1 name, and staging hostname in a controlled deployment record. Confirm that the target account is separate from every V1/production account. Do not record secrets. No resource creation is authorized by this protocol alone.

### Phase 1 — create and verify an empty D1

Create a new, uniquely named D1 database in the confirmed V2 STAGING account. Before applying migrations, verify:
- the database identity matches the approved STAGING target;
- no application tables or rows exist;
- no existing production/V1 resource is being targeted.

Capture a non-secret verification record (database identifier, environment label, timestamp, and table/row-count result). If emptiness cannot be proven, stop; do not clean or repurpose an existing database.

### Phase 2 — apply versioned schema migrations

Apply reviewed, ordered migrations to the new empty D1. Verify the expected schema version, primary keys, foreign keys, unique constraints, indexes, and required authorization tables. Migration failure is a hard stop; do not seed a partially migrated database.

### Phase 3 — generate and validate the synthetic seed

Generate a deterministic seed artifact from the approved manifest. The artifact must contain only fictional STAGING data and stable synthetic IDs. Validate:
- schema version and seed version are supported;
- all IDs are unique and all foreign-key references resolve;
- each store-scoped record belongs to exactly one valid store A1–A5;
- actor roles and store assignments are internally consistent;
- no VENDEUR is granted privileged fields or operations;
- all MGA values satisfy the integer and upper-bound rules;
- no credentials or real personal data are present;
- canonical serialization produces the declared SHA-256 checksum.

A validation failure rejects the whole seed before any write.

### Phase 4 — atomic import

The Worker performs the import in a single D1 transaction (or an equivalent transaction-safe batch with a single commit boundary). It must:
1. acquire/insert a unique bootstrap identity for the target tenant/environment;
2. verify the database is still in the expected empty/uninitialized state;
3. insert the complete dataset and its audit/bootstrap metadata;
4. record seed version, seed ID, checksum, counts, and UTC commit time;
5. mark the import COMMITTED only in the same successful transaction.

Any error rolls back the complete import. Never continue by skipping invalid rows.

### Phase 5 — post-import verification

Read back the committed dataset and compare:
- entity counts against the manifest;
- per-store counts and ownership;
- referential integrity;
- canonical dataset checksum;
- bootstrap status and seed identity.

A mismatch blocks enrollment and sync. Do not repair by editing rows manually; reject/reset only through a separately reviewed STAGING-only procedure.

### Phase 6 — enroll two distinct test devices

Enroll Android #1 and Android #2 separately using the approved owner-verified activation flow. Verify:
- generated device IDs differ;
- each device has its own revocable credential;
- both devices have equal ADMIN authority;
- no credential is shared or embedded in the seed;
- each device receives a server-generated initial checkpoint after successful bootstrap.

Do not install or update either APK as part of this documentation-only change.

### Phase 7 — initial pull and two-device verification

Both devices obtain the same authoritative synthetic snapshot through the defined pull protocol. Each page is applied transactionally. The local pull cursor advances only after the full page commits. Replaying a page must not duplicate records. A failed page must not advance the cursor.

## 3. Bootstrap metadata contract

Persist a bootstrap record with at least:
- `environment`: fixed value `STAGING`;
- `seedVersion`: semantic/integer version of the seed format;
- `seedId`: immutable unique identifier for this dataset;
- `seedChecksum`: SHA-256 of canonical seed content, excluding the checksum field itself;
- `importStatus`: `PREPARED`, `VALIDATED`, `COMMITTED`, or `REJECTED`;
- `recordCounts`: counts by entity type and store where applicable;
- `createdAtUtc` and `committedAtUtc`.

The unique key must prevent a second, different seed from being imported into an already bootstrapped tenant. Same seed ID and checksum returns the original committed result. Same seed ID with a different checksum is rejected. A different seed ID after COMMITTED is rejected unless a separately approved reset procedure has first recreated a verified-empty STAGING database.

## 4. Cursor semantics

- The seed import is not a synthetic history of 1,042 prior server events.
- The legacy/demo local value `lastCursor=1042` is not trusted and must never be sent as a server checkpoint.
- After seed commit, the server establishes a documented bootstrap snapshot boundary and issues a server-owned cursor/checkpoint for each enrolled device.
- Push mutation IDs/idempotency keys and pull change cursors are separate namespaces and serve different purposes.
- A client may advance its cursor only after the corresponding snapshot/page has been validated and committed locally.
- Cursor gaps, malformed pages, duplicate event IDs with conflicting content, or transaction failures block cursor advancement.

## 5. Acceptance tests

| ID | Test | Expected result |
|---|---|---|
| BOOT-001 | New D1 before migration | No application tables or rows |
| BOOT-002 | Wrong account/database identity | Provisioning/import blocked |
| BOOT-003 | Schema migration failure | No seed import; stop |
| BOOT-004 | Valid synthetic seed | Full validation succeeds before writes |
| BOOT-005 | Duplicate IDs or broken references | Entire seed rejected |
| BOOT-006 | Decimal, negative, or oversized MGA | Entire seed rejected |
| BOOT-007 | Unknown store or invalid store ownership | Entire seed rejected |
| BOOT-008 | Checksum mismatch | Entire seed rejected |
| BOOT-009 | Failure during import | Transaction rolls back; no partial dataset |
| BOOT-010 | Repeat same committed seed ID/checksum | Original result returned; no duplicates |
| BOOT-011 | Same seed ID, different checksum | Rejected |
| BOOT-012 | Different seed after COMMITTED | Rejected absent approved database reset |
| BOOT-013 | Read-back counts/checksum mismatch | Enrollment and sync blocked |
| BOOT-014 | Enroll two devices | Unique device IDs and separate credentials |
| BOOT-015 | ADMIN parity | Both test ADMINs have equal authorization |
| BOOT-016 | VENDEUR cross-store access | Denied |
| BOOT-017 | Initial snapshot replay | No duplicate local records |
| BOOT-018 | Interrupted/invalid pull page | Local cursor unchanged |
| BOOT-019 | Local demo cursor 1042 | Ignored; server checkpoint used |
| BOOT-020 | V1 resources/data/credentials referenced | Test/review fails; prohibited |

## 6. Stop conditions

Stop without retrying destructive actions if the account identity is uncertain, D1 is not empty, migration state is unknown, seed checksum differs, transaction atomicity cannot be demonstrated, bootstrap metadata conflicts, or authorization tests fail. Do not drop, truncate, overwrite, or repurpose any database to make a test pass.

## 7. Completion evidence required before enabling STAGING sync

The implementation review must retain non-secret evidence for: approved account/environment identity; empty-D1 verification; migration version; seed ID/version/checksum; pre-import validation report; transaction result; post-import counts/checksum; device identity uniqueness; authorization tests; and initial pull/cursor tests.

This protocol is a design/acceptance artifact. Passing documentation review does not mean that D1 has been provisioned, a seed imported, devices enrolled, or sync deployed.
