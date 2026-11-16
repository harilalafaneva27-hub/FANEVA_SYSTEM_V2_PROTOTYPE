# FANEVA SYSTEM V2 — Finance Integrity Test Plan

Date: 2026-11-16  
Branch: `feature/staging-backend-foundation`  
Status: design/test specification only; not executable D1 SQL and not authorization to provision Cloudflare.

## 1. Scope and safety

This plan converts the user-approved finance rules into deterministic acceptance tests for a future disposable local D1-compatible test database and server-domain test harness.

- No production or remote Cloudflare D1 is used.
- No Worker deployment, seed import, APK build/install, or V1 access is authorized by this document.
- V1 data, identifiers, credentials, APIs, Workers and databases are out of scope.
- All monetary values are whole nonnegative MGA integers, bounded by 9,007,199,254,740,991.
- Server-side validation and atomic transactions are authoritative; Android validation is supplementary.

## 2. Approved financial invariants

### Invoice and Credit Note

1. A posted invoice is immutable. A pre-posting discount is included in its net line totals and posted total (F_c).
2. A post-posting reduction must be a posted immutable Credit Note linked to exactly one original CASH or CREDIT invoice; both sale types are eligible. A single Credit Note cannot span invoices. If the CASH invoice is fully paid, the credited amount becomes unapplied customer credit or a refund-to-process balance, never a negative payment; any cash refund is a separate auditable operation.
3. Each Credit Note line references one original sale line, uses its immutable original unit price and records a positive credited quantity.
4. Cumulative net credited quantity and value cannot exceed the original line's remaining eligible quantity/value. A reversal restores eligibility only through an explicit, linked reversal event.
5. Issuing a financial Credit Note does not itself change stock. Physical receipt is a separate stock-return movement and must be posted exactly once for each accepted physical receipt.
6. Posted invoice or Credit Note history is never edited/deleted to correct a mistake; use explicit reversal/correction events.

### Payments and allocations

1. A payment receipt belongs to exactly one tenant and customer and is immutable once posted.
2. One payment may have multiple allocation rows; each allocation targets exactly one CREDIT invoice.
3. Payment, allocation and target invoice must share tenant and customer. CASH invoices cannot receive debt allocations.
4. Sum of posted, non-reversed allocations for a payment cannot exceed its payment amount.
5. Each allocation cannot exceed the target invoice's collectible outstanding balance at posting time.
6. Unallocated remainder is visible as unapplied customer credit and does not reduce any invoice balance until an allocation is posted.
7. Reversal is explicit and append-only. Reversing an allocation restores the corresponding collectible balance exactly once; it must not delete the original allocation.
8. Debt-payment mutation remains ADMIN-only as already approved.

### Balance calculation

For each invoice:

`CalculatedInvoiceBalance = F_c - CN_c - P_c`

- `F_c`: immutable posted net invoice total.
- `CN_c`: net posted Credit Note value for that invoice after linked reversals.
- `P_c`: net posted payment allocations for that invoice after linked reversals.

The balance must never be inferred from a client-supplied balance field.

### Supplier refunds and stock

1. Supplier Refund is a supplier-side register and is never inserted into customer `debt_payments`, `debt_payment_allocations`, or customer invoice balance calculations.
2. Supplier refund cash movement, purchase-cost adjustment and physical supplier-return stock movement are distinct events; their exact accounting linkage remains a separate design item.
3. Stock return from a customer is recorded only when goods are physically received and accepted; a financial Credit Note alone cannot increase stock.
4. Stock-return posting is idempotent and linked to its source receipt so retry cannot duplicate quantity.

## 3. Required acceptance test matrix

| ID | Scenario | Expected result |
|---|---|---|
| FIN-CN-001 | Pre-posting discount on sale lines | Posted (F_c) equals net line total; no Credit Note created |
| FIN-CN-002 | Attempt to edit posted invoice total/lines | Reject; immutable invoice unchanged |
| FIN-CN-003 | Credit Note references two invoices | Reject |
| FIN-CN-004 | Credit Note line references unrelated sale line | Reject |
| FIN-CN-005 | Credit quantity exceeds original line remaining quantity | Reject atomically |
| FIN-CN-006 | Cumulative Credit Notes exceed original line quantity/value | Reject atomically, including concurrent requests |
| FIN-CN-007 | Valid partial Credit Note | Post; invoice balance decreases by its net posted amount |
| FIN-CN-008 | Reverse a posted Credit Note | Append reversal; net (CN_c) and balance are recalculated once |
| FIN-CN-009 | Retry same Credit Note mutation/idempotency key | Return original outcome; no duplicate Credit Note |
| FIN-CN-010 | Credit Note against a CASH invoice with outstanding balance | Accept; reduce that invoice's outstanding balance |
| FIN-CN-011 | Credit Note against a fully paid CASH invoice | Accept; create unapplied customer credit/refund-to-process balance, not a negative payment |
| FIN-CN-012 | Process cash refund for a fully paid CASH invoice Credit Note | Use a separate auditable refund operation; reconcile without changing the original payment or stock unless a separate stock return is posted |
| FIN-PAY-001 | One payment allocated across two same-customer CREDIT invoices | Accept if total allocations <= payment amount and each invoice outstanding |
| FIN-PAY-002 | Allocation to another customer's invoice | Reject |
| FIN-PAY-003 | Allocation to CASH invoice | Reject |
| FIN-PAY-004 | Sum allocations exceeds payment amount | Reject atomically |
| FIN-PAY-005 | Allocation exceeds invoice outstanding balance | Reject atomically |
| FIN-PAY-006 | Partial payment with unapplied remainder | Accept; remainder remains unapplied and does not reduce invoice balance |
| FIN-PAY-007 | Reverse one allocation | Append reversal; restore only that invoice balance once |
| FIN-PAY-008 | Retry payment/allocation request | Idempotent; no duplicate receipt or allocation |
| FIN-PAY-009 | Concurrent allocations compete for same invoice balance | Serialize/reject one safely; no over-allocation |
| FIN-PAY-010 | GERANT or VENDEUR submits debt-payment mutation | Reject; ADMIN-only policy enforced server-side |
| FIN-BAL-001 | Invoice + multiple Credit Notes + multiple allocations | Balance equals (F_c - CN_c - P_c) exactly |
| FIN-BAL-002 | Reversed Credit Note/allocation | Reversal is excluded from net reduction exactly once |
| FIN-SUP-001 | Supplier Refund created | Does not change customer invoice balance or customer payment totals |
| FIN-SUP-002 | Supplier Refund submitted as customer debt payment | Reject type/domain mismatch |
| FIN-STK-001 | Credit Note issued without physical goods receipt | No stock movement; stock unchanged |
| FIN-STK-002 | Physical return accepted and posted | One stock-return movement increases stock by accepted quantity |
| FIN-STK-003 | Retry same physical-return event | No duplicate stock movement |
| FIN-STK-004 | Physical return exceeds credited/eligible quantity | Reject or require separately authorized non-credit return workflow; never silently over-credit |
| FIN-TEN-001 | Cross-tenant invoice/payment/credit-note references | Reject via server authorization and tenant-safe relational constraints |
| FIN-MGA-001 | Fractional, negative, or unsafe-integer MGA value | Reject |
| FIN-AUD-001 | Posted financial event or reversal | Append audit event; no silent mutation/deletion |

## 4. Transaction and concurrency requirements

The following operations must be atomic:

- Posting an invoice, its lines, stock movement and projection update.
- Posting a Credit Note, its lines, cumulative eligible-quantity/value check, invoice balance projection (if materialized), change log and audit event.
- Posting a payment allocation, checking payment remainder and invoice outstanding balance, change log and audit event.
- Reversing a Credit Note or allocation and updating derived balances.
- Accepting physical goods return, writing exactly one stock movement and updating stock projection.

Concurrency tests must use independent sessions/transactions and verify that two ADMIN devices cannot both consume the same remaining invoice quantity or balance. Retry must be idempotent. No last-write-wins.

## 5. Open implementation details — not additional user-policy approvals

These are technical/design gates, not reasons to reinterpret the approved business rules:

1. Define exact posted/reversed status transitions and reversal linkage/cardinality.
2. Define how unapplied customer credit from a fully paid CASH invoice is represented and how a separate cash-refund workflow is authorized, posted, reversed and reconciled. CASH and CREDIT invoice eligibility is approved; this is an implementation detail, not an open policy question.
3. Define source identity and unique constraints for customer stock-return receipts and supplier-return receipts.
4. Define whether customer unapplied credit can be refunded in cash, and the authorization/accounting event for that operation.
5. Define supplier refund linkage to original purchase(s), supplier credit balances and any inventory/cost treatment.
6. Specify rounding is unnecessary for whole-MGA integer multiplication, but enforce overflow-safe multiplication before persistence.
7. Define whether invoice balances are computed on read or maintained as a transactionally updated projection; if projected, prove equality to ledger-derived values.

## 6. Exit criteria before D1 SQL

- Every test above has an executable automated test.
- Fresh schema and all constraints pass in a disposable local D1-compatible environment.
- Concurrency, rollback, idempotency, tenant isolation and role authorization tests pass.
- Credit Note and allocation reversals reconcile to the immutable ledger.
- Supplier refunds cannot leak into customer debt calculations.
- Stock projections equal the sum of posted stock movements.
- No SQL migration or remote Cloudflare resource is created until the complete schema and migration plan are separately reviewed and approved.

## 7. Safety boundary

- PR #2 remains Draft/Open until explicit merge approval.
- No remote D1, Worker, Cloudflare resource, APK installation/update or deployment is performed by this test-design step.
- V1 remains completely untouched, disconnected, and excluded.
