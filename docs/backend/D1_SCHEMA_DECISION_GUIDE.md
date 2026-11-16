# D1 Schema v1 — Technical decision guide (proposal)

Date: 2026-11-16  
Branch: `feature/staging-backend-foundation`  
Status: **PARTIALLY DECIDED — Opening Stock Option A and the single-initial-tenant model explicitly approved by the user; all other proposals remain unapproved.** This document does not authorize SQL migrations, Cloudflare provisioning, seed import, Worker deployment, APK changes, or PR merge.

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

**Recommended model:** preserve the original posted invoice/sale as F_c; represent each issued credit note as its own immutable credit_note header and lines linked to the original invoice; represent each payment as a separate debt_payment allocation linked to the invoice. A cancelled credit note must have an explicit reversal event, not deletion or mutation of posted history. The calculated balance is a server-side projection only.

Rationale: this preserves traceability of invoice, credit note, and payment without merging unlike financial events into one ambiguous adjustment.

Open detail: whether partial credit-note allocations across invoice lines are required, and whether one credit note may reference multiple invoices. Do not encode cardinality until finance rules are confirmed.

**Approval needed:** accept separate credit_note / credit_note_lines records and explicit reversal, subject to confirming allocation cardinality.

## 4. Customer on cash sales

**Recommended:** customer_id nullable for cash sales; required for credit sales. The API must enforce: cash sale may be anonymous, credit sale must identify a customer.

Rationale: avoids creating fictitious customers for walk-in cash transactions while ensuring all debt is attributable.

**Approval needed:** accept anonymous cash sales and mandatory customer for credit sales.

## 5. Concurrent cursor sequencing

**Recommended:** one server-owned, tenant-scoped monotonically increasing cursor allocated inside the same D1 transaction as the business mutation and change_log insert. Use a dedicated tenant_sync_state row updated transactionally; if two writes contend, one transaction retries or returns a retryable error. Never allocate cursor on Android and never advance a device cursor on a partial/invalid response.

Rationale: equal ADMIN devices can mutate concurrently; serialization at the server avoids duplicate or out-of-order cursor allocation.

Required tests: concurrent writes from ADMIN1 and ADMIN2, unique cursor values, no gaps treated as proof of loss, stable pagination, and retry idempotency.

**Approval needed:** accept tenant-scoped serialized cursor allocation, with conflict retry.

## 6. Audit genesis and canonical hash

**Recommended:** server creates one genesis event per tenant/environment. Define canonical UTF-8 JSON serialization with deterministic key ordering, normalized strings, integer-only monetary values, explicit null handling, and UTC timestamps. Compute each event hash from a versioned domain separator + tenant ID + sequence + previous hash + canonical event bytes. Store algorithm/version with the event. Client-provided hashes are ignored.

Rationale: hash-chain verification requires a fully specified byte representation; ordinary JSON.stringify output must not be assumed canonical across implementations.

**Approval needed:** accept server-generated genesis and versioned canonical serialization; exact byte-level format must be specified and tested before migration.

## 7. Retention and deletion

**Recommended:** audit events retained at least 10 years; financial documents and ledger rows are immutable and never cascade-deleted; device revocation disables credentials but retains the device audit trail; mutation receipts and change-log retention must not break idempotency or offline-device recovery. Customer personal/contact data may be anonymized only through a separately audited process where legally/business appropriate, without deleting financial evidence.

**Approval needed:** accept no cascade deletion for financial/audit history and revocation rather than deletion for devices. Exact retention periods for mutation receipts/change log and customer anonymization need a separate policy.

## 8. Suggested decision order

1. Confirm tenant model.
2. Confirm opening stock ledger model.
3. Confirm credit-note and payment allocation model.
4. Confirm anonymous cash-sale policy.
5. Confirm cursor transaction strategy.
6. Approve audit genesis/canonicalization specification.
7. Approve retention/deletion policy.

Only after these decisions are accepted should the D1 DDL be finalized. Then build and test migrations in an ephemeral local D1-compatible environment. Remote Cloudflare resources remain out of scope until separate explicit authorization.

## Safety boundary

- V1 remains completely untouched and disconnected.
- The new V2 STAGING D1 remains uncreated and empty.
- No existing APK/local database data is imported.
- No Worker or Cloudflare resource is provisioned or deployed.
- No APK is built for installation or installed as part of this design step.
- PR #2 remains Draft/Open; no merge is performed.
