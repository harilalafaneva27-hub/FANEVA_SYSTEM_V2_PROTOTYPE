# PR #14 — Financial Allocation Reversal Protocol (Design Gate)

Status: **design/test specification only**. This document does not change schema, allocation arithmetic, API behavior, or production data.

## 1. Non-negotiable invariants

1. Original posted allocation rows are immutable and remain part of the audit history.
2. A reversal is a new append-only record linked to exactly one original allocation.
3. A reversal must be tenant-scoped and must reference an allocation belonging to the same tenant and compatible financial source.
4. The cumulative amount reversed for an original allocation must never exceed its original posted amount.
5. The same original allocation may not be reversed twice for the same amount. Whether multiple partial reversals are permitted is a business-rule decision; until approved, tests must not assume either policy.
6. Reversal processing must be atomic with its audit event, change-log cursor advancement, and mutation receipt.
7. Idempotent replay of the same mutation returns the original receipt and must not create a second reversal.
8. Concurrent attempts must not cause the cumulative reversed amount to exceed the original amount.
9. No last-write-wins behavior. Version conflicts must be explicit and leave all records unchanged.
10. A reversal must not silently rewrite historical invoice, customer-credit, payment, or adjustment records.

## 2. Required domain decisions before implementation

The following are **blocking decisions**, not implementation assumptions:

- Is reversal permitted only as a full reversal, or may it be partial?
- If partial reversal is allowed, may one original allocation have multiple reversal rows?
- Which roles may authorize a reversal, and does it require a reason/reference?
- Does a reversal restore payment availability, customer-credit availability, invoice outstanding, or some combination? Define this separately for each allocation type.
- How are downstream allocations handled when their source allocation is reversed?
- Are reversals allowed after later settlements, credit notes, adjustments, or period close?
- What is the exact rounding policy? MGA values remain whole non-negative integers.
- What is the required expectedVersion scope and conflict response?

No schema or balance formula should be changed until these decisions are approved.

## 3. Test matrix

| ID | Scenario | Expected result |
|---|---|---|
| R01 | Reversal references missing allocation | Reject; no writes |
| R02 | Reversal references allocation in another tenant | Reject; no information leak |
| R03 | Reversal amount is zero, negative, fractional, or unsafe integer | Reject; no writes |
| R04 | Reversal amount exceeds original remaining reversible amount | Reject; no writes |
| R05 | Exact full reversal | Accept only if full reversal policy is approved |
| R06 | Partial reversal | Accept/reject according to approved policy |
| R07 | Second reversal against same original | Enforce approved single/multiple-reversal policy |
| R08 | Cumulative partial reversals exceed original | Reject atomically |
| R09 | Duplicate idempotency key, same request hash | Return original receipt; no duplicate row |
| R10 | Duplicate idempotency key, different request hash | Reject as idempotency conflict |
| R11 | Two concurrent reversals whose sum exceeds original | At most valid amount commits; other conflicts/rejects |
| R12 | Audit insert fails | Reversal and all related writes roll back |
| R13 | Change-log/cursor update fails | Reversal and all related writes roll back |
| R14 | Receipt insert fails | Reversal and all related writes roll back |
| R15 | Expected version is stale | VERSION_CONFLICT; no writes |
| R16 | Attempt to UPDATE/DELETE original allocation | Reject |
| R17 | Attempt to UPDATE/DELETE reversal row | Reject |
| R18 | Replay after successful commit | Same outcome/receipt; no additional effects |
| R19 | Reversal after dependent allocation exists | Apply explicitly approved dependency policy; never silently corrupt balances |
| R20 | Cross-store or unauthorized actor | Reject according to assigned store scope and permission policy |

## 4. Verification layers

### SQL / migration tests
- Foreign-key and tenant-isolation checks.
- Append-only triggers.
- Unique/partial-index behavior, if approved by the final policy.
- Whole-MGA integer constraints.
- Rollback behavior.

### Worker / local D1 integration tests
- Mutation, reversal row, audit event, change-log cursor, and receipt in one atomic transaction.
- Idempotent replay and request-hash mismatch.
- Concurrent mutation and expectedVersion conflict.
- Injected failure at each write boundary.

### Staging verification
- Only after explicit approval of the business decisions and a passing local integration suite.
- Use an isolated V2 STAGING account/database; never V1 or production.
- Verify audit-chain continuity and reconciliation before considering deployment.

## 5. Merge gate

This specification PR must not be treated as implementation completion. PR #14 remains unmergeable until:
1. Domain decisions in section 2 are approved.
2. Tests in section 3 are implemented and pass.
3. Worker/D1 atomicity and audit/cursor/receipt integration are demonstrated.
4. The PR branch is synchronized with its current base and all required CI checks pass.
5. A separate explicit merge approval is received.

## 6. Scope protections

- No V1 files, credentials, workers, databases, or production resources.
- No APK changes.
- No Cloudflare remote D1 migration or deployment.
- No changes to the existing allocation formulas or customer debt balances in this design-only step.
