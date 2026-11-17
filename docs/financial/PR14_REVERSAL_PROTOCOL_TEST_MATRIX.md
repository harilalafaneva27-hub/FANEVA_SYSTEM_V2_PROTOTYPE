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

## 2. Decisions still required before implementation

Option B settles partial/multiple reversal cardinality and the cumulative cap only. These remain blocking decisions; do not infer them:

- Which roles may authorize a reversal, and is a reason/reference mandatory?
- Does reversal restore payment availability, customer-credit availability, invoice outstanding, or some combination? Define separately for each allocation type.
- How are downstream allocations handled when their source allocation is reversed?
- Are reversals allowed after later settlements, credit notes, adjustments, or period close?
- What is the required expectedVersion scope and exact conflict response?
- Which allocation types are reversible, and what source-compatibility rules apply?

MGA values remain whole non-negative integers. No schema, balance formula, or downstream financial behavior should be changed until the remaining decisions are approved.

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
6. The PR #14 branch is currently divergent from its base: comparison reports 2 commits ahead and 1 behind. PR #15 is 3 commits ahead of PR #14's head with no behind commits. This branch ancestry must be reconciled deliberately before merge; do not force-push or retarget without review.

### Audit consequence

Option B is approved as a business rule, but implementation remains blocked. A follow-up implementation design must choose an explicit append-only reversal representation and define how the payment/source-availability and invoice-outstanding projections net original allocations against successful posted reversals. It must also prove idempotency, cumulative caps under concurrency, audit/cursor/receipt atomicity, and replay behavior. No SQL or Worker change is authorized by this audit note.

## 7. Scope protections

- No V1 files, credentials, workers, databases, or production resources.
- No APK changes.
- No Cloudflare remote D1 migration or deployment.
- No changes to existing allocation formulas or customer debt balances in this design-only step.
