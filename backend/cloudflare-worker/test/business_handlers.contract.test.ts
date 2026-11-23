import { describe, expect, it } from "vitest";
import {
  authorizeBusinessMutation, BusinessMutationError, parseBusinessPayload,
  type BusinessMutationInput, type BusinessSession,
} from "../src/business_handlers";

const saleBase: BusinessMutationInput = {
  idempotencyKey: "223e4567-e89b-42d3-a456-426614174000",
  entityType: "SALE", action: "CREATE",
  entityId: "123e4567-e89b-42d3-a456-426614174000",
  expectedVersion: 0, storeId: "A1",
  payload: {
    saleNumber: "VNT-1", saleType: "CASH", customerId: null,
    paidMga: 3600, paymentMethod: "CASH",
    items: [{ productId: "323e4567-e89b-42d3-a456-426614174000", quantity: 2 }],
  },
};
const admin: BusinessSession = {
  principalId: "admin-1", deviceId: "dev-1", role: "ADMIN", storeIds: ["A1", "A2"],
};

describe("canonical business mutation boundary", () => {
  it("accepts a SALE with product UUIDs and quantities only", () => {
    const parsed = parseBusinessPayload(saleBase);
    expect(parsed.kind).toBe("SALE");
    if (parsed.kind === "SALE") {
      expect(parsed.items[0]).toEqual({ productId: "323e4567-e89b-42d3-a456-426614174000", quantity: 2 });
      expect(parsed.paidMga).toBe(3600);
    }
  });

  it("rejects client-supplied sale prices and totals", () => {
    expect(() => parseBusinessPayload({
      ...saleBase, payload: { ...saleBase.payload, totalMga: 3600, unitPriceMga: 1800 },
    })).toThrowError(BusinessMutationError);
    expect(() => parseBusinessPayload({
      ...saleBase, payload: { ...saleBase.payload, items: [{ productId: "323e4567-e89b-42d3-a456-426614174000", quantity: 2, unitPriceMga: 1800 }] },
    })).toThrowError("SALE_LINE_INVALID");
  });

  it("rejects fractional, unsafe and zero payment MGA", () => {
    for (const amount of [0, 1.5, Number.MAX_SAFE_INTEGER + 1, "100"]) {
      expect(() => parseBusinessPayload({
        ...saleBase, entityType: "DEBT_PAYMENT", payload: {
          customerId: "423e4567-e89b-42d3-a456-426614174000", amountMga: amount,
          paymentMethod: "CASH",
        },
      })).toThrowError(BusinessMutationError);
    }
  });

  it("requires both store grants for TRANSFER", () => {
    const transfer: BusinessMutationInput = {
      ...saleBase, entityType: "TRANSFER",
      payload: {
        productId: "323e4567-e89b-42d3-a456-426614174000",
        fromStoreId: "A1", toStoreId: "A2", quantity: 3, reason: "Reassort",
      },
    };
    const parsed = parseBusinessPayload(transfer);
    expect(parsed.kind).toBe("TRANSFER");
    expect(authorizeBusinessMutation(transfer, parsed, admin)).toBeNull();
    expect(authorizeBusinessMutation(transfer, parsed, { ...admin, storeIds: ["A1"] })).toBe("STORE_ACCESS_DENIED");
    expect(authorizeBusinessMutation(transfer, parsed, { ...admin, role: "VENDEUR" })).toBe("PERMISSION_DENIED");
  });

  it("keeps debt payment ADMIN-only", () => {
    const payment: BusinessMutationInput = {
      ...saleBase, entityType: "DEBT_PAYMENT",
      payload: {
        customerId: "423e4567-e89b-42d3-a456-426614174000",
        amountMga: 1500, paymentMethod: "MVOLA",
      },
    };
    const parsed = parseBusinessPayload(payment);
    expect(authorizeBusinessMutation(payment, parsed, admin)).toBeNull();
    expect(authorizeBusinessMutation(payment, parsed, { ...admin, role: "GERANT" })).toBe("PERMISSION_DENIED");
    expect(authorizeBusinessMutation(payment, parsed, { ...admin, role: "VENDEUR" })).toBe("PERMISSION_DENIED");
  });

  it("rejects a CREDIT sale without a customer and a transfer to the same store", () => {
    expect(() => parseBusinessPayload({
      ...saleBase, payload: { ...saleBase.payload, saleType: "CREDIT" },
    })).toThrowError("SALE_CUSTOMER_REQUIRED");
    expect(() => parseBusinessPayload({
      ...saleBase, entityType: "TRANSFER", payload: {
        productId: "323e4567-e89b-42d3-a456-426614174000",
        fromStoreId: "A1", toStoreId: "A1", quantity: 1,
      },
    })).toThrowError("TRANSFER_PAYLOAD_INVALID");
  });
});
