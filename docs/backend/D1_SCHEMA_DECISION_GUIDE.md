# D1 Schema v1 — Technical decision guide (proposal)

Date: 2026-11-16  
Branch: `feature/staging-backend-foundation`  
Status: **DECISIONS RECORDED — tenant, opening stock, credit-note cardinality and line model, payment allocation, cash/credit customer rules, cursor sequencing, FANEVA-AUDIT-V1, retention and finance edge-case policy are user-approved. Remaining implementation details and verification gates are not yet signed off.** This document does not authorize SQL migrations, Cloudflare provisioning, seed import, Worker deployment, APK changes, or PR merge.

The recommendations below are conservative defaults for an isolated FANEVA SYSTEM V2 STAGING environment. They are not treated as approved until explicitly accepted.

## 1. Tenant model

**Recommended:** one initial tenant for the FANEVA SYSTEM V2 installation, while retaining tenant_id on all business rows and tenant-safe foreign keys.

Rationale: the known deployment target is one business system with stores A1–A5. Keeping tenant_id preserves isolation and future migration flexibility without implementing public multi-tenant signup now.

Not authorized: creating a public tenant-registration endpoint or allowing one tenant to query another.

**APPROVED by user:** one initial FANEVA SYSTEM V2 tenant created only by controlled STAGING bootstrap; every tenant-owned table retains `tenant_id` and tenant-safe composite foreign keys; no public tenant self-registration or tenant-discovery endpoint in this phase. Tenant identity is resolved server-side from authenticated enrollment context.

## 2. Opening stock

**Recommended:** an immutable OPENING movement is the canonical source event; stock_balances is a derived/current projection. Each product/store opening quantity must create exactly one opening movement, and projection is initialized in the same transaction.

Rationale: one ledger source prevents a separate opening-balance record and opening movement from being summed twice. Rebuild/reconciliation can derive balances from movements.

Required invariant: sum of posted movement deltas for (tenant, store, product) equals stock_balances.quantity. Reject a negative resulting balance.

**APPROVED by user:** Option A — an immutable OPENING movement is the canonical source event; stock_balances is a derived/current projection. Each store/product opening quantity creates exactly one OPENING movement, and the projection is initialized in the same transaction. Reconciliation invariant: sum of posted movement deltas equals the projected balance; negative resulting balances are rejected.

## 3. Credit notes and debt formula

Known approved formula: `CalculatedInvoiceBalance = F_c - CN_c - P_c`.

**Approved payment model — Option B:** preserve each received payment as one immutable `debt_payments` header for a single customer. A payment may be allocated across multiple credit invoices through one-to-many `debt_payment_allocations` rows. Each allocation identifies exactly one original credit invoice and an integer MGA amount. The sum of allocations must never exceed the payment amount; each allocation must not exceed that invoice's outstanding collectible balance at transaction time. All allocations must belong to the same tenant and customer as the payment, and target CREDIT invoices only. Unallocated remainder is retained as unapplied customer payment credit until explicitly allocated or refunded through an auditable workflow; it must not silently reduce any invoice balance. Allocation/reversal is append-only and idempotent. A cancelled credit note or reversed payment requires an explicit reversal event, not deletion or mutation of posted history. The calculated balance is a server-side projection only.

Rationale: this preserves traceability of invoice, credit note, and payment without merging unlike financial events into one ambiguous adjustment.

Approved cardinality: each credit note references exactly one original invoice. Multiple credit notes may reference the same invoice, subject to remaining eligible invoice value and server-side validation. A credit note must not span multiple invoices. **APPROVED by user — Option A:** credit notes are itemized by original invoice lines. Each line identifies the original product/sale line, returned or credited quantity, and the unit price captured on that original invoice; line amount is calculated from those immutable values. A credit note cannot exceed the remaining uncredited quantity/value of its original invoice lines. The exact rounding policy is not applicable to whole MGA integer arithmetic and must be checked for overflow. Physical stock receipt is a separate event: issuing a financial credit note must not automatically increase stock unless a separately validated goods-return movement is recorded.

**APPROVED by user:** separate immutable `credit_notes` and `credit_note_lines`; each credit note links to exactly one original invoice (one-to-many from invoice to credit notes); no credit note may span multiple invoices; cancellation uses an explicit reversal event. **APPROVED by user — Option A:** line-level product/quantity/unit-price detail using the original invoice line price; enforce remaining uncredited quantities/value. Stock receipt is separate from financial credit-note issuance. **APPROVED by user — CASH/CREDIT eligibility, Option A:** a Credit Note may reference either a CASH or CREDIT invoice. If the original invoice still has an outstanding collectible balance, the posted Credit Note reduces that balance. If a CASH invoice is already fully paid, the credited amount becomes unapplied customer credit or a refund-to-process balance; it is not a negative payment and does not create a payment reversal. Any cash refund is a separate, auditable operation. **APPROVED by user:** approval and execution of a cash refund are `admin_only`; GERANT and VENDEUR are denied server-side. Physical stock receipt remains a separate stock-return movement.


**APPROVED by user — finance model:** discounts applied before invoice posting are included in the invoice-line net amounts; `F_c` is the posted invoice total after those discounts. A post-posting reduction must not edit the invoice; issue an immutable invoice-linked Credit Note. Product returns use the original invoice-line quantity and price, with physical receipt recorded separately as a stock-return movement. `FinancialAdjustment` is reserved for source-linked corrections outside the invoice/return workflow; it does not silently change invoice balance or stock. Tax rates, tax inclusion/exclusion, and tax-credit calculations are not assumed until validated against applicable Madagascar rules. The approved balance formula remains `F_c − CN_c − P_c`; `CN_c` is the posted credit-note amount for that invoice and `P_c` is the sum of posted, non-reversed payment allocations to it.
## 4. Customer on cash sales

**Recommended:** customer_id nullable for cash sales; required for credit sales. The API must enforce: cash sale may be anonymous, credit sale must identify a customer.

Rationale: avoids creating fictitious customers for walk-in cash transactions while ensuring all debt is attributable.

**APPROVED by user:** anonymous cash sales are allowed (`customer_id` nullable only for CASH); `customer_id` is mandatory for CREDIT. The server must enforce this rule on every create/update mutation; UI validation alone is insufficient.

## 5. Concurrent cursor sequencing

**Recommended:** one server-owned, tenant-scoped monotonically increasing cursor allocated inside the same D1 transaction as the business mutation and change_log insert. Use a dedicated tenant_sync_state row updated transactionally; if two writes contend, one transaction retries or returns a retryable error. Never allocate cursor on Android and never advance a device cursor on a partial/invalid response.

Rationale: equal ADMIN devices can mutate concurrently; serialization at the server avoids duplicate or out-of-order cursor allocation.

Required tests: concurrent writes from ADMIN1 and ADMIN2, unique cursor values, no gaps treated as proof of loss, stable pagination, and retry idempotency.

**APPROVED by user — Option A:** allocate a server-owned, monotonically increasing cursor scoped to the tenant inside the same D1 transaction as the business mutation and `change_log` insert. Serialize competing ADMIN1/ADMIN2 writes through the tenant sync-state row; on contention, retry safely or return a retryable conflict. Android never allocates cursors. Device checkpoints advance only after a complete, validated pull page/response. Idempotency receipts remain separate from pull cursors.

Required verification: concurrent ADMIN1/ADMIN2 mutations produce unique ordered cursors; retries do not duplicate business effects or change events; pagination is stable; partial/invalid responses never advance checkpoints.

## 6. Audit genesis and canonical hash

**Recommended:** server creates one genesis event per tenant/environment. Define canonical UTF-8 JSON serialization with deterministic key ordering, normalized strings, integer-only monetary values, explicit null handling, and UTC timestamps. Compute each event hash from a versioned domain separator + tenant ID + sequence + previous hash + canonical event bytes. Store algorithm/version with the event. Client-provided hashes are ignored.

Rationale: hash-chain verification requires a fully specified byte representation; ordinary JSON.stringify output must not be assumed canonical across implementations.

**APPROVED by user — FANEVA-AUDIT-V1:** the server creates exactly one `GENESIS` event per tenant/environment during controlled bootstrap; sequence starts at 1 and `previous_hash` is 32 zero bytes. No client may create/reset the chain. Event content is normalized to Unicode NFC before RFC 8785 (JCS) canonical JSON serialization as UTF-8; reject duplicate JSON keys, floating-point values, NaN/Infinity, and invalid input. Hash input is the exact binary frame: ASCII `FANEVA-AUDIT-V1` followed by NUL; tenant ID as NFC UTF-8 preceded by unsigned 32-bit big-endian byte length; sequence as unsigned 64-bit big-endian; previous hash as 32 raw bytes; canonical event bytes preceded by unsigned 64-bit big-endian byte length. `event_hash = SHA-256(frame)`. Store `hash_algorithm=SHA-256` and `hash_version=FANEVA-AUDIT-V1`. Server computes and verifies all hashes; Android may independently verify but cannot authoritatively replace them. Hash mismatch yields `AUDIT_CHAIN_INVALID`; the affected chain is not reported as verified. Genesis is unique per tenant/environment and recovery must append an auditable recovery event, never silently reset the chain.

Required cross-platform tests: identical canonical bytes and hashes on server/Android #1/Android #2; NFC-equivalent strings; key ordering; null handling; integer boundaries; duplicate keys/floats rejected; altered payload/previous hash/sequence detected; genesis uniqueness and chain continuity.

## 7. Retention and deletion

**APPROVED by user — retention and deletion policy, including 90-day offline grace:**

1. **Audit events:** retain for at least 10 years; append-only, immutable, no cascade delete. Preserve hash-chain verification metadata and recovery events.
2. **Financial, invoice, credit-note, debt-payment, purchase, stock-movement and adjustment records:** no hard deletion and no cascade deletion. Retain at least 10 years after the relevant fiscal period closes, or longer where required by applicable law, dispute, audit, or business hold. Legal/accounting review is required before setting any shorter period.
3. **Mutation idempotency receipts:** retain the minimal deduplication proof (tenant, device, idempotency key, request hash, outcome/reference) for at least 10 years; do not retain secrets or unnecessary personal data in receipts. This prevents old retries from creating duplicate financial effects.
4. **Change log:** retain events needed by all active enrolled devices. Proposed offline grace period is 90 days. A device that exceeds this period must be marked REBOOTSTRAP_REQUIRED; before pruning any operational change-log segment, create and verify a tenant snapshot/checkpoint so the device can safely perform full rebootstrap. Never silently advance its cursor or discard unacknowledged changes.
5. **Device records:** revoke credentials, never delete device identity/audit history while the tenant exists. Retain revocation metadata and enrollment/recovery history for at least 10 years after revocation or tenant closure.
6. **Customer personal/contact data:** do not anonymize while there is an open balance, unresolved dispute, active legal hold, or a financial record that still requires identifiable source evidence. Any later anonymization must be separately authorized, audited, and preserve immutable invoice/ledger snapshots and referential integrity.
7. **Tenant closure:** freeze mutations, export records, revoke all devices, retain financial/audit evidence under the above periods; no tenant-wide cascade deletion.


Before any production use, verify applicable Madagascar accounting, tax, privacy and limitation requirements with qualified local advice. This is a policy proposal, not legal advice; applicable Madagascar accounting, tax, privacy and limitation rules must be checked before production.
## 8. Suggested decision order

1. Tenant model — APPROVED.
2. Opening stock ledger model — APPROVED Option A.
3. Credit-note cardinality and line-level model — APPROVED.
4. Payment allocation — APPROVED Option B.
5. Anonymous CASH / identified CREDIT sale rule — APPROVED.
6. Server-owned tenant cursor sequencing — APPROVED Option A.
7. FANEVA-AUDIT-V1 genesis and canonical hash specification — APPROVED.
8. Retention/deletion policy, including 90-day offline grace — APPROVED.
9. Finance edge-case policy — APPROVED; tax treatment remains intentionally unconfigured pending local verification.

Only after these decisions are accepted should the D1 DDL be finalized. Then build and test migrations in an ephemeral local D1-compatible environment. Remote Cloudflare resources remain out of scope until separate explicit authorization.

## Safety boundary

- V1 remains completely untouched and disconnected.
- The new V2 STAGING D1 remains uncreated and empty.
- No existing APK/local database data is imported.
- No Worker or Cloudflare resource is provisioned or deployed.
- No APK is built for installation or installed as part of this design step.
- PR #2 remains Draft/Open; no merge is performed.
