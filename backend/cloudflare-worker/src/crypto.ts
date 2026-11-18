const encoder = new TextEncoder();

export async function sha256Hex(value: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", value);
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
  let offset = 0;
  if (der[offset++] !== 0x30) throw new Error("Invalid ECDSA DER sequence");
  const sequenceLength = readDerLength(der, offset);
  offset = sequenceLength.next;
  if (sequenceLength.length !== der.length - offset) throw new Error("Invalid ECDSA DER length");

  const r = readDerInteger(der, offset);
  const s = readDerInteger(der, r.next);
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
  let length = 0;
  for (let i = 0; i < count; i++) length = (length << 8) | bytes[offset++];
  return { length, next: offset };
}

function readDerInteger(bytes: Uint8Array, offset: number): { value: Uint8Array; next: number } {
  if (bytes[offset++] !== 0x02) throw new Error("Invalid ECDSA DER integer");
  const size = readDerLength(bytes, offset);
  const end = size.next + size.length;
  if (size.length < 1 || end > bytes.length) throw new Error("Invalid ECDSA DER integer length");
  return { value: bytes.slice(size.next, end), next: end };
}

function normalizeInteger(value: Uint8Array, width: number): Uint8Array {
  let start = 0;
  while (start < value.length - 1 && value[start] === 0) start++;
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
    spki,
    { name: "ECDSA", namedCurve: "P-256" },
    false,
    ["verify"],
  );
  const rawSignature = derEcdsaToP1363(derSignature);
  return crypto.subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, rawSignature, message);
}

export function decodeBase64Url(value: string): Uint8Array {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error("Invalid base64url value");
  const normalized = value.replace(/-/g, "+").replace(/_/g, "/");
  const binary = atob(normalized + "=".repeat((4 - (normalized.length % 4)) % 4));
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}
