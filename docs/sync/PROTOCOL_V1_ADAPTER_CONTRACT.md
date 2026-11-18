# Android ↔ Worker protocol-v1 adapter contract

Status: implementation contract for `feature/authoritative-business-sync`.
This document does not authorize deployment or applying a D1 migration.

## Envelope

Android sends exactly:

```json
{
  "protocolVersion": 1,
  "mutations": [
    {
      "idempotencyKey": "<stable UUID>",
      "entityType": "SALE | DEBT_PAYMENT | TRANSFER | PRODUCT | CUSTOMER | STOCK",
      "action": "CREATE | UPSERT | SET",
      "entityId": "<server UUID>",
      "expectedVersion": 0,
      "storeId": "A1",
      "payload": {}
    }
  ]
}
```

The adapter must never send Room auto-increment IDs as server identifiers. It resolves
`(entityType, localId) → (serverId, serverVersion)` through Room
`server_id_mappings`. New server IDs and idempotency UUIDs must be stable across
retries; a retry must not create a fresh identifier.

## Business payloads

### SALE / CREATE

- `entityId`: stable server sale UUID.
- `storeId`: sale store.
- `payload.customerId`: mapped server customer UUID for CREDIT, otherwise null.
- `payload.saleType`: CASH or CREDIT.
- `payload.paidMga`: whole non-negative MGA integer.
- `payload.paymentMethod`: CASH, MVOLA, ORANGE_MONEY, AIRTEL_MONEY, BANK, OTHER.
- `payload.items[]`: `productId` (server UUID) and positive integer `quantity`.
- Client total, line price, remaining debt, seller identity, and actor role are never
  authoritative. Worker derives price and totals from server product records and
  actor identity from the authenticated session.

### DEBT_PAYMENT / CREATE

- `entityId`: stable server payment UUID.
- `payload.customerId`: mapped server customer UUID.
- `payload.amountMga`: positive whole MGA integer.
- `payload.paymentMethod`: supported server payment method.
- `payload.note`: optional bounded text.
- Worker enforces ADMIN-only permission, outstanding balance, and no overpayment.

### TRANSFER / CREATE

- `entityId`: stable server transfer UUID.
- `payload.productId`: mapped server product UUID.
- `payload.fromStoreId`, `payload.toStoreId`: distinct A1–A5 stores.
- `payload.quantity`: positive integer.
- `payload.reason`: optional bounded text.
- Worker checks that the authenticated principal is granted both stores and that
  source stock is sufficient.

## Result contract

Each submitted mutation receives exactly one durable result:

- `APPLIED`: business write committed; Android may mark it SYNCED.
- `REJECTED`: terminal business/policy rejection; never automatically retried.
- `CONFLICT`: expectedVersion mismatch; held for reconciliation, never automatically
  retried.
- Missing, duplicate, unknown, or malformed result IDs invalidate the batch response.
  Android must not advance the pull cursor on an invalid/incomplete response.

## Mapping and pull invariants

1. Server IDs are opaque UUID strings; local IDs remain local.
2. Mapping writes, entity updates, outbox transitions, and cursor advancement are
   committed in one Room transaction after the entire pull page/snapshot validates.
3. A server ID cannot map to two local entities of the same entity type.
4. Cursor must be monotonic and must not exceed the server high-watermark.
5. Missing mappings, unknown entity types, non-integral MGA, unsafe integer overflow,
   and incomplete pages fail closed without advancing the cursor.
6. No fallback to protocol-v2 envelope, legacy Worker, or local simulated success.

## Compatibility note

The current Android request is protocolVersion 2 with `operations`, while the current
Worker accepts protocolVersion 1 with `mutations`. Current Android outbox payloads also
use local IDs and legacy field names. The adapter implementation must explicitly transform
those payloads and fail closed when required server mappings or source fields are absent.
