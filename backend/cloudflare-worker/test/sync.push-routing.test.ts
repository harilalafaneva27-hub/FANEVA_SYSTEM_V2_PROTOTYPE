import { beforeEach, describe, expect, it, vi } from "vitest";

vi.mock("../src/business_transactions", () => ({ applyBusinessSale: vi.fn() }));
vi.mock("../src/business_transfer_debt", () => ({
  applyBusinessTransfer: vi.fn(),
  applyBusinessDebtPayment: vi.fn(),
}));

import { applyBusinessSale } from "../src/business_transactions";
import { applyBusinessTransfer, applyBusinessDebtPayment } from "../src/business_transfer_debt";
import { syncPush } from "../src/sync";

const TOKEN = "A".repeat(43);
const DEVICE = "dev_" + "a".repeat(64);
const PRINCIPAL = "principal-test";
const UUID = "123e4567-e89b-42d3-a456-426614174000";

class SessionStatement {
  private values: unknown[] = [];
  constructor(private readonly sql: string) {}
  bind(...values: unknown[]) { this.values = values; return this; }
  async first<T>() {
    if (this.sql.includes("FROM auth_sessions")) {
      return {
        principal_id: PRINCIPAL,
        device_id: DEVICE,
        role: "ADMIN",
      } as T;
    }
    return null as T | null;
  }
  async all<T>() {
    if (this.sql.includes("FROM auth_store_grants")) {
      return { results: [{ store_id: "A1" }, { store_id: "A2" }] as T[] };
    }
    return { results: [] as T[] };
  }
  async run() { return { meta: { changes: 1 } }; }
}

function fixture() {
  const db = { prepare: (sql: string) => new SessionStatement(sql) };
  const env = { DB: db, FANEVA_ENVIRONMENT: "staging" } as unknown as Parameters<typeof syncPush>[1];
  return env;
}

function mutation(entityType: "SALE" | "TRANSFER" | "DEBT_PAYMENT") {
  return {
    idempotencyKey: UUID,
    entityType,
    action: "CREATE",
    entityId: "223e4567-e89b-42d3-a456-426614174000",
    expectedVersion: 0,
    storeId: "A1",
    payload: { marker: entityType },
  };
}

async function push(mutations: unknown[], protocolVersion = 1) {
  return syncPush(new Request("https://worker.test/v1/sync/push", {
    method: "POST",
    headers: {
      Authorization: `Bearer ${TOKEN}`,
      "X-Faneva-Device-Id": DEVICE,
      "content-type": "application/json",
    },
    body: JSON.stringify({ protocolVersion, mutations }),
  }), fixture());
}

describe("sync push business-handler dispatch", () => {
  beforeEach(() => vi.resetAllMocks());

  it("routes SALE to the authoritative sale handler with the authenticated session", async () => {
    vi.mocked(applyBusinessSale).mockResolvedValue({ idempotencyKey: UUID, status: "APPLIED" });
    const response = await push([mutation("SALE")]);
    expect(response.status).toBe(200);
    expect(vi.mocked(applyBusinessSale)).toHaveBeenCalledWith(
      expect.anything(),
      { principalId: PRINCIPAL, deviceId: DEVICE, role: "ADMIN", storeIds: ["A1", "A2"] },
      expect.objectContaining({ entityType: "SALE", action: "CREATE", storeId: "A1" }),
      expect.any(Number),
    );
    expect(vi.mocked(applyBusinessTransfer)).not.toHaveBeenCalled();
    expect(vi.mocked(applyBusinessDebtPayment)).not.toHaveBeenCalled();
  });

  it("routes TRANSFER and DEBT_PAYMENT to their distinct handlers", async () => {
    vi.mocked(applyBusinessTransfer).mockResolvedValue({ idempotencyKey: UUID, status: "APPLIED" });
    vi.mocked(applyBusinessDebtPayment).mockResolvedValue({ idempotencyKey: UUID, status: "APPLIED" });
    const response = await push([mutation("TRANSFER"), { ...mutation("DEBT_PAYMENT"), idempotencyKey: "323e4567-e89b-42d3-a456-426614174000" }]);
    expect(response.status).toBe(200);
    expect(vi.mocked(applyBusinessTransfer)).toHaveBeenCalledTimes(1);
    expect(vi.mocked(applyBusinessDebtPayment)).toHaveBeenCalledTimes(1);
    const body = await response.json() as { protocolVersion: number; results: Array<{ status: string }> };
    expect(body.protocolVersion).toBe(1);
    expect(body.results.map(result => result.status)).toEqual(["APPLIED", "APPLIED"]);
  });

  it("rejects malformed business envelopes without invoking a handler", async () => {
    const response = await push([{ ...mutation("SALE"), expectedVersion: 1 }]);
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      protocolVersion: 1,
      results: [{ status: "REJECTED", error: "BUSINESS_MUTATION_ENVELOPE_INVALID" }],
    });
    expect(vi.mocked(applyBusinessSale)).not.toHaveBeenCalled();
  });

  it("rejects a non-V1 envelope before dispatch", async () => {
    const response = await push([mutation("SALE")], 2);
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({ error: "SYNC_ENVELOPE_INVALID" });
    expect(vi.mocked(applyBusinessSale)).not.toHaveBeenCalled();
  });
});
