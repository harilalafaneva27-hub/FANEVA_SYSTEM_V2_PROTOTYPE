# Reversal Ledger — Net Projection Implementation Design

**Status:** Implementation reference for Draft PR #32 (migration 0006) and Draft PR #34 (Worker endpoint). Implemented in the repository and locally tested; not applied to remote D1.

## 1. Goals and invariants

- Original allocation rows are immutable.
- A reversal is a separate posted row linked to exactly one original allocation.
- Multiple partial reversals are supported.
- Successful posted reversals cannot cumulatively exceed the original allocation amount.
- Failed or rejected attempts do not consume reversible capacity.
- Restored availability is represented as a distinct, auditable source and is never simultaneously spendable through the original source residual.
- Reversal, availability ledger entry, audit event, change-log row/cursor, and mutation receipt commit atomically.
- MGA values are safe whole integers.
- No automatic cascade reversal or last-write-wins behavior is permitted.

## 2. Ledger representation

### 2.1 Original allocations and reversal rows

The original rows remain in debt_payment_allocations and customer_credit_allocations. A successful reversal inserts a new row in the same table with reversal_of_id pointing to the original row. The original row is not updated or deleted.

The allocation_reversal_state projection stores, per tenant, allocation type, and original allocation:
- original_amount_mga;
- cumulative reversed_amount_mga;
- optimistic concurrency version;
- update timestamp.

A reversal insert is accepted only when its expectedVersion matches the current state and its amount does not exceed the remaining reversible amount. A unique index on original allocation plus expected version prevents a second reversal from consuming the same version. The state projection advances only as a result of a matching posted reversal.

### 2.2 Restored debt-payment availability

A debt-payment allocation reversal does not undo receipt of the payment and does not create a cash refund or another payment receipt.

Migration 0006 appends one payment_availability_ledger row for each successful reversal. It is linked to:
- the tenant and original debt payment;
- the customer and store;
- the new reversal allocation row;
- the parent availability entry consumed by the original allocation, if there was one;
- accounting period, reason, actor, device, idempotency key, and request hash.

A later debt-payment allocation that consumes restored availability must reference that exact payment_availability_ledger entry. Its available amount is reduced only by posted allocations referencing that exact entry. Reversing that later allocation creates a new child availability entry. This lineage prevents the same restored amount from being spendable both in the parent entry and in a new reversal entry.

For allocations against the original payment's initial unapplied balance, the base payment residual is calculated separately from allocations that reference availability-ledger entries. A reversal adds a new availability source; it does not increase the historical payment receipt amount.

### 2.3 Restored customer-credit availability

A customer-credit allocation reversal appends a positive REVERSAL row in customer_credit_ledger. Its source_type identifies CUSTOMER_CREDIT_ALLOCATION_REVERSAL, source_id points to the new reversal allocation, and parent_credit_entry_id points to the exact credit source consumed by the original allocation.

The restored credit is a distinct positive source. A later customer-credit allocation must reference that source row. The original credit principal is not changed and is not duplicated.

## 3. Net invoice projection

For the two implemented allocation types, net allocated amount is the sum of original posted allocations less successful posted reversal rows. Posted credit notes are deducted separately.

The migration's allocation and credit-note guards use this net effect when checking the collectible invoice balance. Consequently:
- a reversal restores the same amount of collectible invoice balance;
- a later allocation reduces outstanding only by its newly posted amount;
- debt-payment and customer-credit allocations share the invoice cap;
- a credit note cannot push the net collectible balance below zero;
- a reversal does not cascade into later allocations.

A reversal is rejected if its resulting source availability or invoice state would violate an existing guard. Other adjustment and credit-note reversal types require their own explicitly defined protocols.

## 4. Accounting-period behavior

Migration 0006 adds tenant-scoped accounting_periods with non-overlap and CLOSED-period immutability guards.

The source period is resolved from the original allocation timestamp using Antananarivo time (UTC+3). If that period is OPEN, the reversal is posted there. If it is CLOSED, the correction is posted in the next OPEN period whose start is not earlier than the source period's end. The correction retains a link to the original allocation and does not rewrite the closed period. If no eligible OPEN period exists, the request is rejected.

## 5. Worker mutation path

The endpoint is POST /v1/financial/allocations/reversals.

1. Authenticate the active device using the server-side token hash, account, tenant, and active store scope.
2. Require role ADMIN. ADMIN1 and ADMIN2 have equal authority; GERANT and VENDEUR are denied.
3. Validate the JSON object, allocation type, original allocation ID, positive safe MGA integer, reason, expectedVersion, and idempotency key.
4. Resolve a matching posted original allocation and eligible accounting period.
5. Check idempotent replay before current-version validation. Same key/hash returns the stored receipt; a different hash conflicts.
6. Build the reversal allocation insert. Database triggers validate source identity, store scope, period, amount cap, and expectedVersion, then append the restored-availability ledger entry and advance allocation_reversal_state.
7. Append the audit event, change-log row, and mutation receipt in one D1 batch. The change-log trigger advances the tenant cursor in the same transaction.
8. If a concurrent request has already advanced the original allocation version, return VERSION_CONFLICT. If an independent request collides on the tenant audit/cursor sequence, the atomic batch leaves no partial writes and reports a retryable conflict.

## 6. Verification

- Migration 0006 passed the disposable SQLite schema suite: 53 total tests on PR #32 head 666eed4afebe4e4b68fa27cafc202bb730d52067, run #44079174076.
- Worker adapter tests passed: 41 total tests on PR #34 head 5d2c7c3b5356ac6e379470629c64ed8f89fbc2d5, run #44079307089.
- Wrangler local Cloudflare D1 integration passed 9/9 tests on PR #34 head fcf3756f87cbf41a6074ba2c8e85ad8728451c8f, run #44082668698. It applied migrations 0001–0006 and exercised the actual Worker with a local D1 binding.
- Final Worker CI must be green on the latest head after the test-suite separation change.

Local D1 verification is not a substitute for a remote isolated V2 STAGING run. Actual Cloudflare D1 concurrency, transaction rollback, audit-chain continuity, cursor advancement, and receipt replay remain remote staging gates.

## 7. Deployment and merge boundary

The migration and Worker endpoint exist in Draft PRs #32 and #34. They have not been merged or deployed. No remote D1 migration, Worker deployment, Android change, APK build, or V1 change is included. A remote staging migration requires separate explicit authorization and must target only the isolated V2 Cloudflare account.
