# Migration 0006 Proposal — Financial Allocation Reversal

Status: DESIGN PROPOSAL ONLY. No SQL has been applied or committed by this document.
Target: isolated V2 STAGING schema branch. Never apply to V1 or a production database.

## 1. Existing schema constraints confirmed

The current schema contains:
- `debt_payments` and append-only `debt_payment_allocations`;
- `customer_credit_ledger` and `customer_credit_allocations`;
- `reversal_of_id` self-references on both allocation tables;
- `mutation_receipts`, `audit_events`, `change_log`, and tenant cursor state;
- migration 0003 guards that cap gross POSTED allocation amounts against payment/source and invoice outstanding.

The existing self-reference does not by itself enforce cumulative multi-row reversals, net outstanding, idempotency of each reversal request, or restored availability. The current D1 workflow runs SQLite tests; it is not evidence of a Cloudflare Worker/D1 runtime transaction.

## 2. Approved business rules

1. Original allocations are immutable and remain in the ledger.
2. Multiple partial reversals may reference one original allocation.
3. Sum of successful posted reversals must never exceed the original allocation amount.
4. A reversal is a separate append-only record; rejected/failed requests do not reduce the remaining reversible amount.
5. Debt-payment reversal restores availability to the original payment as unapplied payment credit. It does not create a new payment.
6. Customer-credit reversal restores availability to the original customer-credit source. It must not mint duplicate credit.
7. Invoice outstanding increases by the successfully reversed allocation amount, subject to the approved invoice and accounting-period rules.
8. Closed accounting periods are immutable. A permitted correction is posted in the next OPEN period with an explicit reference to the original. If no OPEN period exists, reject.
9. No automatic cascade reversal of downstream allocations. Reject a reversal if its consequences would violate available balance, invoice balance, or other invariants.
10. ADMIN only may authorize reversal; ADMIN1 and ADMIN2 have equal authority. GERANT and VENDEUR are denied.
11. All MGA values are integer-only and within the safe integer ceiling.
12. Reversal, restored availability entry, invoice projection, audit event, change-log cursor, and mutation receipt must commit atomically.

## 3. Proposed migration 0006 responsibilities

Migration 0006 should add the minimum additive schema required to represent:
- reversal request/record identity, original allocation reference, amount, reason, actor, device, accounting period, timestamps, idempotency key, request hash, and outcome/status;
- separate source-linked payment-availability entries;
- separate source-linked customer-credit-availability entries;
- uniqueness for tenant-scoped idempotency keys, not uniqueness on original allocation reference (multiple reversals are valid);
- foreign keys that prove tenant and source identity across original allocation, payment/customer, sale/invoice, actor, device, and period where those parent keys exist;
- append-only UPDATE/DELETE guards for posted financial evidence.

Do not overload `customer_credit_ledger.REVERSAL` as a universal payment and customer-credit mechanism. Payment availability and customer credit availability remain separate ledgers/projections.

The proposal does not prescribe exact table or column names where the existing schema's period and account/device contracts have not yet been fully verified.

## 4. Required invariants and transaction algorithm

For each original allocation:
`remaining_reversible = original_amount - SUM(successfully_posted_reversal_amounts)`.

Within one D1 transaction:
1. Validate authenticated actor is an active ADMIN and device/tenant scope matches.
2. Validate idempotency key and request hash. Same key + same hash replays the stored receipt; same key + different hash is rejected.
3. Validate original allocation is POSTED, belongs to the tenant, and is eligible under accounting-period rules.
4. Re-read the current successful reversal total and source/invoice balances inside the write transaction.
5. Conditionally reserve the amount only if cumulative reversal remains <= original amount and all availability/invoice invariants remain valid. The condition must be enforced by database writes/constraints, not a read-then-write check alone.
6. Append the reversal and exactly one matching source-specific restored-availability entry.
7. Update any mutable projection/version using compare-and-swap / expectedVersion semantics.
8. Append audit event and change-log entry, advance cursor, and persist mutation receipt in the same transaction.
9. Any failure rolls back all writes; no partial financial or audit state is allowed.

The exact D1 concurrency primitive must be proven against the actual Cloudflare D1 runtime before claiming concurrency safety. SQLite unit tests alone are insufficient.

## 5. Executable test gates

### Reversal lifecycle
- one partial reversal; multiple partial reversals against the same original;
- exact remaining amount succeeds; one MGA over the remaining amount fails;
- full reversal; no further reversal succeeds;
- duplicate request replays receipt without a second ledger entry;
- same idempotency key with different request hash is rejected;
- failed/rejected attempts do not consume reversible amount;
- cross-tenant, wrong source, wrong customer, wrong invoice, and wrong allocation type are rejected.

### Payment availability
- partial debt-allocation reversal restores exactly that amount to the original payment's unapplied availability;
- no new payment is created;
- restored availability can be allocated once;
- repeated/replayed reversal cannot restore it twice;
- over-allocation and cross-customer use fail.

### Customer credit availability
- reversal restores exactly the amount to the original credit source;
- no duplicate credit is minted;
- restored credit can be applied once;
- repeated/replayed reversal cannot restore it twice;
- cross-customer/source use fails.

### Invoice and downstream rules
- invoice outstanding increases by the successfully reversed amount;
- payment and customer-credit allocations share the same invoice outstanding cap after netting;
- no cascade reversal occurs;
- a reversal that would make a downstream or invoice balance invalid is rejected atomically.

### Accounting periods and permissions
- OPEN period reversal succeeds;
- CLOSED period remains immutable and correction is posted only in next OPEN period;
- no OPEN period means rejection;
- ADMIN1 and ADMIN2 allowed; GERANT and VENDEUR denied.

### Atomicity, concurrency, and regression
- concurrent reversals cannot exceed the original allocation;
- concurrent reuse of restored availability cannot double spend;
- failure at every step rolls back reversal, availability, projection, audit, cursor, and receipt;
- all existing migration 0001–0005 tests continue to pass;
- migration upgrade from a fresh DB and a representative pre-0006 DB is verified;
- rollback/recovery plan is documented without deleting append-only financial evidence.

## 6. Explicit implementation gates

Before SQL is authored:
- inspect complete migration 0001–0005 and all relevant trigger definitions;
- identify the canonical accounting-period table and its lifecycle contract (if absent, define it in a separate approved design);
- verify exact parent-key relationships for actor, device, payment, credit source, and invoice;
- decide whether current source tables can be extended additively or dedicated availability tables are required;
- specify D1-supported atomic conditional-write strategy and validate it with runtime integration tests.

Before merge:
- migration and executable SQLite regression tests pass;
- D1 runtime integration tests prove transaction atomicity and concurrent caps;
- diff contains only the intended migration, tests, and documentation;
- no V1, Android APK, remote D1, secret, or production resource changes;
- explicit user approval for merge.

## 7. Scope and safety

This is a proposal document only. It does not add migration SQL, modify migrations 0001–0005, alter Worker/API code, mutate remote D1, create credentials, deploy, or change V1. Keep the branch and PR in Draft until every implementation gate is evidenced.
