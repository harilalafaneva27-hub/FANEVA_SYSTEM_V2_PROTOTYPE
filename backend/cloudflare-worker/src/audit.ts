import { sha256Hex } from "./crypto";

export interface SecurityAuditEventInput {
  eventId: string;
  eventType: string;
  occurredAt: number;
  actorPrincipalId?: string | null;
  actorDeviceId?: string | null;
  targetDeviceId?: string | null;
  requestId?: string | null;
  outcome: "SUCCESS" | "DENIED" | "FAILURE";
  metadata?: Record<string, string | number | boolean | null>;
}
export interface AuditGuard {
  targetDeviceStatus?: "ACTIVE" | "REVOKED";
  targetDeviceUpdatedAt?: number;
  actorPrincipalActive?: boolean;
}
interface AuditHeadRow { last_event_hash: string; last_sequence: number }
const ZERO_HASH = "0".repeat(64);
const encoder = new TextEncoder();

function canonicalMetadata(metadata: SecurityAuditEventInput["metadata"]): string {
  const entries = Object.entries(metadata ?? {}).sort(([a], [b]) => a.localeCompare(b));
  return JSON.stringify(Object.fromEntries(entries));
}

export async function prepareSecurityAuditEvent(
  env: { DB: D1Database },
  input: SecurityAuditEventInput,
  guard: AuditGuard = {},
): Promise<D1PreparedStatement> {
  if (!/^[A-Z][A-Z0-9_]{2,79}$/.test(input.eventType)) throw new Error("AUDIT_EVENT_TYPE_INVALID");
  if (!Number.isSafeInteger(input.occurredAt) || input.occurredAt <= 0) throw new Error("AUDIT_TIMESTAMP_INVALID");
  if (!/^[a-f0-9-]{16,64}$/i.test(input.eventId)) throw new Error("AUDIT_EVENT_ID_INVALID");
  const head = await env.DB.prepare(
    "SELECT last_event_hash,last_sequence FROM security_audit_head WHERE singleton_id=1",
  ).first<AuditHeadRow>();
  if (!head || !/^[a-f0-9]{64}$/.test(head.last_event_hash)) throw new Error("AUDIT_CHAIN_HEAD_INVALID");
  const metadataJson = canonicalMetadata(input.metadata);
  if (encoder.encode(metadataJson).byteLength > 8192) throw new Error("AUDIT_METADATA_TOO_LARGE");
  const canonical = JSON.stringify([
    "FANEVA-SECURITY-AUDIT", 1, input.eventId, input.eventType, input.occurredAt,
    input.actorPrincipalId ?? null, input.actorDeviceId ?? null,
    input.targetDeviceId ?? null, input.requestId ?? null, input.outcome,
    metadataJson, head.last_event_hash,
  ]);
  const eventHash = await sha256Hex(encoder.encode(canonical));
  let guardSql = "1=1";
  const values: unknown[] = [
    input.eventId, input.eventType, input.occurredAt,
    input.actorPrincipalId ?? null, input.actorDeviceId ?? null,
    input.targetDeviceId ?? null, input.requestId ?? null,
    input.outcome, metadataJson, head.last_event_hash, eventHash,
  ];
  if (guard.targetDeviceStatus) {
    values.push(input.targetDeviceId, guard.targetDeviceStatus);
    guardSql += ` AND EXISTS (SELECT 1 FROM device_registry WHERE device_id=?12 AND status=?13)`;
  }
  if (guard.targetDeviceUpdatedAt !== undefined) {
    values.push(input.targetDeviceId, guard.targetDeviceUpdatedAt);
    const a = values.length - 1, b = values.length;
    guardSql += ` AND EXISTS (SELECT 1 FROM device_registry WHERE device_id=?${a} AND enrolled_at=?${b})`;
  }
  if (guard.actorPrincipalActive) {
    values.push(input.actorPrincipalId);
    guardSql += ` AND EXISTS (SELECT 1 FROM auth_principals WHERE principal_id=?${values.length} AND status='ACTIVE')`;
  }
  return env.DB.prepare(
    `INSERT INTO security_audit_events
      (event_id,event_type,occurred_at,actor_principal_id,actor_device_id,target_device_id,
       request_id,outcome,metadata_json,previous_hash,event_hash)
     SELECT ?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11 WHERE ${guardSql}`,
  ).bind(...values);
}

export async function appendSecurityAuditEvent(
  env: { DB: D1Database }, input: SecurityAuditEventInput,
): Promise<void> {
  const result = await (await prepareSecurityAuditEvent(env, input)).run();
  if ((result.meta.changes ?? 0) !== 1) throw new Error("AUDIT_APPEND_FAILED");
}

export async function securityAuditEventHashForTest(
  input: SecurityAuditEventInput, previousHash = ZERO_HASH,
): Promise<string> {
  const metadataJson = canonicalMetadata(input.metadata);
  return sha256Hex(encoder.encode(JSON.stringify([
    "FANEVA-SECURITY-AUDIT", 1, input.eventId, input.eventType, input.occurredAt,
    input.actorPrincipalId ?? null, input.actorDeviceId ?? null,
    input.targetDeviceId ?? null, input.requestId ?? null, input.outcome,
    metadataJson, previousHash,
  ])));
}
