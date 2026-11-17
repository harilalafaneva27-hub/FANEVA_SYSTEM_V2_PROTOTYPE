import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { calculateAuditEventHash, canonicalizeAuditEvent } from "../src/audit.mjs";

globalThis.crypto ??= webcrypto;

test("canonicalizes object keys in deterministic JCS order", () => {
  assert.equal(
    canonicalizeAuditEvent({ z: 1, eventType: "SALE_POSTED", amountMGA: 1200 }),
    '{"amountMGA":1200,"eventType":"SALE_POSTED","z":1}'
  );
});

test("matches the FANEVA-AUDIT-V1 SHA-256 reference vector", async () => {
  const hash = await calculateAuditEventHash({
    tenantId: "tenant-a",
    sequence: 1,
    previousHashHex: "00".repeat(32),
    event: { amountMGA: 1200, eventType: "SALE_POSTED", id: "evt-1" }
  });
  assert.equal(hash, "b2aca8259b4253b1789cebcae92c4e49d1f45cbef0af4585da6a89e7fc782196");
});

test("normalizes tenant ID to NFC before framing", async () => {
  const common = { sequence: 1, previousHashHex: "00".repeat(32), event: { type: "GENESIS" } };
  const a = await calculateAuditEventHash({ ...common, tenantId: "caf\u00e9" });
  const b = await calculateAuditEventHash({ ...common, tenantId: "cafe\u0301" });
  assert.equal(a, b);
});

test("rejects fractional, unsafe and negative-zero numeric values", () => {
  assert.throws(() => canonicalizeAuditEvent({ amount: 1.5 }), /safe integer/);
  assert.throws(() => canonicalizeAuditEvent({ amount: Number.MAX_SAFE_INTEGER + 1 }), /safe integer/);
  assert.throws(() => canonicalizeAuditEvent({ amount: -0 }), /safe integer/);
});

test("rejects unsupported values and malformed UTF-16", () => {
  assert.throws(() => canonicalizeAuditEvent({ value: undefined }), /undefined/);
  assert.throws(() => canonicalizeAuditEvent({ value: "\ud800" }), /surrogate/);
  assert.throws(() => canonicalizeAuditEvent({ value: new Date() }), /unsupported/);
});

test("rejects malformed previous hash and invalid sequence", async () => {
  const base = { tenantId: "t1", event: { type: "X" } };
  await assert.rejects(calculateAuditEventHash({ ...base, sequence: 1, previousHashHex: "abcd" }), /64 hexadecimal/);
  await assert.rejects(calculateAuditEventHash({ ...base, sequence: 0, previousHashHex: "00".repeat(32) }), /uint64/);
  await assert.rejects(calculateAuditEventHash({ ...base, sequence: 1.2, previousHashHex: "00".repeat(32) }), /unsigned integer/);
});
