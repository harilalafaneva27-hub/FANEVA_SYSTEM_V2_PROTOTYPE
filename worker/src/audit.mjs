// FANEVA SYSTEM V2 — Worker-compatible audit hash implementation.
// Implements the approved FANEVA-AUDIT-V1 binary frame.
// Call only with an event object produced by a duplicate-key-rejecting JSON parser.

const encoder = new TextEncoder();
const DOMAIN = encoder.encode("FANEVA-AUDIT-V1\0");

function assertUnicodeScalarString(value, label) {
  for (let i = 0; i < value.length; i += 1) {
    const code = value.charCodeAt(i);
    if (code >= 0xd800 && code <= 0xdbff) {
      const next = value.charCodeAt(i + 1);
      if (!(next >= 0xdc00 && next <= 0xdfff)) {
        throw new TypeError(label + " contains an unpaired UTF-16 high surrogate");
      }
      i += 1;
    } else if (code >= 0xdc00 && code <= 0xdfff) {
      throw new TypeError(label + " contains an unpaired UTF-16 low surrogate");
    }
  }
}

function canonicalize(value, path = "$") {
  if (value === null || typeof value === "boolean") return JSON.stringify(value);
  if (typeof value === "string") {
    assertUnicodeScalarString(value, path);
    return JSON.stringify(value);
  }
  if (typeof value === "number") {
    if (!Number.isSafeInteger(value) || Object.is(value, -0)) {
      throw new TypeError(path + " must be a safe integer; floats, -0 and unsafe integers are forbidden");
    }
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) {
    const parts = [];
    for (let i = 0; i < value.length; i += 1) {
      if (!Object.prototype.hasOwnProperty.call(value, i)) {
        throw new TypeError(path + " contains an array hole");
      }
      parts.push(canonicalize(value[i], path + "[" + i + "]"));
    }
    return "[" + parts.join(",") + "]";
  }
  if (typeof value === "object" && Object.getPrototypeOf(value) === Object.prototype) {
    const keys = Object.keys(value).sort(); // ECMAScript UTF-16 code-unit ordering (JCS).
    const parts = [];
    for (const key of keys) {
      assertUnicodeScalarString(key, path + " key");
      const member = value[key];
      if (member === undefined) throw new TypeError(path + "." + key + " is undefined");
      parts.push(JSON.stringify(key) + ":" + canonicalize(member, path + "." + key));
    }
    return "{" + parts.join(",") + "}";
  }
  throw new TypeError(path + " contains an unsupported JSON value");
}

function u32be(value) {
  if (!Number.isInteger(value) || value < 0 || value > 0xffffffff) {
    throw new RangeError("tenant ID UTF-8 length exceeds uint32");
  }
  const out = new Uint8Array(4);
  new DataView(out.buffer).setUint32(0, value, false);
  return out;
}

function u64be(value) {
  let n;
  try { n = typeof value === "bigint" ? value : BigInt(value); }
  catch { throw new TypeError("sequence must be an unsigned integer"); }
  if (n < 1n || n > 0xffffffffffffffffn) {
    throw new RangeError("sequence must fit unsigned uint64 and be >= 1");
  }
  const out = new Uint8Array(8);
  new DataView(out.buffer).setBigUint64(0, n, false);
  return out;
}

function fromHex(hex) {
  if (typeof hex !== "string" || !/^[0-9a-fA-F]{64}$/.test(hex)) {
    throw new TypeError("previousHashHex must be exactly 64 hexadecimal characters");
  }
  const out = new Uint8Array(32);
  for (let i = 0; i < 32; i += 1) out[i] = Number.parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function concatBytes(...chunks) {
  const size = chunks.reduce((sum, chunk) => sum + chunk.length, 0);
  const out = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { out.set(chunk, offset); offset += chunk.length; }
  return out;
}

function toHex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}


export function parseAuditEventJson(source) {
  if (typeof source !== "string") throw new TypeError("audit JSON source must be a string");
  let index = 0;

  function fail(message) {
    throw new SyntaxError(message + " at JSON offset " + index);
  }
  function whitespace() {
    while (index < source.length) {
      const code = source.charCodeAt(index);
      if (code === 0x20 || code === 0x09 || code === 0x0a || code === 0x0d) index += 1;
      else break;
    }
  }
  function stringToken() {
    const start = index;
    if (source[index] !== '"') fail("Expected JSON string");
    index += 1;
    while (index < source.length) {
      const code = source.charCodeAt(index);
      if (code === 0x22) {
        index += 1;
        let value;
        try { value = JSON.parse(source.slice(start, index)); }
        catch { fail("Invalid JSON string"); }
        assertUnicodeScalarString(value, "JSON string");
        return value;
      }
      if (code === 0x5c) {
        index += 1;
        if (index >= source.length) fail("Unterminated JSON escape");
        if (source[index] === "u") {
          if (!/^[0-9a-fA-F]{4}$/.test(source.slice(index + 1, index + 5))) fail("Invalid Unicode escape");
          index += 5;
          continue;
        }
        if (!['"', "/", String.fromCharCode(92), "b", "f", "n", "r", "t"].includes(source[index])) {
          fail("Invalid JSON escape");
        }
        index += 1;
        continue;
      }
      if (code < 0x20) fail("Unescaped control character");
      index += 1;
    }
    fail("Unterminated JSON string");
  }
  function value() {
    whitespace();
    const ch = source[index];
    if (ch === '"') return stringToken();
    if (ch === "{") {
      index += 1;
      whitespace();
      const out = {};
      const seen = new Set();
      if (source[index] === "}") { index += 1; return out; }
      while (true) {
        whitespace();
        const key = stringToken();
        if (seen.has(key)) fail("Duplicate JSON object key " + JSON.stringify(key));
        seen.add(key);
        whitespace();
        if (source[index] !== ":") fail("Expected colon after object key");
        index += 1;
        const member = value();
        Object.defineProperty(out, key, { value: member, enumerable: true, writable: true, configurable: true });
        whitespace();
        if (source[index] === "}") { index += 1; return out; }
        if (source[index] !== ",") fail("Expected comma in object");
        index += 1;
      }
    }
    if (ch === "[") {
      index += 1;
      whitespace();
      const out = [];
      if (source[index] === "]") { index += 1; return out; }
      while (true) {
        out.push(value());
        whitespace();
        if (source[index] === "]") { index += 1; return out; }
        if (source[index] !== ",") fail("Expected comma in array");
        index += 1;
      }
    }
    for (const [literal, result] of [["true", true], ["false", false], ["null", null]]) {
      if (source.startsWith(literal, index)) { index += literal.length; return result; }
    }
    const number = /^-?(?:0|[1-9][0-9]*)/.exec(source.slice(index));
    if (number) {
      index += number[0].length;
      const parsed = Number(number[0]);
      if (!Number.isSafeInteger(parsed) || Object.is(parsed, -0)) {
        fail("Audit JSON numbers must be safe integers and cannot be negative zero");
      }
      if (source[index] === "." || source[index] === "e" || source[index] === "E") {
        fail("Audit JSON does not permit fractional or exponent numbers");
      }
      return parsed;
    }
    fail("Invalid JSON value");
  }

  const result = value();
  whitespace();
  if (index !== source.length) fail("Trailing JSON content");
  return result;
}

export function canonicalizeAuditEvent(event) {
  return canonicalize(event);
}

export async function calculateAuditEventHash({ tenantId, sequence, previousHashHex, event }) {
  if (typeof tenantId !== "string" || tenantId.length === 0) {
    throw new TypeError("tenantId must be a non-empty string");
  }
  assertUnicodeScalarString(tenantId, "tenantId");
  const tenantBytes = encoder.encode(tenantId.normalize("NFC"));
  const eventBytes = encoder.encode(canonicalizeAuditEvent(event));
  const previousHash = fromHex(previousHashHex);
  const frame = concatBytes(
    DOMAIN,
    u32be(tenantBytes.length),
    tenantBytes,
    u64be(sequence),
    previousHash,
    u64be(BigInt(eventBytes.length)),
    eventBytes
  );
  const digest = await globalThis.crypto.subtle.digest("SHA-256", frame);
  return toHex(new Uint8Array(digest));
}
