#!/usr/bin/env node
import { createHash, randomBytes } from "node:crypto";
import { createInterface } from "node:readline/promises";
import { stdin, stdout } from "node:process";
import { pathToFileURL } from "node:url";

export const EXPECTED_D1_ID = "db0a27e3-898c-4116-8730-7bd1da0a1a10";
export const EXPECTED_D1_NAME = "faneva-v2-staging-db";
export const EXPECTED_WORKER_NAME = "faneva-v2-staging-api";
export const BOOTSTRAP_TTL_SECONDS = 1800;

const API_ROOT = "https://api.cloudflare.com/client/v4";

export function generateBootstrapCode() {
  return randomBytes(32).toString("base64url");
}

export function hashBootstrapCode(code) {
  return createHash("sha256").update(code, "utf8").digest("hex");
}

async function cloudflareJson(fetchImpl, url, token, init = {}) {
  let response;
  try {
    response = await fetchImpl(url, {
      ...init,
      headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
    });
  } catch {
    throw new Error("CLOUDFLARE_API_UNREACHABLE");
  }
  let payload;
  try { payload = await response.json(); } catch {
    throw new Error("CLOUDFLARE_API_RESPONSE_INVALID");
  }
  if (!response.ok || payload.success !== true) throw new Error("CLOUDFLARE_API_REQUEST_FAILED");
  return payload;
}

async function d1Query(fetchImpl, accountId, token, sql, params) {
  const payload = await cloudflareJson(
    fetchImpl,
    `${API_ROOT}/accounts/${accountId}/d1/database/${EXPECTED_D1_ID}/query`,
    token,
    { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ sql, params }) },
  );
  const result = payload.result?.[0];
  if (!result || result.success !== true) throw new Error("D1_QUERY_FAILED");
  return result;
}

export async function issueOwnerBootstrapCode({
  token, accountId, databaseId, fetchImpl = fetch, now = Math.floor(Date.now() / 1000),
}) {
  if (!token || !/^[a-f0-9]{32}$/i.test(accountId ?? "")) throw new Error("CLOUDFLARE_CREDENTIALS_INVALID");
  if (databaseId !== EXPECTED_D1_ID) throw new Error("STAGING_D1_ID_MISMATCH");
  if (!Number.isSafeInteger(now) || now <= 0) throw new Error("ISSUED_AT_INVALID");

  const accountUrl = `${API_ROOT}/accounts/${accountId}`;
  const database = await cloudflareJson(fetchImpl, `${accountUrl}/d1/database/${EXPECTED_D1_ID}`, token);
  if (database.result?.uuid !== EXPECTED_D1_ID || database.result?.name !== EXPECTED_D1_NAME) {
    throw new Error("STAGING_D1_IDENTITY_MISMATCH");
  }

  const worker = await cloudflareJson(fetchImpl, `${accountUrl}/workers/scripts/${EXPECTED_WORKER_NAME}/settings`, token);
  const hasApprovedBinding = (worker.result?.bindings ?? []).some(
    binding => binding.type === "d1" && binding.name === "DB" && binding.id === EXPECTED_D1_ID,
  );
  if (!hasApprovedBinding) throw new Error("STAGING_WORKER_D1_BINDING_MISMATCH");

  await d1Query(
    fetchImpl, accountId, token,
    "UPDATE owner_bootstrap SET status='LOCKED', failed_attempts=5 WHERE status='ISSUED' AND expires_at<=?1",
    [String(now)],
  );

  const code = generateBootstrapCode();
  const digest = hashBootstrapCode(code);
  const bootstrapId = `boot_${randomBytes(16).toString("hex")}`;
  const inserted = await d1Query(
    fetchImpl, accountId, token,
    "INSERT INTO owner_bootstrap (bootstrap_id,code_sha256_hex,status,issued_at,expires_at,failed_attempts,max_attempts) SELECT ?1,?2,'ISSUED',?3,?4,0,5 WHERE NOT EXISTS (SELECT 1 FROM owner_bootstrap WHERE status='ISSUED')",
    [bootstrapId, digest, String(now), String(now + BOOTSTRAP_TTL_SECONDS)],
  );
  if ((inserted.meta?.changes ?? 0) !== 1) {
    throw new Error("OWNER_BOOTSTRAP_CODE_ALREADY_ISSUED_OR_INSERT_FAILED");
  }
  return { code, bootstrapId, expiresAt: now + BOOTSTRAP_TTL_SECONDS };
}

async function main() {
  if (!stdin.isTTY || !stdout.isTTY || process.env.CI === "true") {
    throw new Error("INTERACTIVE_TERMINAL_REQUIRED_REFUSING_TO_PRINT_BOOTSTRAP_CODE");
  }
  const token = process.env.CLOUDFLARE_API_TOKEN ?? "";
  const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? "";
  const databaseId = process.env.FANEVA_V2_STAGING_D1_ID ?? "";
  if (!token || !accountId || !databaseId) throw new Error("REQUIRED_CLOUDFLARE_ENVIRONMENT_VARIABLE_MISSING");

  stdout.write(`Target: ${EXPECTED_D1_NAME} (${EXPECTED_D1_ID})\n`);
  stdout.write(`Worker binding: ${EXPECTED_WORKER_NAME} / DB\n`);
  const rl = createInterface({ input: stdin, output: stdout });
  const answer = await rl.question("Type ISSUE_FANEVA_V2_OWNER_BOOTSTRAP_CODE to continue: ");
  rl.close();
  if (answer !== "ISSUE_FANEVA_V2_OWNER_BOOTSTRAP_CODE") throw new Error("BOOTSTRAP_ISSUANCE_NOT_CONFIRMED");

  const issued = await issueOwnerBootstrapCode({ token, accountId, databaseId });
  stdout.write("\nONE-TIME OWNER BOOTSTRAP CODE — valid for 30 minutes, single use:\n");
  stdout.write(`${issued.code}\n\n`);
  stdout.write("Copy it directly into the Android 1 owner-bootstrap flow. Do not paste it into GitHub, chat, issues, logs, or source files. The raw code is not stored by this script.\n");
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => {
    console.error(error instanceof Error ? error.message : "BOOTSTRAP_ISSUANCE_FAILED");
    process.exitCode = 1;
  });
}
