import { describe, expect, it } from "vitest";
import {
  EXPECTED_D1_ID, EXPECTED_D1_NAME, EXPECTED_WORKER_NAME,
  generateBootstrapCode, hashBootstrapCode, issueOwnerBootstrapCode,
} from "../scripts/issue-owner-bootstrap.mjs";

function mockCloudflare({ insertChanges = 1, bindingId = EXPECTED_D1_ID } = {}) {
  const calls = [];
  const fetchImpl = async (url, init = {}) => {
    calls.push({ url, init });
    if (url.endsWith(`/d1/database/${EXPECTED_D1_ID}`)) {
      return new Response(JSON.stringify({ success: true, result: { uuid: EXPECTED_D1_ID, name: EXPECTED_D1_NAME } }), { status: 200 });
    }
    if (url.endsWith(`/workers/scripts/${EXPECTED_WORKER_NAME}/settings`)) {
      return new Response(JSON.stringify({ success: true, result: { bindings: [{ type: "d1", name: "DB", id: bindingId }] } }), { status: 200 });
    }
    if (url.endsWith(`/d1/database/${EXPECTED_D1_ID}/query`)) {
      const request = JSON.parse(init.body);
      const changes = request.sql.startsWith("INSERT INTO owner_bootstrap") ? insertChanges : 0;
      return new Response(JSON.stringify({ success: true, result: [{ success: true, meta: { changes }, results: [] }] }), { status: 200 });
    }
    throw new Error("Unexpected mock URL");
  };
  return { fetchImpl, calls };
}

describe("one-time owner bootstrap issuer", () => {
  it("generates a 256-bit base64url code and stores only its SHA-256 digest", async () => {
    const code = generateBootstrapCode();
    expect(code).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(hashBootstrapCode(code)).toMatch(/^[a-f0-9]{64}$/);

    const mock = mockCloudflare();
    const issued = await issueOwnerBootstrapCode({
      token: "test-token", accountId: "a".repeat(32), databaseId: EXPECTED_D1_ID,
      fetchImpl: mock.fetchImpl, now: 1_800_000_000,
    });
    expect(issued.code).toMatch(/^[A-Za-z0-9_-]{43}$/);
    const insertCall = mock.calls.find(call => call.url.endsWith("/query") &&
      JSON.parse(call.init.body).sql.startsWith("INSERT INTO owner_bootstrap"));
    const params = JSON.parse(insertCall.init.body).params;
    expect(params[1]).toBe(hashBootstrapCode(issued.code));
    expect(params).not.toContain(issued.code);
    expect(issued.expiresAt).toBe(1_800_001_800);
  });

  it("refuses to issue a code when the live Worker binding is not the approved D1", async () => {
    const mock = mockCloudflare({ bindingId: "wrong-database-id" });
    await expect(issueOwnerBootstrapCode({
      token: "test-token", accountId: "a".repeat(32), databaseId: EXPECTED_D1_ID,
      fetchImpl: mock.fetchImpl, now: 1_800_000_000,
    })).rejects.toThrow("STAGING_WORKER_D1_BINDING_MISMATCH");
    expect(mock.calls.some(call => call.url.endsWith("/query"))).toBe(false);
  });

  it("does not return a raw code when another unexpired code is already issued", async () => {
    const mock = mockCloudflare({ insertChanges: 0 });
    await expect(issueOwnerBootstrapCode({
      token: "test-token", accountId: "a".repeat(32), databaseId: EXPECTED_D1_ID,
      fetchImpl: mock.fetchImpl, now: 1_800_000_000,
    })).rejects.toThrow("OWNER_BOOTSTRAP_CODE_ALREADY_ISSUED_OR_INSERT_FAILED");
  });
});
