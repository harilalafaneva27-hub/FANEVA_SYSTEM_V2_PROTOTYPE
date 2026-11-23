import { describe, expect, it } from "vitest";
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
  shouldLockBootstrapAfterFailure,
} from "../src/owner-bootstrap";

describe("owner-verified bootstrap cryptographic primitives", () => {
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
  });
});
