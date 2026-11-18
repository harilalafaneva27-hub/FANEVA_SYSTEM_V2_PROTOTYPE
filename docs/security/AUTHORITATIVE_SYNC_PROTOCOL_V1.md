# FANEVA V2 Authoritative Sync Protocol v1 — implementation status

## Security boundary

All three endpoints require both:
1. A valid ECDSA P-256 device signature over the exact HTTP method, path, timestamp, nonce, device ID and SHA-256 body digest.
2. A device-bound Bearer session whose token hash resolves to an active principal, active device, unexpired and non-revoked session.

The role and store grants are loaded from D1 for every request. A device signature alone never authorizes business operations. The actor principal ID is always taken from the validated session.

## Endpoints

All endpoints are POST and require JSON.

- `/v1/sync/push`
- `/v1/sync/pull`
- `/v1/sync/bootstrap`

### Push envelope

```json
{
  "protocolVersion": 1,
  "mutations": [
    {
      "idempotencyKey": "223e4567-e89b-42d3-a456-426614174000",
      "entityType": "STOCK",
      "action": "SET",
      "entityId": "123e4567-e89b-42d3-a456-426614174000",
      "expectedVersion": 2,
      "storeId": "A1",
      "payload": {
        "productId": "123e4567-e89b-42d3-a456-426614174000",
        "quantity": 12
      }
    }
  ]
}
```

Maximum 50 mutations per request. Each mutation has a UUID idempotency key and UUID entity ID. `expectedVersion=0` means create; positive values mean compare-and-swap update. Monetary fields are JSON numbers validated as safe, non-negative whole MGA integers, maximum `9007199254740991`. Quantities are bounded whole integers.

### Implemented mutation coverage

- `PRODUCT / UPSERT`: ADMIN and GERANT only. Server persists product identity, name, category, purchase/selling MGA prices and active state.
- `CUSTOMER / UPSERT`: ADMIN and GERANT only. Server persists customer profile and credit limit.
- `STOCK / SET`: ADMIN and GERANT only, restricted to an explicitly granted A1–A5 store. Server records a stock movement when quantity changes.

A successful mutation, its store-scoped event(s), and its idempotency result are committed together through a D1 batch. The client must not mark an outbox item SYNCED until the APPLIED response is received. Reusing an idempotency key with a different canonical request hash returns `IDEMPOTENCY_KEY_REUSED`. Replaying the same request returns the stored original response. Version mismatches return terminal `CONFLICT / VERSION_CONFLICT`; there is no last-write-wins behavior.

### Pull and cursor

Pull accepts `{ "afterCursor": 0, "limit": 100, "storeId": "A1" }`. Limit is 1–200. The server captures a global high-watermark, returns ordered events scoped to the caller's store grants, and returns `nextCursor`. If a page is full, the cursor advances only to the last returned/scanned event; otherwise it advances to the captured high-watermark. A cursor ahead of the server is rejected with `CURSOR_AHEAD_OF_SERVER`. Vendor sessions do not receive customer events or purchase prices.

### Bootstrap

Bootstrap returns a server snapshot of products, authorized-store stock and role-permitted customer information, plus the cursor watermark. ADMIN/GERANT snapshots include purchase prices and full customer credit data. VENDEUR snapshots omit purchase prices and receive customer ID/name only for POS; they do not receive phone, address, debt or credit limit.

## Explicitly incomplete / gate remains closed

This is the first server sync slice, not production-ready or LOT29-complete:
- Sale creation, sale lines, cash/credit settlement, debt payments, customer ledger, transfers, supplier workflows and financial adjustments are not yet implemented as push mutations.
- The current Android `SyncRepository.kt` still emits protocol v2 (`operations[]`, with `payloadJson` as a string) and its OkHttp sync request does not attach `Authorization: Bearer`. The Worker v1 contract expects `mutations[]` with structured payloads plus a device-bound Bearer session. An explicit Android adapter/session-token integration is therefore required before connecting this endpoint; do not point the current APK at this handler yet.
- Android outbox currently contains SALE, TRANSFER, CUSTOMER, DEBT_PAYMENT and STOCK_ADJUSTMENT operations. Worker v1 currently implements PRODUCT, CUSTOMER and STOCK only; the operation mapping and server-side sale/debt/transfer semantics are not yet complete.
- The Android client has not yet been updated to process `APPLIED`, `REJECTED` and `CONFLICT` mutation-level results or persist the pull cursor from `/v1/sync/pull`.
- Bootstrap snapshot/cursor consistency under concurrent writes needs a D1-backed integration test.
- Idempotency, transaction rollback, duplicate request races, and concurrent VERSION_CONFLICT require real D1 integration coverage.
- Live staging D1 migrations and smoke tests have not been run. No D1 migration has been applied and no Worker has been deployed.

Do not open the staging readiness gate until all incomplete items above are implemented and verified.
