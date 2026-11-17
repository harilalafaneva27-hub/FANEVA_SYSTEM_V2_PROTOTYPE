# PR #14 — Financial Allocation Reversal Protocol (Design Gate)

Status: **design/test specification only**. This document records the approved reversal cardinality policy and does not change schema, allocation arithmetic, API behavior, or production data.

## 1. Approved policy — Option B

The following reversal rules are approved:

1. Original posted allocation rows are immutable and remain part of the audit history.
2. A reversal is a new append-only record linked to exactly one original allocation (for example, through `reversal_of_id`).
3. **Partial reversal is permitted.**
4. **Multiple partial reversal rows may reference the same original allocation.** A one-reversal-per-original uniqueness constraint is therefore invalid for this policy.
5. The sum of successful posted reversals for an original allocation must never exceed its original posted amount.
6. The remaining reversible amount is conceptually: **original posted amount − sum of successful posted reversal amounts**. This is a guard calculation, not a decision about invoice/payment/customer balance restoration.
7. Reversal records must be tenant-scoped and reference an allocation belonging to the same tenant and compatible financial source.
8. The original allocation must never be updated or deleted to represent a reversal.
9. Idempotent replay of the same mutation returns its original receipt and must not create another reversal row.
10. A repeated legitimate partial reversal is allowed when it has a distinct authorized request/idempotency key and remains within the remaining reversible amount.
11. Concurrent attempts must be serialized or conditionally guarded so their combined successful posted amount cannot exceed the original amount.
12. Reversal processing must be atomic with its audit event, change-log cursor advancement, and mutation receipt.
13. No last-write-wins behavior. Version conflicts must be explicit and leave all records unchanged.

Only successful posted reversals count toward the cumulative reversed amount. Failed, rejected, or uncommitted attempts must not consume reversible capacity.

## 2. Approved domain decisions (2026-11-17)

The implementation follows these approved rules:

- Authorization: ADMIN only; ADMIN1 and ADMIN2 have equal authority. GERANT and VENDEUR are denied. Assigned-store scope is enforced.
- Debt-payment reversal: the payment remains received. A source-linked positive entry is appended to payment_availability_ledger; it is not a cash refund or a second payment receipt.
- Customer-credit reversal: a source-linked positive REVERSAL entry is appended to customer_credit_ledger; it does not mint duplicate credit principal.
- Invoice outstanding: computed from net posted allocations (original allocations less successful posted reversals), with debt-payment and customer-credit allocations sharing the same cap.
- Downstream allocations: no cascade or automatic reversal. Restored availability is a distinct source and can be consumed once. Any operation violating source or invoice caps is rejected atomically.
- Accounting periods: CLOSED periods are immutable. A correction for an allocation in a CLOSED period is recorded in the next OPEN period; if none exists, reject.
- Optimistic concurrency: expectedVersion is scoped to the original allocation's reversal state. Stale versions return VERSION_CONFLICT.
- Eligible allocation types: posted debt-payment allocations and posted customer-credit allocations. Other financial document types require their own protocol.
- A reason, actor/device identity, timestamp, idempotency key, canonical request hash, audit event, change-log cursor and receipt are mandatory.

MGA values remain whole non-negative integers. No schema, balance formula, or downstream financial behavior should be changed until the remaining decisions are approved.


## 2A. Approved financial projection semantics (approved 2026-11-17)

The following projection behavior is now explicitly approved for reversal of an allocation:

1. **Debt-payment allocation reversal:** the received debt payment remains received. The successfully reversed allocation amount becomes an unapplied payment credit associated with that same payment/customer/store source; it is not a cash refund and must not be counted as a second receipt.
2. **Customer-credit allocation reversal:** the successfully reversed amount becomes available customer credit again, linked to the original credit source; it must not create duplicate credit principal.
3. **Invoice outstanding:** increases by the successful posted reversal amount for the affected invoice, subject to the invoice's other valid credits/adjustments and the existing collectible-balance rules.
4. The original allocation remains immutable. Each successful reversal is a separate append-only record. Projection changes must be derived from the net allocation effect (original allocation less successful posted reversals), not by mutating the original row.
5. Reversal must not create a cash refund, reverse the source payment itself, or duplicate customer-credit principal unless a separate approved transaction explicitly does so.

### Required projection tests

| ID | Scenario | Expected result |
|---|---|---|
| F01 | Partially reverse a debt-payment allocation | Payment remains received; reversed amount becomes unapplied payment credit; invoice outstanding increases by that amount |
| F02 | Multiple partial reversals of the same debt-payment allocation | Unapplied payment credit increases by the sum of successful reversals only; no duplicate receipt |
| F03 | Fully reverse a debt-payment allocation | Entire original allocation is unapplied; payment remains received; invoice outstanding reflects the reversal |
| F04 | Partially reverse a customer-credit allocation | Available customer credit is restored by the successful reversal amount; invoice outstanding increases equally |
| F05 | Multiple partial reversals of the same customer-credit allocation | Available credit is restored cumulatively without duplicating original credit principal |
| F06 | Fully reverse a customer-credit allocation | Full original amount becomes available again; invoice outstanding reflects the reversal |
| F07 | Replay same reversal idempotency key and request hash | No second reversal, no duplicate available balance, same receipt |
| F08 | Reuse idempotency key with different request hash | Reject conflict; no projection change |
| F09 | Concurrent reversals near cumulative cap | Only commits within remaining reversible amount; projections equal committed reversal total |
| F10 | Audit, cursor, or receipt write fails | Reversal and all projection/ledger writes roll back together |
| F11 | Attempt to reverse more than remaining amount | Reject; no ledger or projection changes |
| F12 | Subsequent allocation consumes restored unapplied payment credit | Allowed only within the available unapplied amount and the invoice outstanding cap |
| F13 | Subsequent allocation consumes restored customer credit | Allowed only within the available credit and invoice outstanding cap |

The implementation must ensure that restored unapplied payment credit and restored customer credit are represented as auditable source-linked ledger effects. It must not simply reduce the historical original allocation sum or rewrite immutable rows.

### Implementation boundary still requiring explicit design

The approved financial semantics do not yet specify:
- The exact ledger event schema and source-link columns for the restored unapplied payment credit.
- Whether the current `customer_credit_ledger` representation is sufficient for both restored payment availability and restored customer-credit availability, or whether a separate payment-availability ledger is needed.
- The exact formula/query used by allocation guards to compute net consumed source amount after reversals.
- Handling of downstream allocations, period close, later settlements, and credit notes.

These are implementation-design tasks. Do not silently assume the current schema already supports them. No SQL migration or Worker behavior is authorized by this specification update alone.

## 3. Required test matrix

| ID | Scenario | Expected result |
|---|---|---|
| R01 | Reversal references missing allocation | Reject; no writes |
| R02 | Reversal references allocation in another tenant | Reject; no information leak |
| R03 | Reversal amount is zero, negative, fractional, or unsafe integer | Reject; no writes |
| R04 | First partial reversal below original amount | Accept; original remains unchanged |
| R05 | Second distinct partial reversal against the same original | Accept if cumulative total remains within original amount |
| R06 | Several partial reversals exactly reach the original amount | Accept; remaining reversible amount becomes zero |
| R07 | Any further positive reversal after full cumulative reversal | Reject; no writes |
| R08 | A single reversal exceeds the remaining reversible amount | Reject atomically |
| R09 | Two or more partial reversals cumulatively exceed original amount | Reject the excess attempt atomically |
| R10 | Same idempotency key and same request hash replayed | Return original receipt; do not create another row |
| R11 | Same idempotency key reused with a different request hash | Reject as idempotency conflict |
| R12 | Distinct idempotency keys for legitimate partial reversals | Permit multiple rows, subject to cumulative cap |
| R13 | Concurrent reversals whose combined amount exceeds remaining amount | At most the amount within the cap commits; other attempt conflicts/rejects |
| R14 | Audit insert fails | Reversal and all related writes roll back |
| R15 | Change-log/cursor update fails | Reversal and all related writes roll back |
| R16 | Receipt insert fails | Reversal and all related writes roll back |
| R17 | Expected version is stale | VERSION_CONFLICT; no writes |
| R18 | Attempt to UPDATE/DELETE original allocation | Reject |
| R19 | Attempt to UPDATE/DELETE a posted reversal row | Reject |
| R20 | Reversal after dependent allocation exists | Apply only an explicitly approved dependency policy; never silently corrupt balances |
| R21 | Cross-store or unauthorized actor | Reject according to assigned store scope and permission policy |

## 4. Idempotency, uniqueness, and concurrency constraints

- Idempotency uniqueness applies to the mutation/request key within its defined tenant/device scope, not to `reversal_of_id` alone.
- Multiple reversal rows for one original allocation are valid under Option B; do not add a unique constraint that permits only one reversal row per original.
- Bind the idempotency key to a canonical request hash. Same key + same hash replays the original receipt; same key + different hash is a conflict.
- Enforce the cumulative cap inside the same atomic transaction as reversal insertion. A read-then-write check outside the transaction is insufficient.
- The cap must be evaluated against successful posted reversals for the same tenant and original allocation, including concurrent requests.
- The exact database constraint/locking strategy is an implementation choice to be validated by local D1 integration tests; this document does not prescribe a migration.

## 5. Verification layers

### SQL / migration tests
- Foreign-key and tenant-isolation checks.
- Append-only protections for original allocations and posted reversal rows.
- Multiple reversal rows referencing one original are accepted.
- Idempotency-key uniqueness and request-hash conflict behavior.
- Whole-MGA integer constraints and rollback behavior.
- Cumulative successful posted reversal amount never exceeds the original amount.

### Worker / local D1 integration tests
- Mutation, reversal row, audit event, change-log cursor, and receipt in one atomic transaction.
- Idempotent replay and request-hash mismatch.
- Concurrent partial reversals and expectedVersion conflict.
- Injected failure at each write boundary.
- Verify original allocation immutability and cumulative cap after every committed operation.

### Financial projection tests — blocked pending domain decisions
For each allocation type, separately specify and test the effects on payment availability, customer-credit availability, invoice outstanding, and dependent allocations. Do not assume that all types restore the same balances.

### Staging verification
- Only after remaining domain decisions are approved and a passing local integration suite exists.
- Use an isolated V2 STAGING account/database; never V1 or production.
- Verify audit-chain continuity and reconciliation before considering deployment.

## 6. Merge gate

This specification PR must not be treated as implementation completion. PR #14 remains unmergeable until:
1. Remaining domain decisions in section 2 are approved.
2. Tests in section 3 are implemented and pass.
3. Worker/D1 atomicity and audit/cursor/receipt integration are demonstrated.
4. The PR branch is synchronized with its current base and all required CI checks pass.
5. A separate explicit merge approval is received.

## 6. Repository audit findings (read-only, 2026-11-17)

The following findings are based on the current PR #14 / PR #15 diffs and the initial schema/migration files inspected for this audit:

1. The initial schema already contains `reversal_of_id` self-references on `debt_payment_allocations` and `customer_credit_allocations`; `credit_notes` also has a self-reference. These foreign keys establish a link only. They do not enforce a reversal amount cap, a reversal lifecycle, or correct balance restoration.
2. `debt_payment_allocations` and `customer_credit_allocations` are append-only in the initial schema. Their existing rows cannot be updated to change a posted original allocation into a reversed state.
3. PR #14's migration 0003 calculates payment and invoice caps from rows whose status is `POSTED`. It does not subtract successful reversal amounts or calculate a remaining reversible amount. Its customer-credit source cap likewise sums posted applications only.
4. Consequently, the current schema plus migration 0003 does **not yet implement Option B**. Merely inserting a linked reversal row is insufficient: a row marked `REVERSED` is not included in the posted-allocation sums, while the original posted row remains counted; a positive `POSTED` row would increase the summed allocation rather than reverse it.
5. PR #15 migration 0004 maintains `stock_balances` from `stock_movements` using SQLite triggers and rejects negative stock. Its tests cover ledger/projection consistency and rollback of that statement/transaction. The PR description explicitly does not prove the complete Worker transaction containing stock, audit event, change-log cursor, and mutation receipt.
6. The original PR #16 branch remains divergent/dirty and is retained as a backup. Its reconciled replacement is PR #30, based on the PR #15 head. PR #31 carries the Worker audit-crypto module on top of PR #30. PR #32 adds migration 0006, and PR #34 carries the reversal endpoint on top of PR #32. These replacement branches preserve the original branches; no force-push was used.

### Audit consequence

Option B is approved as a business rule, but implementation remains blocked. A follow-up implementation design must choose an explicit append-only reversal representation and define how the payment/source-availability and invoice-outstanding projections net original allocations against successful posted reversals. It must also prove idempotency, cumulative caps under concurrency, audit/cursor/receipt atomicity, and replay behavior. No SQL or Worker change is authorized by this audit note.

## 7. Scope protections

- No V1 files, credentials, workers, databases, or production resources.
- No APK changes.
- No Cloudflare remote D1 migration or deployment.
- No changes to existing allocation formulas or customer debt balances in this design-only step.


## 6A. Implementation evidence (2026-11-17)

- PR #30: migration 0005 and reconciled stock/audit test suite; D1 Schema Validation SUCCESS.
- PR #31: Worker audit/auth/idempotency module on the reconciled base; Worker Audit Crypto Tests SUCCESS.
- PR #32: migration 0006 and 18 reversal-specific migration tests; D1 Schema Validation Run #44079174076 SUCCESS, 53 total tests.
- PR #34: ADMIN-only reversal endpoint and SQLite-backed Worker integration tests; Worker Audit Crypto Tests Run #44079307089 SUCCESS, 41 total tests.
- These are local SQLite/Worker-adapter CI results. They do not prove concurrency against a live Cloudflare D1 database. Isolated V2 STAGING verification remains a deployment gate.
- PRs #14, #15, #30, #31, #32 and #34 remain Draft/Open pending explicit merge approval. No remote D1 migration or deployment has occurred.
