import { createHash } from "node:crypto";
import { describe, expect, it } from "vitest";
import {
  shouldCleanupFixtureData,
  verifySecurityAuditChain,
} from "../scripts/disposable-staging-d1-live-runner.mjs";

const ZERO_HASH = "0".repeat(64);
const sha256 = value => createHash("sha256").update(value).digest("hex");

function makeEvent({
  sequence, eventId, eventType, occurredAt, metadataJson = "{}",
  previousHash = ZERO_HASH,
}) {
  const canonical = JSON.stringify([
    "FANEVA-SECURITY-AUDIT", 1, eventId, eventType, occurredAt,
    null, null, null, null, "SUCCESS", metadataJson, previousHash,
  ]);
  return {
    sequence,
    event_id: eventId,
    event_type: eventType,
    occurred_at: occurredAt,
    actor_principal_id: null,
    actor_device_id: null,
    target_device_id: null,
    request_id: null,
    outcome: "SUCCESS",
    metadata_json: metadataJson,
    previous_hash: previousHash,
    event_hash: sha256(canonical),
  };
}

describe("disposable roundtrip security audit verification", () => {
  it("accepts a pristine empty audit chain", () => {
    expect(verifySecurityAuditChain([], [{
      last_event_hash: ZERO_HASH,
      last_sequence: 0,
    }])).toMatchObject({ eventCount: 0, lastSequence: 0, lastEventHash: ZERO_HASH });
  });

  it("verifies contiguous sequence, canonical hashes, and the persisted head", () => {
    const first = makeEvent({
      sequence: 1, eventId: "audit-event-00000001",
      eventType: "DEVICE_ENROLLMENT", occurredAt: 100,
      metadataJson: '{"device":"fixture"}',
    });
    const second = makeEvent({
      sequence: 2, eventId: "audit-event-00000002",
      eventType: "DEVICE_REVOKED", occurredAt: 101,
      previousHash: first.event_hash,
    });
    expect(verifySecurityAuditChain([first, second], [{
      last_event_hash: second.event_hash,
      last_sequence: 2,
    }])).toMatchObject({
      eventCount: 2,
      lastSequence: 2,
      lastEventHash: second.event_hash,
    });
  });

  it("rejects a modified event hash", () => {
    const event = makeEvent({
      sequence: 1, eventId: "audit-event-00000003",
      eventType: "DEVICE_ENROLLMENT", occurredAt: 100,
    });
    event.event_hash = "f".repeat(64);
    expect(() => verifySecurityAuditChain([event], [{
      last_event_hash: event.event_hash,
      last_sequence: 1,
    }])).toThrow(/SHA-256 mismatch/);
  });

  it("rejects a fork or mismatched chain head", () => {
    const first = makeEvent({
      sequence: 1, eventId: "audit-event-00000004",
      eventType: "DEVICE_ENROLLMENT", occurredAt: 100,
    });
    const fork = makeEvent({
      sequence: 2, eventId: "audit-event-00000005",
      eventType: "DEVICE_REVOKED", occurredAt: 101,
      previousHash: ZERO_HASH,
    });
    expect(() => verifySecurityAuditChain([first, fork], [{
      last_event_hash: fork.event_hash,
      last_sequence: 2,
    }])).toThrow(/previous hash/);
    expect(() => verifySecurityAuditChain([first], [{
      last_event_hash: ZERO_HASH,
      last_sequence: 0,
    }])).toThrow(/head hash/);
  });

  it("allows destructive cleanup only after migrations and fixture setup", () => {
    expect(shouldCleanupFixtureData({ migrationsApplied: false, fixtureStarted: true })).toBe(false);
    expect(shouldCleanupFixtureData({ migrationsApplied: true, fixtureStarted: false })).toBe(false);
    expect(shouldCleanupFixtureData({ migrationsApplied: true, fixtureStarted: true })).toBe(true);
  });
});
