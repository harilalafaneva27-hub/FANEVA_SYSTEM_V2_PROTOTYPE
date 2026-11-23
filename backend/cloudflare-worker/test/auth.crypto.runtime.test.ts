import { describe, expect, it } from "vitest";
import {
  derivePasswordVerifier,
  generatePasswordSalt,
  PASSWORD_PBKDF2_ITERATIONS,
} from "../src/owner-bootstrap";

describe("Owner password derivation in the Cloudflare Workers runtime", () => {
  it("supports the production PBKDF2-SHA256 parameters and a generated 16-byte salt", async () => {
    const password = "Faneva-Admin-Strong-2026!";
    const salt = generatePasswordSalt();

    expect(salt).toBeInstanceOf(Uint8Array);
    expect(salt.byteLength).toBe(16);
    expect(PASSWORD_PBKDF2_ITERATIONS).toBe(310_000);

    const first = await derivePasswordVerifier(password, salt);
    const second = await derivePasswordVerifier(password, salt);

    expect(first).toMatch(/^[a-f0-9]{64}$/);
    expect(second).toBe(first);
  });

  it("rejects invalid password and salt inputs before Web Crypto derivation", async () => {
    const salt = generatePasswordSalt();
    await expect(derivePasswordVerifier("short", salt)).rejects.toThrow("PASSWORD_POLICY_INVALID");
    await expect(derivePasswordVerifier("Faneva-Admin-Strong-2026!", new Uint8Array(15)))
      .rejects.toThrow("PASSWORD_SALT_INVALID");
  });
});
