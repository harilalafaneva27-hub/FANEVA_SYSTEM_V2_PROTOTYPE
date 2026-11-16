# FANEVA SYSTEM V2 — STAGING synthetic seed manifest

## Status and purpose

This is the data contract for a fresh, fictional STAGING dataset under approved Bootstrap Option A. It is a manifest/specification, not an imported dataset. No records have been created in D1 by adding this file.

**Source restriction:** generate every record from scratch. Do not read or import V1 data, V2 APK data, Room databases, backups, exports, customer lists, supplier lists, or production identifiers. Use invented names and synthetic contact values only.

## Actual synthetic fixture profile (V2 STAGING)

| Entity | Target count | Notes |
|---|---:|---|
| Stores | 5 | A1, A2, A3, A4, A5 |
| Products | 12 | Synthetic catalog; stable product IDs |
| Customers | 6 | Fictional; synthetic names and contact values |
| Suppliers | 3 | Fictional; synthetic names and contact values |
| ADMIN accounts | 2 | Equal authority; no primary ADMIN |
| GERANT accounts | 1 | Explicit store assignment |
| VENDEUR accounts | 2 | Explicit store assignment; restricted permissions |
| Opening stock records | Per product/store subset | Only explicit store-product pairs |
| Sales | 2 | Synthetic completed sales with sale lines |
| Purchases | 1 | Synthetic purchase header and line |
| Debt records | 1 | Reconciles to the partially paid synthetic sale |
| Stock movements | 10 | Opening stock, purchase, and sale movements; ledger reconciles |
| Audit events | 2 | Synthetic deterministic UTC events |

These are the actual record counts in the checked-in JSON fixture. They describe repository test data only; they do not describe D1 contents. The fixture remains schema-neutral until mapped and reviewed against the approved D1 schema.

## Identity and tenancy rules

- Use deterministic, namespaced synthetic IDs, e.g. `stg-v2-store-A1`, `stg-v2-product-001`, and equivalent entity-specific prefixes.
- Never use real customer/supplier names, phone numbers, addresses, account IDs, device IDs, tokens, or credentials.
- Test ADMIN identities are fixtures only; authentication secrets and activation proofs are provisioned out-of-band and never stored here.
- ADMIN-1 and ADMIN-2 have equal permissions. Neither device nor account is designated primary.
- GERANT and VENDEUR records must have explicit assigned store scopes.
- Every store-scoped business row must reference one valid store A1–A5. No implicit global scope.
- Do not seed device credentials or pre-authorized device tokens. Devices enroll through the owner-verified flow.

## Data integrity rules

1. Every primary key is unique across its entity namespace.
2. Every foreign key resolves to exactly one record.
3. Sale lines reference an existing sale and product; purchase lines reference an existing purchase and product.
4. Stock quantities are whole, non-negative integers. Any permitted negative-stock business rule must be explicitly represented in the schema and separately tested; do not silently seed negative stock.
5. Monetary values are whole, non-negative MGA integers, maximum `9_007_199_254_740_991`.
6. Customer debt/payment examples must reconcile with their source transactions. Do not create a debt balance unsupported by a sale/credit source.
7. Stock movements must reconcile with opening stock, purchases, sales, returns, and adjustments represented in the fixture.
8. Timestamps are UTC ISO-8601 values. UI display conversion to Africa/Antananarivo (UTC+3) is a client concern.
9. Audit fixture ordering must be deterministic. Production hash-chain verification is tested by the backend, not simulated by arbitrary seed hashes.

## Authorization test matrix

| Actor | Allowed in fixture tests | Must be denied |
|---|---|---|
| ADMIN-1 | All authorized STAGING stores and admin operations | No special privilege over ADMIN-2 |
| ADMIN-2 | Same authority as ADMIN-1 | No special privilege over ADMIN-1 |
| GERANT | Assigned-store management permitted by policy | Unassigned stores; ADMIN-only debt payment |
| VENDEUR | POS and permitted operations in assigned store | Unassigned stores; purchase cost, CUMP, margin, profit, debt/credit-limit views, ADMIN-only debt payment, unlisted actions |

Customer creation is ADMIN/GERANT only. Debt payment and customer-credit allocation are ADMIN-only. Reusable customer credit is non-transferable, may be applied only to a CREDIT invoice for the same customer and tenant, and must not exceed available credit or invoice outstanding. Cash-refund approval and execution are ADMIN-only. Unlisted operations are denied by default.

## Required generator output

The seed generator/validator must produce a machine-readable report containing:
- `environment=STAGING`;
- `seedVersion` and immutable `seedId`;
- canonicalization algorithm/version;
- SHA-256 checksum of canonical content (excluding the checksum field);
- total and per-entity record counts;
- per-store counts for store-scoped entities;
- foreign-key and uniqueness validation results;
- MGA integer/range validation results;
- store-scope and role-fixture validation results;
- explicit confirmation that no credentials/secrets are present.

The generator must be deterministic: identical generator version and inputs produce byte-identical checksum input and checksum. Do not include generated-at timestamps in the checksum-bearing payload unless fixed by the fixture specification.

**Current fixture checksum limitation:** the checked-in offline validator currently hashes UTF-8 JSON serialized by Python `json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)`. This is a deterministic fixture checksum for this Python implementation and fixture, **not** RFC 8785/JCS canonicalization, not the approved `FANEVA-AUDIT-V1` event hash frame, and not proof of a production audit chain. Do not use or label it as any of those. Production audit hashing remains a separate server-side implementation and verification requirement.

## Validation cases

- Valid V2 STAGING fixture passes all structural and domain checks.
- Duplicate entity ID fails.
- Dangling foreign key fails.
- Unknown store ID fails.
- Cross-store reference inconsistent with the fixture ownership model fails.
- Decimal, negative, non-finite, or out-of-range MGA fails.
- Missing sale/purchase source for a debt/stock movement fails reconciliation.
- VENDEUR assigned to multiple/unintended stores or granted privileged fields fails.
- Real-looking credentials, tokens, activation proofs, or secret fields fail the secret scan.
- Any attempt to source data from an APK/Room/export/backup fails the provenance review.

## Not included in this manifest

No production/customer data; no V1/V2 APK database extraction; no passwords, API tokens, Worker secrets, activation proofs, private keys, or device credentials; no server cursor checkpoint; no pre-enrolled devices; no claim that the D1 database exists or has been seeded.

## Opening Stock Option A — approved implementation contract

The user explicitly approved **Option A** for D1 V2:

- An immutable `OPENING` stock movement is the sole canonical opening-stock source event.
- `stock_balances` is a current projection, not an independent source of opening quantity.
- The JSON `openingStock` section is a fixture assertion/manifest of intended opening positions. It must **not** be imported as a second balance ledger.
- For each (storeId, productId), exactly one `OPENING` movement must exist and its positive/non-negative `quantityDelta` must equal the corresponding `openingStock.quantity`.
- Duplicate opening positions or duplicate OPENING movements are invalid.
- The opening movement and initial projection must be written atomically during a future seed import. A failed import rolls back both.
- Reconciliation invariant: the projected balance equals the sum of all posted movement deltas for that store/product, including opening, purchase, sale, and any separately authorized future movement types.
- Any operation resulting in a negative balance is rejected.

The offline validator now checks one-to-one correspondence between the `openingStock` assertions and canonical `OPENING` movements, in addition to full stock-ledger reconciliation. This still validates only the synthetic JSON fixture; it does not prove D1 schema compatibility or execute an import.
