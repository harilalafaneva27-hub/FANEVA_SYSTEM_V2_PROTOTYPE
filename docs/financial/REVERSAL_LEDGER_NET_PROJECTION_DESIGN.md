# Reversal Ledger — Net Projection Implementation Design

Status: DESIGN ONLY — not an approved SQL/Worker implementation
Parent context: PR #27 reversal protocol, Option B, and approved financial projection semantics (2026-11-17).

## 1. Goals and invariants

- Original allocation rows are immutable.
- Each reversal is a distinct append-only record linked to one original allocation.
- Multiple partial reversals are permitted; successful posted reversals must never exceed the original amount.
- Failed/rejected attempts do not contribute to the reversed sum.
- Reversal, restored availability, audit event, change-log cursor, and mutation receipt must commit atomically in one tenant-scoped D1 transaction.
- A source amount must be spendable at most once. Restored availability must not also remain available through the original payment/credit source.
- MGA amounts remain integer-only and within the existing safe-integer range.

## 2. Recommended ledger representation

### 2.1 Allocation records

Retain append-only allocation tables, but make the semantic distinction explicit:
- ALLOCATION: original consumption of a payment or credit source.
- ALLOCATION_REVERSAL: compensating record with reversal_of_id referencing the original allocation.

Do not rely on reversal_of_id IS NULL as the only invariant unless every migration and query enforces it consistently. A typed discriminator or equivalent explicit invariant is recommended. Never mutate the original allocation to represent a partial reversal.

### 2.2 Available payment / customer credit

Use source-linked append-only availability ledger entries rather than mutating payment amounts or original credit entries.

- Debt-payment allocation reversal: append a positive PAYMENT_UNAPPLIED availability entry tied to the same tenant, customer, store, original payment, and reversal record. The payment remains received; this is not a cash receipt or refund.
- Customer-credit allocation reversal: append a positive restored-credit entry tied to the original credit source and reversal record. It must be eligible for subsequent customer-credit allocations.
- Each availability entry is consumed by a later allocation referencing that exact entry. Never make the same reversed amount available both by increasing the original source residual and by adding a new availability entry.

The current customer_credit_ledger includes PAYMENT_UNAPPLIED and may represent positive unapplied payment availability, but source linkage and allocation eligibility must prove that a payment amount is represented exactly once. If that cannot be proven, introduce a dedicated payment-availability ledger in a reviewed forward migration.

For restored customer credit, prefer a dedicated positive credit-ledger entry type such as ALLOCATION_REVERSAL_CREDIT, linked to the reversal and original credit entry. The current customer_credit_ledger CHECK allows REVERSAL, but the customer-credit allocation source guard accepts only CREDIT_NOTE_ISSUED and PAYMENT_UNAPPLIED. This mismatch must be resolved in a reviewed migration before implementation.

## 3. Net projection equations

For an original allocation A:
- net_allocated(A) = A.amount - SUM(successful posted reversal amounts referencing A)
- remaining_reversible(A) = net_allocated(A)

For payment source P:
- payment_unapplied(P) = P.amount - SUM(net_allocated(original allocations sourced from P))

For customer-credit source C:
- credit_available(C) = C.positive_amount - SUM(net_allocated(original allocations sourced from C))

For credit invoice S:
- invoice_outstanding(S) = S.total - posted_credit_notes - SUM(net_allocated debt-payment allocations to S) - SUM(net_allocated customer-credit allocations to S)

Only valid posted original/reversal records contribute. Reversal rows are subtracted from their referenced originals; rejected/failed attempts are excluded. These equations are conceptual: implementation must account for the existing status model and source lineage and must not double-count a restored availability ledger entry as well as a reduced original source allocation.

## 4. Transaction and concurrency protocol

Within one tenant-scoped D1 transaction:
1. Authenticate actor/device and authorize tenant, store, customer, payment/credit source, and invoice.
2. Resolve idempotency. Same key + same canonical request hash returns the original receipt; same key + different hash rejects as conflict.
3. Validate target is an original posted allocation, not a reversal row.
4. Use a conditional write or optimistic version guard to serialize the cumulative reversal cap.
5. Validate positive safe MGA integer and amount <= remaining reversible amount.
6. Insert append-only reversal record.
7. Insert exactly one restored availability ledger entry linked to that reversal.
8. Insert audit event, change-log event/cursor, and mutation receipt.
9. Commit atomically; any failed step rolls back all writes.

D1/SQLite concurrency semantics must be proven with a conditional guard or equivalent serialization. A read-then-write check alone is insufficient.

## 5. Required tests

### Allocation/reversal invariants
- Partial reversal followed by another partial reversal.
- Exact remaining amount accepted; one MGA above remaining rejected.
- Full reversal and multiple partial reversals summing exactly to original.
- Reversal row cannot itself be reversed unless a separate protocol is approved.
- No cross-tenant/customer/store/payment/invoice/source reversal.
- No reversal of DRAFT, REVERSED, missing, or non-original allocation.
- Original allocation update/delete rejected.

### Availability and no-double-spend
- Debt-payment reversal creates unapplied payment availability; received payment total remains unchanged.
- Restored payment availability can be allocated once to an eligible invoice.
- Re-allocation reduces available amount exactly once.
- Customer-credit reversal restores availability tied to original credit lineage.
- Restored customer credit can be allocated once; original credit principal is not duplicated.
- Invoice outstanding increases by successful reversal amount and decreases only when a new allocation posts.
- No path can spend both original residual availability and reversal-generated availability for the same amount.

### Idempotency, concurrency, atomicity
- Same key/hash replay returns same receipt with no duplicate ledger entries.
- Same key/different hash conflicts.
- Concurrent partial reversals cannot exceed original allocation amount.
- Concurrent allocation against restored availability cannot overspend it.
- Failure in audit, cursor, availability ledger, or receipt write rolls back all related writes.
- Replay after commit returns original outcome without duplicate events or cursor advancement.

## 6. Existing schema observations

The inspected schema/migration candidate currently has:
- debt_payment_allocations.reversal_of_id and customer_credit_allocations.reversal_of_id, each with tenant-scoped self-reference.
- Both allocation tables have append-only UPDATE/DELETE triggers.
- customer_credit_ledger.entry_type includes PAYMENT_UNAPPLIED and REVERSAL; its CHECK requires positive PAYMENT_UNAPPLIED and permits either sign for REVERSAL.
- The customer-credit allocation source guard accepts only positive CREDIT_NOTE_ISSUED and PAYMENT_UNAPPLIED entries, not REVERSAL.
- Migration 0003 caps payment allocations against the sum of all POSTED allocation rows, caps invoice outstanding against posted allocation sums, and caps customer-credit source consumption against posted allocation sums. It does not subtract successful reversals.
- Migration 0003 explicitly says append-only reversal rows are not netted and require a separately specified protocol.

The current schema is therefore insufficient for the approved reversal behavior. A migration must be designed and tested; this document does not modify it.

## 7. Open design gates before SQL/Worker implementation

1. Decide whether payment-unapplied availability remains in customer_credit_ledger or moves to a dedicated payment-availability ledger. Prove source identity and no-double-spend.
2. Approve the typed reversal representation and idempotency uniqueness scope. Do not add a unique constraint on reversal_of_id; multiple partial reversals are approved.
3. Define the conditional D1 concurrency guard and demonstrate deterministic parallel tests.
4. Downstream allocations: never cascade-delete or auto-reverse later allocations. A restored availability entry is a separate source and may be consumed once by a new allocation. Any further reversal must be capped by the still-reversible original amount and must not create negative availability or exceed invoice collectible limits; reject atomically otherwise.
5. Closed accounting periods: never mutate or reopen a CLOSED period. Record an approved correction/reversal in the next OPEN period with immutable links to the original transaction and both accounting periods. If no OPEN period exists, reject until one is opened through the governed accounting process.
6. Permission: ADMIN only, with ADMIN1 and ADMIN2 equal authority. GERANT and VENDEUR are denied. Require a reason, actor/device identity, timestamp, idempotency key, and audit/change-log evidence. Apply tenant and assigned-store scope checks as appropriate.
7. Define expected-version scope and deterministic D1 concurrency guard.
6. Specify migration ordering and rollback/recovery. Do not edit historical migrations that may already have been applied; use a forward migration after review.

## 8. Explicit non-goals

This is design only. It does not implement SQL, triggers, Worker endpoints, API contracts, Android behavior, remote D1 operations, staging resource creation, production deployment, APK builds, or V1 changes. PR #14 and PR #27 remain unmerged pending review and tests.
