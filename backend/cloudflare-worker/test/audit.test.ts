import { describe, expect, it } from "vitest";
import { securityAuditEventHashForTest } from "../src/audit";

const base = {
  eventId: "evt_0123456789abcdef",
  eventType: "DEVICE_ENROLLMENT_APPROVED",
  occurredAt: 1_795_600_000,
  actorPrincipalId: "usr_admin",
  actorDeviceId: "dev_actor",
  targetDeviceId: "dev_target",
  outcome: "SUCCESS" as const,
};

describe("security audit canonical hashing", () => {
  it("is deterministic regardless of metadata insertion order", async () => {
    const a = await securityAuditEventHashForTest({ ...base, metadata: { role: "ADMIN", storeCount: 5 } });
    const b = await securityAuditEventHashForTest({ ...base, metadata: { storeCount: 5, role: "ADMIN" } });
    expect(a).toBe(b);
    expect(a).toMatch(/^[a-f0-9]{64}$/);
  });

  it("changes when an event field or previous hash changes", async () => {
    const first = await securityAuditEventHashForTest(base);
    const differentActor = await securityAuditEventHashForTest({ ...base, actorDeviceId: "dev_other" });
    const nextLink = await securityAuditEventHashForTest(base, "a".repeat(64));
    expect(differentActor).not.toBe(first);
    expect(nextLink).not.toBe(first);
  });

  it("does not hash plaintext credentials unless explicitly passed as metadata", async () => {
    const hash = await securityAuditEventHashForTest({ ...base, metadata: { reason: "owner approval" } });
    expect(hash).not.toContain("password");
    expect(hash).not.toContain("token");
  });
});
