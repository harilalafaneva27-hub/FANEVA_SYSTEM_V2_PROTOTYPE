import { describe, expect, it } from "vitest";
import { canonicalRequest, sha256Hex, verifyP256Signature } from "../src/crypto";

describe("FANEVA V2 request signature contract", () => {
  it("verifies P-256 SHA-256 signatures in Android-compatible DER form", async () => {
    const pair = await crypto.subtle.generateKey(
      { name: "ECDSA", namedCurve: "P-256" },
      true,
      ["sign", "verify"],
    );
    const spki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey));
    const body = new TextEncoder().encode('{"protocolVersion":2,"deviceId":"dev_test"}');
    const timestamp = "1795000000";
    const nonce = "00112233445566778899aabbccddeeff";
    const bodyHash = await sha256Hex(body);
    const message = canonicalRequest({
      method: "POST",
      pathname: "/v1/security/verify",
      timestamp,
      nonce,
      deviceId: "dev_test",
      bodySha256: bodyHash,
    });
    const rawSignature = new Uint8Array(await crypto.subtle.sign(
      { name: "ECDSA", hash: "SHA-256" },
      pair.privateKey,
      message,
    ));

    // Test adapter converts P1363 to DER, matching java.security.Signature output.
    const derSignature = p1363ToDer(rawSignature);
    expect(await verifyP256Signature(spki, message, derSignature)).toBe(true);
    expect(await verifyP256Signature(spki, new TextEncoder().encode("tampered"), derSignature)).toBe(false);
  });
});

function p1363ToDer(raw: Uint8Array): Uint8Array {
  const integer = (part: Uint8Array) => {
    let start = 0;
    while (start < part.length - 1 && part[start] === 0) start++;
    let value = part.slice(start);
    if (value[0] & 0x80) value = new Uint8Array([0, ...value]);
    return new Uint8Array([0x02, value.length, ...value]);
  };
  const r = integer(raw.slice(0, 32));
  const s = integer(raw.slice(32));
  const content = new Uint8Array([...r, ...s]);
  return new Uint8Array([0x30, content.length, ...content]);
}
