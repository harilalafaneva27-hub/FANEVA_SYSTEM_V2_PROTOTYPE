import { sha256Hex } from "./crypto";

const encoder = new TextEncoder();

function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}
export const OWNER_BOOTSTRAP_TTL_SECONDS = 30 * 60;
export const OWNER_BOOTSTRAP_MAX_ATTEMPTS = 5;
export const OWNER_BOOTSTRAP_CODE_BYTES = 32;
export const SESSION_TOKEN_BYTES = 32;
export const PASSWORD_PBKDF2_ITERATIONS = 100_000;
export const PASSWORD_PBKDF2_LEGACY_ITERATIONS = 310_000;

function toHex(bytes: Uint8Array): string {
  return [...bytes].map((value) => value.toString(16).padStart(2, "0")).join("");
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const value of bytes) binary += String.fromCharCode(value);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

/** Generates 256 bits from the platform CSPRNG. Call only in a controlled issuer flow. */
export function generateOwnerBootstrapCode(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(OWNER_BOOTSTRAP_CODE_BYTES));
  return toBase64Url(bytes);
}

/** Only this digest is persisted. The returned raw code must be shown once and discarded. */
export async function hashOwnerBootstrapCode(code: string): Promise<string> {
  if (!isOwnerBootstrapCodeFormatValid(code)) throw new Error("BOOTSTRAP_CODE_INVALID");
  return sha256Hex(encoder.encode(code));
}

export function isOwnerBootstrapCodeFormatValid(code: string): boolean {
  return /^[A-Za-z0-9_-]{43}$/.test(code);
}

export function generateServerSessionToken(): string {
  return toBase64Url(crypto.getRandomValues(new Uint8Array(SESSION_TOKEN_BYTES)));
}

export async function hashServerSessionToken(token: string): Promise<string> {
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new Error("SESSION_TOKEN_INVALID");
  return sha256Hex(encoder.encode(token));
}

/** Server-side PBKDF2 verifier; never persist or log the supplied password. */
export async function derivePasswordVerifier(password: string, salt: Uint8Array, iterations: number = PASSWORD_PBKDF2_ITERATIONS): Promise<string> {
  if (password.length < 12 || password.length > 128) throw new Error("PASSWORD_POLICY_INVALID");
  if (salt.byteLength !== 16) throw new Error("PASSWORD_SALT_INVALID");
  if (iterations !== PASSWORD_PBKDF2_ITERATIONS && iterations !== PASSWORD_PBKDF2_LEGACY_ITERATIONS) throw new Error("PASSWORD_KDF_ITERATIONS_INVALID");
  let key: CryptoKey;
  try {
    key = await crypto.subtle.importKey("raw", asArrayBuffer(encoder.encode(password)), "PBKDF2", false, ["deriveBits"]);
  } catch {
    throw new Error("PASSWORD_KDF_IMPORT_FAILED");
  }
  let bits: ArrayBuffer;
  try {
    bits = await crypto.subtle.deriveBits(
      { name: "PBKDF2", hash: "SHA-256", salt: asArrayBuffer(salt), iterations },
      key,
      256,
    );
  } catch {
    throw new Error("PASSWORD_KDF_DERIVE_FAILED");
  }
  return toHex(new Uint8Array(bits));
}

export function generatePasswordSalt(): Uint8Array {
  return crypto.getRandomValues(new Uint8Array(16));
}

export function encodePasswordSalt(salt: Uint8Array): string {
  return toBase64Url(salt);
}

export function bootstrapExpiry(issuedAtUnixSeconds: number): number {
  if (!Number.isSafeInteger(issuedAtUnixSeconds) || issuedAtUnixSeconds < 0) {
    throw new Error("BOOTSTRAP_ISSUED_AT_INVALID");
  }
  return issuedAtUnixSeconds + OWNER_BOOTSTRAP_TTL_SECONDS;
}

export function shouldLockBootstrapAfterFailure(failedAttempts: number): boolean {
  return Number.isInteger(failedAttempts) && failedAttempts >= OWNER_BOOTSTRAP_MAX_ATTEMPTS;
}
