# Financial Allocation Reversal — Approved Protocol & Verification Matrix

**Status:** Implemented in Draft PR #32 (migration 0006) and Draft PR #34 (Worker endpoint). This document describes the current implementation and its remaining verification gates. It is not authorization to merge, migrate a remote database, or deploy.

## 1. Approved business policy — Option B

1. Original posted allocation rows remain immutable.
2. Each reversal is a new posted allocation row linked to exactly one original allocation.
3. Partial reversals are allowed.
4. Multiple partial reversals may reference the same original allocation.
5. The cumulative amount of successful posted reversals must not exceed the original posted amount.
6. Failed, rejected, or rolled-back attempts do not consume reversible capacity.
7. A distinct idempotency key is required for each distinct partial reversal. Same key and same request hash replay the original receipt; same key with a different request hash conflicts.
8. Reversal writes and their financial availability entry, audit event, change-log cursor, and receipt are atomic.
9. No last-write-wins behavior. A stale expectedVersion returns VERSION_CONFLICT.
10. Reversals are tenant-scoped and must match the original financial source, customer, invoice, and store.

## 2. Approved authorization and financial semantics

- **Authorization:** ADMIN only. ADMIN1 and ADMIN2 have equal authority. GERANT and VENDEUR are denied. The active device and assigned-store scope are checked.
- **Debt-payment allocation reversal:** The received payment remains received. A positive entry is appended to payment_availability_ledger and linked to the exact reversal allocation and, where applicable, the parent availability entry. This is not a cash refund or a second payment receipt.
- **Customer-credit allocation reversal:** A positive REVERSAL entry is appended to customer_credit_ledger and linked to the reversal allocation and the credit source. It restores availability without creating duplicate credit principal.
- **Invoice outstanding:** The original allocation remains in history; successful reversal rows reduce net allocations. Invoice outstanding therefore increases by the successfully reversed amount, subject to posted credit notes and existing collectible-balance guards.
- **Downstream allocations:** No automatic cascade reversal. Restored availability is a distinct source and can be consumed only once. A later allocation must reference the exact availability entry.
- **Accounting periods:** CLOSED periods are immutable. If the source period is CLOSED, the correction is recorded in the next OPEN period. If no eligible OPEN period exists, the request is rejected.
- **Eligible source types:** Posted debt-payment allocations and posted customer-credit allocations only. Other financial document types require their own reversal protocol.
- **Amounts:** Whole non-negative MGA integers within the safe-integer ceiling.

## 3. Implemented database and Worker controls

Migration 0006 adds:
- accounting_periods with non-overlap and CLOSED-period immutability guards;
- payment_availability_ledger for restored debt-payment availability;
- allocation_reversal_state as a per-original cumulative amount and expectedVersion projection;
- reversal metadata on the two allocation tables;
- source-linked customer-credit reversal metadata;
- unique idempotency and expected-version indexes;
- triggers validating source, customer, store, period, actor/device, reason, request hash, amount cap, and state advancement;
- net invoice allocation guards that subtract posted reversal rows.

The Worker route is POST /v1/financial/allocations/reversals. It authenticates the device from server-side records, requires ADMIN, checks assigned-store scope, validates the request, resolves idempotent replay, and submits the reversal, audit event, change-log entry, and mutation receipt in one D1 batch. The change-log trigger advances the tenant cursor in the same transaction.

## 4. Required behavior matrix

| ID | Scenario | Expected result |
|---|---|---|
| REV-01 | First partial reversal | Accepted; original row unchanged |
| REV-02 | Multiple partial reversals | Accepted while cumulative amount remains within original amount |
| REV-03 | Exact remaining amount | Accepted; remaining reversible amount becomes zero |
| REV-04 | One MGA beyond remaining amount | Rejected; no writes |
| REV-05 | Reversal of a reversal row | Rejected |
| REV-06 | Same key and same request hash | Original receipt replayed; no duplicate reversal |
| REV-07 | Same key with different request hash | IDEMPOTENCY_CONFLICT |
| REV-08 | Stale expectedVersion | VERSION_CONFLICT; no writes |
| REV-09 | Concurrent reversals against one original | At most one request per expectedVersion commits; cumulative cap is preserved |
| REV-10 | Cross-tenant/source/customer/invoice mismatch | Rejected |
| REV-11 | GERANT or VENDEUR | REVERSAL_FORBIDDEN |
| REV-12 | ADMIN outside assigned store | STORE_SCOPE_DENIED |
| PAY-01 | Debt-payment reversal | Payment remains received; exact amount becomes unapplied availability |
| PAY-02 | Reuse restored payment availability | Allowed only from that exact entry and within its remaining amount |
| CRD-01 | Customer-credit reversal | Positive source-linked credit availability entry appended |
| CRD-02 | Reuse restored customer credit | Allowed only from that exact credit entry and within its remaining amount |
| INV-01 | Mixed payment and customer-credit allocations | Combined net allocations do not exceed collectible invoice balance |
| PER-01 | Reversal in OPEN source period | Posted in that period |
| PER-02 | Source period CLOSED | Correction posted in next OPEN period |
| PER-03 | No next OPEN period | Rejected; historical period remains unchanged |
| ATOM-01 | Availability, audit, change-log, cursor, or receipt write fails | Entire D1 batch rolls back |
| ATOM-02 | Concurrent independent reversals collide on tenant audit/cursor | No partial writes; request can be safely retried |

## 5. Verification evidence

- Migration 0006 disposable-SQLite suite: 53 total tests passed on PR #32 head 666eed4afebe4e4b68fa27cafc202bb730d52067, run #44079174076.
- Worker Audit Crypto suite: 41/41 tests passed on PR #34 head d177c7afcd2c5328d948c6336f6f5db8bd048da6, run #44083656474.
- Wrangler local Cloudflare D1 integration: 10/10 tests passed on PR #34 head d177c7afcd2c5328d948c6336f6f5db8bd048da6, run #44083656317. The additional case verifies ADMIN2's equal authority and correction into the next OPEN period. The workflow applied migrations 0001–0006, started the Worker with a local D1 binding, and exercised atomicity, replay, source restoration, authorization, rollback, and concurrent requests.
- The latest Worker CI after the test-suite separation change passed 41/41 tests; the latest Wrangler local D1 integration passed 9/9 tests.

These results establish local validation. They do not prove behavior against a remote Cloudflare D1 database.

## 6. Remaining release gates

1. Complete all CI checks on the final PR #32 and PR #34 heads.
2. Verify the exact migration sequence and foreign-key integrity against an isolated V2 STAGING database in the separate Cloudflare account.
3. Run remote D1 concurrency, rollback, audit-chain, cursor, and receipt-replay tests.
4. Confirm accounting-period coverage and the staging tenant's migration readiness.
5. Review the final diffs and obtain explicit merge approval before any merge.
6. Obtain separate explicit authorization before applying a remote migration or deploying a Worker.

## 7. Scope protections

- No V1 source, credentials, Worker, D1 database, or production resource is modified.
- No Android source or APK is modified by this reversal work.
- The new local D1 workflow has no Cloudflare secrets and does not create or change remote resources.
- Migration 0006 has not been applied to a remote D1 database.
- PR #32, PR #34, and this documentation PR remain Draft/Open until the gates and explicit approvals are satisfied.
