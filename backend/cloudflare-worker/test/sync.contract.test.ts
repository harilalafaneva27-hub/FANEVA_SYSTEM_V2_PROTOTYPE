import { describe, expect, it } from "vitest";
import { isMGA, parseMutation, redactVendorEvent } from "../src/sync";

const uuid = "123e4567-e89b-42d3-a456-426614174000";
const key = "223e4567-e89b-42d3-a456-426614174000";

describe("authoritative sync contract validation", () => {
  it("accepts only safe whole-MGA integers", () => {
    expect(isMGA(0)).toBe(true);
    expect(isMGA(Number.MAX_SAFE_INTEGER)).toBe(true);
    expect(isMGA(-1)).toBe(false);
    expect(isMGA(1.5)).toBe(false);
    expect(isMGA(Number.MAX_SAFE_INTEGER + 1)).toBe(false);
    expect(isMGA("100")).toBe(false);
    expect(isMGA(0, false)).toBe(false);
  });

  it("accepts a versioned stock mutation with an assigned-store selector", () => {
    expect(parseMutation({
      idempotencyKey: key, entityType: "STOCK", action: "SET", entityId: uuid,
      expectedVersion: 2, storeId: "A1", payload: { productId: uuid, quantity: 12 },
    })).not.toBeNull();
  });

  it("rejects malformed IDs, unsafe versions, and invalid store identifiers", () => {
    const base = {
      idempotencyKey: key, entityType: "STOCK", action: "SET", entityId: uuid,
      expectedVersion: 0, storeId: "A1", payload: { productId: uuid, quantity: 0 },
    };
    expect(parseMutation({ ...base, idempotencyKey: "not-a-uuid" })).toBeNull();
    expect(parseMutation({ ...base, expectedVersion: Number.MAX_SAFE_INTEGER + 1 })).toBeNull();
    expect(parseMutation({ ...base, storeId: "A6" })).toBeNull();
    expect(isMGA(1.25)).toBe(false);
  });

  it("requires explicit whole MGA values for product and customer mutations", () => {
    const product = {
      idempotencyKey: key, entityType: "PRODUCT", action: "UPSERT", entityId: uuid,
      expectedVersion: 0, payload: { productCode: "P-1", name: "Produit", purchasePriceMga: 10, sellingPriceMga: 20 },
    };
    expect(parseMutation(product)).not.toBeNull();
    const customer = {
      idempotencyKey: key, entityType: "CUSTOMER", action: "UPSERT", entityId: uuid,
      expectedVersion: 0, payload: { displayName: "Client", creditLimitMga: 500 },
    };
    expect(parseMutation(customer)).not.toBeNull();
  });
});

describe("VENDEUR event redaction", () => {
  it("removes nested PRODUCT purchase price from the actual generic event envelope", () => {
    const event = {
      entityId: uuid,
      version: 2,
      actorPrincipalId: "seller-1",
      mutationId: key,
      payload: {
        productCode: "P-1",
        name: "Produit",
        purchasePriceMga: 800,
        sellingPriceMga: 1200,
      },
    };
    const redacted = redactVendorEvent("PRODUCT", event);
    expect((redacted.payload as Record<string, unknown>).purchasePriceMga).toBeUndefined();
    expect((redacted.payload as Record<string, unknown>).sellingPriceMga).toBe(1200);
    expect(event.payload.purchasePriceMga).toBe(800);
  });

  it("removes flat legacy PRODUCT purchase price as defense in depth", () => {
    const redacted = redactVendorEvent("PRODUCT", {
      productCode: "P-1",
      purchasePriceMga: 800,
      sellingPriceMga: 1200,
    });
    expect(redacted.purchasePriceMga).toBeUndefined();
    expect(redacted.sellingPriceMga).toBe(1200);
  });

  it("redacts CREDIT sale customer and remaining debt but preserves own sale totals", () => {
    const redacted = redactVendorEvent("SALE", {
      saleType: "CREDIT",
      customerId: "customer-secret",
      remainingMga: 700,
      totalMga: 1200,
      paidMga: 500,
    });
    expect(redacted.customerId).toBeUndefined();
    expect(redacted.remainingMga).toBeUndefined();
    expect(redacted.customerRedacted).toBe(true);
    expect(redacted.totalMga).toBe(1200);
    expect(redacted.paidMga).toBe(500);
  });
});
