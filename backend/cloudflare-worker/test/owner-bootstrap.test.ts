import { describe, expect, it, vi } from "vitest";
import { failedOwnerBootstrapBatchSteps } from "../src/auth";
import {
  bootstrapExpiry,
  derivePasswordVerifier,
  encodePasswordSalt,
  generateOwnerBootstrapCode,
  generatePasswordSalt,
  generateServerSessionToken,
  hashOwnerBootstrapCode,
  hashServerSessionToken,
  isOwnerBootstrapCodeFormatValid,
  OWNER_BOOTSTRAP_MAX_ATTEMPTS,
  OWNER_BOOTSTRAP_TTL_SECONDS,
  PASSWORD_PBKDF2_ITERATIONS,
  PASSWORD_PBKDF2_LEGACY_ITERATIONS,
  shouldLockBootstrapAfterFailure,
} from "../src/owner-bootstrap";

describe("owner bootstrap batch diagnostics", () => {
  it("checks audit persistence by readback while retaining exact change-count guards for other batch steps", () => {
    const results: Array<{ meta?: { changes?: number } }> = Array.from({ length: 10 }, () => ({ meta: { changes: 1 } }));
    results[6] = { meta: { changes: 0 } };
    results[9] = { meta: { changes: 0 } };
    expect(failedOwnerBootstrapBatchSteps(results, true)).toEqual(["store_grant_A3"]);
    expect(failedOwnerBootstrapBatchSteps(results, false)).toEqual(["store_grant_A3", "bootstrap_audit_event"]);
  });

  it("reports missing or unexpected batch result counts without exposing result data", () => {
    expect(failedOwnerBootstrapBatchSteps([])).toEqual([
      "bootstrap_consume", "principal_create", "device_activate", "session_create",
      "store_grant_A1", "store_grant_A2", "store_grant_A3", "store_grant_A4",
      "store_grant_A5", "bootstrap_audit_event", "batch_result_count_mismatch",
    ]);
  });
});

describe("owner-verified bootstrap cryptographic primitives", () => {
  it("uses 100,000 iterations for new credentials and retains 310,000 legacy verification", () => {
    expect(PASSWORD_PBKDF2_ITERATIONS).toBe(100_000);
    expect(PASSWORD_PBKDF2_LEGACY_ITERATIONS).toBe(310_000);
  });
  it("generates a 256-bit one-time code and persists only its digest", async () => {
    const code = generateOwnerBootstrapCode();
    expect(isOwnerBootstrapCodeFormatValid(code)).toBe(true);
    expect(code).toHaveLength(43);
    const digest = await hashOwnerBootstrapCode(code);
    expect(digest).toMatch(/^[a-f0-9]{64}$/);
    expect(digest).not.toContain(code);
    expect(await hashOwnerBootstrapCode(code)).toBe(digest);
  });

  it("enforces a server-side 30-minute expiry", () => {
    expect(OWNER_BOOTSTRAP_TTL_SECONDS).toBe(1800);
    expect(bootstrapExpiry(1_800_000_000)).toBe(1_800_001_800);
  });

  it("locks at five failed attempts", () => {
    expect(OWNER_BOOTSTRAP_MAX_ATTEMPTS).toBe(5);
    expect(shouldLockBootstrapAfterFailure(4)).toBe(false);
    expect(shouldLockBootstrapAfterFailure(5)).toBe(true);
    expect(shouldLockBootstrapAfterFailure(6)).toBe(true);
  });

  it("generates high-entropy opaque server session tokens and stores only their digest", async () => {
    const token = generateServerSessionToken();
    expect(token).toHaveLength(43);
    const digest = await hashServerSessionToken(token);
    expect(digest).toMatch(/^[a-f0-9]{64}$/);
    expect(digest).not.toContain(token);
  });

  it("derives a salted PBKDF2-SHA256 password verifier", async () => {
    const salt = generatePasswordSalt();
    expect(encodePasswordSalt(salt)).toHaveLength(22);
    const first = await derivePasswordVerifier("Faneva-Secure-Admin-2026!", salt);
    const second = await derivePasswordVerifier("Faneva-Secure-Admin-2026!", salt);
    const other = await derivePasswordVerifier("Different-Secure-2026!", salt);
    expect(first).toHaveLength(64);
    expect(first).toBe(second);
    expect(first).not.toBe(other);
  });

  it("rejects malformed bootstrap codes and weak passwords", async () => {
    await expect(hashOwnerBootstrapCode("290493")).rejects.toThrow("BOOTSTRAP_CODE_INVALID");
    await expect(derivePasswordVerifier("short", generatePasswordSalt())).rejects.toThrow("PASSWORD_POLICY_INVALID");
    await expect(derivePasswordVerifier("Faneva-Secure-Admin-2026!", generatePasswordSalt(), 1)).rejects.toThrow("PASSWORD_KDF_ITERATIONS_INVALID");
  });

  it("classifies Web Crypto key-import failures without exposing implementation details", async () => {
    const spy = vi.spyOn(crypto.subtle, "importKey").mockRejectedValue(new Error("sensitive runtime detail"));
    try {
      await expect(derivePasswordVerifier("Faneva-Secure-Admin-2026!", generatePasswordSalt()))
        .rejects.toThrow("PASSWORD_KDF_IMPORT_FAILED");
    } finally {
      spy.mockRestore();
    }
  });

  it("classifies PBKDF2 derive failures without exposing implementation details", async () => {
    const spy = vi.spyOn(crypto.subtle, "deriveBits").mockRejectedValue(new Error("sensitive runtime detail"));
    try {
      await expect(derivePasswordVerifier("Faneva-Secure-Admin-2026!", generatePasswordSalt()))
        .rejects.toThrow("PASSWORD_KDF_DERIVE_FAILED");
    } finally {
      spy.mockRestore();
    }
  });
});
