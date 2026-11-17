import test from "node:test";
import assert from "node:assert/strict";
import { webcrypto } from "node:crypto";
import { calculateAuditEventHash, canonicalizeAuditEvent, parseAuditEventJson } from "../src/audit.mjs";

globalThis.crypto ??= webcrypto;

test("canonicalizes object keys in deterministic JCS order", () => {
  assert.equal(
    canonicalizeAuditEvent({ z: 1, eventType: "SALE_POSTED", amountMGA: 1200 }),
    '{"amountMGA":1200,"eventType":"SALE_POSTED","z":1}'
  );
});


test("strict parser rejects duplicate keys, including escaped-equivalent keys", () => {
  assert.throws(() => parseAuditEventJson('{"amount":1,"amount":2}'), /Duplicate JSON object key/);
  assert.throws(() => parseAuditEventJson('{"a":1,"\\u0061":2}'), /Duplicate JSON object key/);
  assert.throws(() => parseAuditEventJson('{"outer":{"x":1,"x":2}}'), /Duplicate JSON object key/);
});

test("strict parser rejects floats, exponent values, unsafe integers and trailing content", () => {
  assert.throws(() => parseAuditEventJson('{"amount":1.25}'), /safe integers|fractional/);
  assert.throws(() => parseAuditEventJson('{"amount":1e3}'), /safe integers|fractional|exponent/);
  assert.throws(() => parseAuditEventJson('{"amount":9007199254740992}'), /safe integers/);
  assert.throws(() => parseAuditEventJson('{"a":1} trailing'), /Trailing JSON content/);
});

test("strict parser safely preserves prototype-like keys and canonicalizes parsed data", () => {
  const event = parseAuditEventJson('{"__proto__":{"safe":true},"type":"GENESIS"}');
  assert.equal(Object.prototype.hasOwnProperty.call(event, "__proto__"), true);
  assert.equal(canonicalizeAuditEvent(event), '{"__proto__":{"safe":true},"type":"GENESIS"}');
});

test("strict parser accepts valid nested integer-only JSON", () => {
  const event = parseAuditEventJson('{"items":[{"qty":2},null,true],"note":"ok"}');
  assert.deepEqual(event, { items: [{ qty: 2 }, null, true], note: "ok" });
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
