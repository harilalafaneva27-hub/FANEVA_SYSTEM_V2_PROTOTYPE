# Financial Allocation Reversal — Executable Test Plan

**Status:** Migration 0006 and the Worker reversal handler are implemented in Draft PRs #32 and #34. The repository has passed local migration, Worker-adapter, and Wrangler local D1 test suites. Remote isolated V2 STAGING verification remains outstanding.

## 1. Test layers

| Layer | Purpose | Evidence |
|---|---|---|
| Migration schema tests | Apply migrations 0001–0006 to disposable SQLite and validate constraints/triggers | 53 total tests passed on migration PR #32 head 666eed4afebe4e4b68fa27cafc202bb730d52067, run #44079174076 |
| Worker adapter tests | Validate endpoint logic, idempotency, roles, source restoration, and rollback through the SQLite-backed adapter | 41/41 tests passed on PR #34 head d177c7afcd2c5328d948c6336f6f5db8bd048da6, run #44083656474 |
| Wrangler local D1 integration | Run the Worker with a local Cloudflare D1 binding and apply migrations 0001–0006 | 10/10 tests passed on PR #34 head d177c7afcd2c5328d948c6336f6f5db8bd048da6, run #44083656317 |
| Remote Cloudflare D1 STAGING | Verify actual account database behavior, concurrency, and operational configuration | Not yet run |

The Wrangler local D1 workflow uses no Cloudflare API token or account secret and does not create or modify remote resources.

## 2. Reversal cap and append-only behavior

| ID | Setup / operation | Expected |
|---|---|---|
| REV-001 | Original allocation 1,000 MGA; reverse 300 | One new reversal row; original unchanged; remaining 700 |
| REV-002 | Reverse another 200 against the same original | Second reversal row; cumulative 500; remaining 500 |
| REV-003 | Reverse exact remaining 500 | Accepted; cumulative 1,000; remaining zero |
| REV-004 | Attempt one additional MGA | Rejected; no new ledger or projection row |
| REV-005 | Attempt to reverse a reversal row | Rejected |
| REV-006 | Attempt to UPDATE or DELETE original allocation or posted reversal | Rejected by append-only guards |
| REV-007 | Attempt cross-tenant, cross-customer, wrong source, wrong invoice, or wrong store reversal | Rejected |
| REV-008 | Attempt zero, negative, fractional, or unsafe integer amount | Rejected |
| REV-009 | Stale expectedVersion | VERSION_CONFLICT; no writes |
| REV-010 | Concurrent partial reversals against one original | Cumulative successful amount never exceeds original amount |

## 3. Idempotency, authorization, and atomicity

| ID | Operation | Expected |
|---|---|---|
| IDEM-001 | Repeat same key and canonical request hash | Same receipt; one reversal and one availability entry |
| IDEM-002 | Reuse key with a different request hash | IDEMPOTENCY_CONFLICT; no extra writes |
| AUTH-001 | ADMIN1 performs valid reversal | Allowed within assigned store |
| AUTH-002 | ADMIN2 performs valid reversal | Same authority as ADMIN1 |
| AUTH-003 | GERANT or VENDEUR requests reversal | REVERSAL_FORBIDDEN |
| AUTH-004 | ADMIN requests reversal outside assigned stores | STORE_SCOPE_DENIED |
| ATOM-001 | Force availability-ledger insert failure | Reversal, state, audit, cursor, and receipt roll back |
| ATOM-002 | Force audit insert failure | Entire D1 batch rolls back |
| ATOM-003 | Force change-log/cursor failure | Entire D1 batch rolls back |
| ATOM-004 | Force receipt insert failure | Entire D1 batch rolls back |
| ATOM-005 | Replay after successful commit | Original receipt returned; no second event or cursor movement |
| CON-001 | Concurrent independent reversals collide on tenant audit/cursor | No partial write; retry succeeds with same idempotency key |

## 4. Debt-payment availability

| ID | Setup / operation | Expected |
|---|---|---|
| PAY-001 | Payment received 1,000; allocation 700; reverse 250 | Payment remains received; restored availability 250; invoice outstanding +250 |
| PAY-002 | Reverse another 150 | Restored availability total 400; no second payment receipt |
| PAY-003 | Allocate 300 from a restored availability entry | Allowed; exact entry has 100 remaining |
| PAY-004 | Attempt to allocate 101 more from that entry | Rejected |
| PAY-005 | Reverse an allocation that consumed a restored entry | New child availability entry; parent entry is not made spendable again |
| PAY-006 | Replay a reversal | Availability is not restored twice |

## 5. Customer-credit availability

| ID | Setup / operation | Expected |
|---|---|---|
| CRD-001 | Credit source 800; allocation 600; reverse 250 | Positive source-linked reversal entry of 250; original principal unchanged |
| CRD-002 | Reverse another 100 | Separate reversal entry; restored total 350 |
| CRD-003 | Allocate 300 from restored credit | Allowed; exact source has 50 remaining |
| CRD-004 | Attempt to allocate 51 more | Rejected |
| CRD-005 | Reversal of an allocation from a restored credit entry | New source-linked child credit entry; no double spend |
| CRD-006 | Cross-customer/store/tenant source use | Rejected |

## 6. Invoice and accounting-period behavior

| ID | Operation | Expected |
|---|---|---|
| INV-001 | Reverse a posted allocation | Invoice collectible outstanding increases by the successful reversal amount |
| INV-002 | Apply debt payment and customer credit after reversal | Combined net allocations remain within collectible balance |
| INV-003 | Post a credit note after reversal | Credit note is capped against net outstanding |
| INV-004 | Reallocate restored payment or credit | Outstanding decreases only by the new posted allocation |
| PER-001 | Reversal in OPEN source period | Posted in that OPEN period |
| PER-002 | Reversal against CLOSED source period | Posted in next OPEN period with source link |
| PER-003 | No next OPEN period | Rejected; CLOSED period unchanged |
| PER-004 | Overlapping accounting-period insert | Rejected |

## 7. Migration and regression checks

- Apply migrations 0001, 0002, 0003, 0004, 0005, and 0006 in numeric order to a fresh disposable database.
- Verify upgrade from a representative pre-0006 database; preserve all historical allocation rows.
- Verify migration 0006 preflight rejects ambiguous legacy reversal rows rather than reinterpreting them.
- Run PRAGMA foreign_key_check after migration and after fixtures.
- Verify append-only triggers, unique idempotency indexes, expected-version indexes, source linkage, and invoice net-balance guards.
- Run the existing migration, auth, audit, stock, and allocation regression suites.
- Do not claim remote D1 behavior based only on SQLite or local Wrangler results.

## 8. Remote V2 STAGING verification checklist

Before any remote migration:
- Confirm the configured Cloudflare account ID belongs to the separate V2 account.
- Verify read access to that account, D1, and Workers using the read-only access-check workflow.
- Confirm the target D1 database is isolated V2 STAGING, not V1 or production.
- Take and verify a backup/export before migration.
- Verify tenant accounting-period coverage and migration preflight.
- Apply migration 0006 only after explicit approval.
- Run remote D1 concurrency, rollback, audit-chain, cursor, and idempotency-replay checks.
- Reconcile source availability and invoice balances after every test.
- Do not deploy the Worker until all remote tests pass and deployment is separately authorized.

## 9. Current status and non-claims

- Local migration, Worker-adapter, and Wrangler local D1 test layers have passed at the recorded commit heads.
- The latest Worker CI after test-suite separation passed 41/41 Worker tests and 10/10 Wrangler local D1 integration tests, including ADMIN2 parity and CLOSED-period correction.
- Remote Cloudflare D1 STAGING verification has not been performed.
- Migration 0006 has not been applied remotely; no Worker deployment has occurred.
- No Android source, APK, V1 resource, production database, or production credential has been modified.
