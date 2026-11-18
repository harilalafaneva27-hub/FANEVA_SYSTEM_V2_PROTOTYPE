const encoder = new TextEncoder();

// Copy into a fresh ArrayBuffer so TypeScript and Web Crypto agree on BufferSource.
function asArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength);
  copy.set(bytes);
  return copy.buffer;
}

export async function sha256Hex(value: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", asArrayBuffer(value));
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export function canonicalRequest(input: {
  method: string;
  pathname: string;
  timestamp: string;
  nonce: string;
  deviceId: string;
  bodySha256: string;
}): Uint8Array {
  const canonical = [
    "FANEVA-V2-REQUEST",
    "1",
    input.method.toUpperCase(),
    input.pathname,
    input.timestamp,
    input.nonce,
    input.deviceId,
    input.bodySha256.toLowerCase(),
  ].join("\n");
  return encoder.encode(canonical);
}

// Android's SHA256withECDSA emits ASN.1 DER (r,s); WebCrypto ECDSA expects
// the IEEE-P1363 fixed-width r||s representation.
export function derEcdsaToP1363(der: Uint8Array, coordinateLength = 32): Uint8Array {
  if (!Number.isInteger(coordinateLength) || coordinateLength < 1) throw new Error("Invalid ECDSA coordinate width");
  let offset = 0;
  if (der[offset++] !== 0x30) throw new Error("Invalid ECDSA DER sequence");
  const sequenceLength = readDerLength(der, offset);
  offset = sequenceLength.next;
  if (sequenceLength.length !== der.length - offset) throw new Error("Invalid ECDSA DER length");

  const r = readDerInteger(der, offset, coordinateLength);
  const s = readDerInteger(der, r.next, coordinateLength);
  if (s.next !== der.length) throw new Error("Unexpected ECDSA DER trailing data");

  const out = new Uint8Array(coordinateLength * 2);
  out.set(normalizeInteger(r.value, coordinateLength), 0);
  out.set(normalizeInteger(s.value, coordinateLength), coordinateLength);
  return out;
}

function readDerLength(bytes: Uint8Array, offset: number): { length: number; next: number } {
  const first = bytes[offset++];
  if (first === undefined) throw new Error("Missing DER length");
  if ((first & 0x80) === 0) return { length: first, next: offset };
  const count = first & 0x7f;
  if (count === 0 || count > 2 || offset + count > bytes.length) throw new Error("Invalid DER length");
  if (bytes[offset] === 0) throw new Error("Non-minimal DER length");
  let length = 0;
  for (let i = 0; i < count; i++) length = (length << 8) | bytes[offset++];
  if ((count === 1 && length < 128) || (count === 2 && length <= 255) || length > bytes.length) {
    throw new Error("Non-canonical DER length");
  }
  return { length, next: offset };
}

function readDerInteger(
  bytes: Uint8Array,
  offset: number,
  coordinateLength: number,
): { value: Uint8Array; next: number } {
  if (bytes[offset++] !== 0x02) throw new Error("Invalid ECDSA DER integer");
  const size = readDerLength(bytes, offset);
  const end = size.next + size.length;
  if (size.length < 1 || size.length > coordinateLength + 1 || end > bytes.length) {
    throw new Error("Invalid ECDSA DER integer length");
  }
  const value = bytes.slice(size.next, end);
  if ((value[0] & 0x80) !== 0) throw new Error("Negative ECDSA integer");
  if (value[0] === 0 && (value.length === 1 || (value[1] & 0x80) === 0)) {
    throw new Error("Non-minimal ECDSA integer");
  }
  if (value.every((byte) => byte === 0)) throw new Error("Zero ECDSA integer");
  return { value, next: end };
}

function normalizeInteger(value: Uint8Array, width: number): Uint8Array {
  const start = value[0] === 0 ? 1 : 0;
  const significant = value.slice(start);
  if (significant.length > width) throw new Error("ECDSA integer exceeds curve width");
  const out = new Uint8Array(width);
  out.set(significant, width - significant.length);
  return out;
}

export async function verifyP256Signature(
  spki: Uint8Array,
  message: Uint8Array,
  derSignature: Uint8Array,
): Promise<boolean> {
  const key = await crypto.subtle.importKey(
    "spki",
    asArrayBuffer(spki),
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  const rawSignature = derEcdsaToP1363(derSignature);
  return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, asArrayBuffer(rawSignature), asArrayBuffer(message));
}

export function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("Invalid base64url value");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized + "=".repeat((4 - (normalized.length % 4)) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
