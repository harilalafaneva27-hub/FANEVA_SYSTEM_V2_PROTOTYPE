# Reversal Migration Test Plan

Status: TEST SPECIFICATION ONLY — no SQL or runtime implementation.
Target: a future forward-only migration after the open design gates in REVERSAL_LEDGER_NET_PROJECTION_DESIGN.md are approved.

## Test harness requirements

- Apply migrations 0001, 0002, 0003, then the proposed new migration in order to a fresh SQLite database with foreign keys enabled.
- Never edit or rewrite historical migrations 0001–0003.
- Seed synthetic tenants, stores, customers, credit invoices, posted debt payments, and positive customer-credit source entries.
- Assert database state and all source-linked ledger rows after each operation.
- Run a separate deterministic concurrency/integration suite against the actual D1 transaction path; SQLite in-memory tests alone do not prove Cloudflare D1 concurrency behavior.

## A. Reversal cap and append-only behavior

| ID | Setup / operation | Expected |
|---|---|---|
| REV-001 | Original posted allocation of 1,000 MGA; reverse 300 | One reversal of 300; original row unchanged; remaining reversible = 700 |
| REV-002 | Same original; then reverse another 200 | Two reversal rows; cumulative = 500; remaining = 500 |
| REV-003 | Reverse exact remaining 500 | Accepted; cumulative reversal = 1,000; remaining = 0 |
| REV-004 | Attempt one additional MGA | Rejected; no reversal or projection rows added |
| REV-005 | Single reversal equal to original | Accepted as full reversal |
| REV-006 | Attempt to reverse a reversal row | Rejected |
| REV-007 | Attempt reversal across tenant/customer/store/payment/credit source/invoice | Rejected; no writes |
| REV-008 | Attempt reversal of missing, DRAFT, non-POSTED, or invalid target | Rejected |
| REV-009 | Update/delete original allocation or posted reversal | Rejected by append-only protections |
| REV-010 | Failed/rejected reversal attempt | Does not contribute to successful cumulative reversal sum |

## B. Debt-payment availability projection

| ID | Setup / operation | Expected |
|---|---|---|
| PAY-001 | Payment received 1,000; allocation 700; reverse 250 | Payment received remains 1,000; unapplied availability = 550; invoice outstanding increases by 250 |
| PAY-002 | Another partial reversal of 150 | Unapplied availability = 700; no second payment receipt |
| PAY-003 | Allocate 600 from restored availability | Remaining unapplied = 100; allocation references exact availability source |
| PAY-004 | Attempt to allocate 101 more | Rejected; no negative availability |
| PAY-005 | Check original payment residual and restored availability together | Total spendable payment availability equals 1,000 minus currently net allocated amount; no double count |
| PAY-006 | Reverse and then reallocate restored amount | Invoice outstanding decreases only by the new posted allocation amount |

## C. Customer-credit availability projection

| ID | Setup / operation | Expected |
|---|---|---|
| CRD-001 | Credit source 800; allocation 600; reverse 250 | Restored customer credit = 250; original credit principal remains 800 |
| CRD-002 | Another partial reversal 100 | Restored customer credit totals 350, with distinct source-linked entries |
| CRD-003 | Allocate 300 from restored credit | Remaining restored credit = 50 |
| CRD-004 | Attempt to allocate 51 more | Rejected |
| CRD-005 | Check original source plus restored entries | Total spendable credit equals original principal less net consumed amount; no duplication |
| CRD-006 | Verify customer/store/tenant source linkage | Cross-scope credit allocation rejected |

## D. Invoice outstanding and cross-source cap

| ID | Setup / operation | Expected |
|---|---|---|
| INV-001 | Invoice 1,000; debt allocation 400; reverse 150 | Outstanding increases by 150 |
| INV-002 | Apply customer credit 200 after reversal | Accepted only within resulting outstanding |
| INV-003 | Mix debt payment and customer credit allocations | Combined net allocations never exceed invoice collectible amount |
| INV-004 | Apply posted credit note | Outstanding decreases by posted credit-note amount; reversal calculation remains consistent |
| INV-005 | Reallocate restored payment/credit | Outstanding decreases by newly posted allocation only |
| INV-006 | Reversal violates invoice collectible constraints | Rejected atomically |

## E. Idempotency, concurrency, and atomicity

| ID | Operation | Expected |
|---|---|---|
| IDEM-001 | Repeat same idempotency key and canonical request hash | Same receipt; exactly one reversal and one restored availability entry |
| IDEM-002 | Reuse key with a different request hash | Conflict; no extra writes |
| CON-001 | Concurrent partial reversals whose combined amount exceeds remaining reversible | Committed total never exceeds original amount; excess request rejects/conflicts |
| CON-002 | Concurrent allocations against same restored availability | Combined committed allocation never exceeds available amount |
| CON-003 | Concurrent reversal and allocation against same source | Final net availability is nonnegative; no amount is spendable twice |
| ATOM-001 | Force availability-ledger insert failure | Reversal, audit, cursor, receipt all roll back |
| ATOM-002 | Force audit or change-log/cursor failure | Reversal and availability projection roll back |
| ATOM-003 | Force receipt write failure | Entire mutation rolls back |
| ATOM-004 | Replay after successful commit | Original outcome returned; no duplicate event/cursor movement |

## F. Closed-period, downstream allocation, and authorization policy tests

| ID | Operation | Expected |
|---|---|---|
| PER-001 | ADMIN1 requests a valid reversal in an OPEN period | Allowed if all financial caps and expected-version checks pass |
| PER-002 | ADMIN2 requests a valid reversal in an OPEN period | Same authority as ADMIN1; allowed under same guards |
| PER-003 | GERANT or VENDEUR requests a reversal | Denied; no writes |
| PER-004 | Reversal request targets a CLOSED accounting period | Denied; no historical period mutation |
| PER-005 | Reversal is recorded in next OPEN period | Correction event references original transaction and original closed period; original remains immutable |
| PER-006 | No OPEN period exists | Denied until a governed period is opened |
| DSA-001 | A restored availability entry is consumed by a later allocation | Allowed once, with exact source linkage and available-balance cap |
| DSA-002 | Further reversal would overdraw availability or exceed invoice collectible balance | Rejected atomically; no cascade to later allocations |
| DSA-003 | Later allocation exists when a prior reversal is requested | Later allocation remains immutable; reversal is allowed only if net source and invoice constraints remain valid |

## G. Migration and regression checks

- Fresh install applies migrations in numeric order with no foreign-key violations.
- Existing v1-v3 database upgrades using only the new forward migration and preserves all historical rows.
- Migration runner records application and prevents accidental reapplication.
- Existing allocation cap tests continue to pass.
- Existing invoice outstanding, credit-note, tenant-isolation, append-only, audit, change-log, and receipt tests continue to pass.
- PRAGMA foreign_key_check returns no rows after migration and after each test fixture.
- No test assumes Worker/D1 atomicity based solely on local SQLite behavior.

## Explicit non-claims

This test plan does not prove that a proposed migration exists or passes. The policy decisions for downstream allocations, closed accounting periods, and reversal authorization are fixed in the companion design document and are covered by section F above. The physical payment-availability ledger representation and its exact source-linkage constraints still require schema-level review before SQL is written. Expected-version scope and the deterministic D1 concurrency guard must also be specified and proven in the runtime integration suite. No SQL, Worker, Android, remote D1, deployment, APK, or V1 changes are included.
