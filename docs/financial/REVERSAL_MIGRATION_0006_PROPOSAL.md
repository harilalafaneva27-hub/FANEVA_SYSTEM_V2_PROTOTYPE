# Migration 0006 — Financial Allocation Reversal (Implementation Record)

**Status:** Implemented in Draft PR #32. Worker endpoint implemented in Draft PR #34. Migration 0006 has passed local schema validation but has not been applied to a remote D1 database.

**Target:** Isolated FANEVA SYSTEM V2 STAGING only. Never apply to V1 or production.

## 1. Why migration 0006 is required

The initial schema's reversal_of_id self-references establish a link but do not by themselves enforce:
- cumulative multi-row reversal limits;
- optimistic concurrency per original allocation;
- source availability restoration without double spending;
- net invoice outstanding after reversals;
- accounting-period immutability;
- atomic audit, change-log cursor, and receipt behavior.

Migration 0006 adds these controls without rewriting historical allocation rows.

## 2. Approved business rules

1. Original posted allocations remain immutable.
2. Multiple partial reversal rows may reference one original allocation.
3. Successful posted reversals cannot exceed the original allocation amount in total.
4. Failed, rejected, or rolled-back requests do not consume reversible capacity.
5. Debt-payment reversal restores unapplied availability against the original payment; it does not create a second receipt or cash refund.
6. Customer-credit reversal restores availability through a source-linked positive credit ledger row; it does not mint duplicate credit principal.
7. Invoice outstanding is based on net posted allocations after successful reversals and remains subject to posted credit notes and collectible-balance guards.
8. No automatic cascade reversal. A restored source can be consumed only once through its exact source ID.
9. CLOSED accounting periods are immutable. A correction is recorded in the next OPEN period; if none exists, reject.
10. ADMIN only may authorize reversal. ADMIN1 and ADMIN2 have equal authority; GERANT and VENDEUR are denied.
11. Assigned-store scope, active device, tenant identity, and source identity are enforced.
12. Amounts are whole MGA integers within the safe-integer ceiling.

## 3. Migration 0006 schema changes

The migration adds:

- **migration_0006_preflight:** fails closed if legacy allocation reversal links, REVERSED allocation rows, or customer-credit REVERSAL ledger rows already exist with ambiguous historical semantics.
- **accounting_periods:** tenant-scoped non-overlapping periods, close metadata, and immutable CLOSED periods.
- **payment_availability_ledger:** append-only positive availability rows linked to the exact debt-payment reversal allocation, payment, customer, store, parent availability entry, period, reason, actor, device, idempotency key, and request hash.
- **allocation_reversal_state:** per-original original amount, cumulative reversed amount, and version projection.
- **debt_payment_allocations metadata:** payment availability source reference, reversal reason, period, device, idempotency key, request hash, and expected version.
- **customer_credit_allocations metadata:** reversal reason, period, device, idempotency key, request hash, and expected version.
- **customer_credit_ledger metadata:** parent credit source, accounting period, device, idempotency key, and request hash.

The original allocation tables and their posted rows remain append-only.

## 4. Database guards and indexes

Migration 0006 replaces the earlier gross-allocation guards with net-allocation guards that subtract successful posted reversal rows. It adds guards for:
- original allocation source, customer, invoice, and store consistency;
- positive whole-MGA reversal amount and remaining reversible capacity;
- ADMIN account, active device, and assigned-store authorization;
- eligible source and target accounting periods;
- reversal reason, idempotency key, request hash, and expectedVersion;
- customer-credit reversal ledger source linkage;
- payment-availability ledger source linkage;
- allocation_reversal_state creation and advancement only with a matching posted original/reversal;
- append-only payment availability and immutable CLOSED periods;
- credit-note posting against net invoice collectible balance.

Unique indexes enforce tenant/device/idempotency uniqueness, one restored availability entry per debt-payment reversal, one customer-credit reversal source entry per reversal, and one reversal per original allocation per expectedVersion. There is intentionally no unique constraint on reversal_of_id alone, because multiple partial reversals are approved.

## 5. Worker transaction algorithm

The Worker endpoint validates the active principal and request, resolves same-key replay before checking the current version, and inserts the reversal allocation. Database triggers append the matching restored availability entry and advance the per-original reversal state.

The same D1 batch includes:
1. reversal allocation insert;
2. audit event insert;
3. change-log insert, which advances tenant_sync_state through its database trigger;
4. mutation receipt insert.

Any failed statement rolls back the batch. A stale version returns VERSION_CONFLICT. A transient tenant audit/cursor collision is reported as a retryable atomic-batch failure; no partial writes survive.

## 6. Executable verification

- Migration suite: 53 total tests passed on PR #32 head 666eed4afebe4e4b68fa27cafc202bb730d52067, run #44079174076.
- Worker adapter suite: 41 total tests passed on PR #34 head 5d2c7c3b5356ac6e379470629c64ed8f89fbc2d5, run #44079307089.
- Wrangler local D1 integration suite: 9/9 passed on PR #34 head fcf3756f87cbf41a6074ba2c8e85ad8728451c8f, run #44082668698. It applied migrations 0001–0006 and exercised the Worker through a local D1 binding, including concurrent requests and injected rollback.
- Latest Worker CI must also pass after the final test workflow separation change.

These results are local validation only. They do not establish remote Cloudflare D1 concurrency or production readiness.

## 7. Remaining gates before merge or remote staging

- Review the complete migration sequence 0001–0006 and run foreign_key_check against the isolated V2 STAGING database.
- Verify accounting-period coverage for the target tenant.
- Run remote D1 concurrent reversal, restored-availability allocation, rollback, audit-chain, cursor, and receipt-replay tests.
- Verify the exact Cloudflare account ID is the separate V2 account; never use V1 resources.
- Obtain explicit approval before merging PR #32 or PR #34.
- Obtain separate explicit authorization before applying migration 0006 or deploying the Worker.

## 8. Scope and safety

No remote SQL has been applied. No Worker has been deployed. No V1 resource, Android source, APK, credential, or production data has been changed. The migration is forward-only and must be applied only to an isolated V2 STAGING database after all gates and approvals are satisfied.
