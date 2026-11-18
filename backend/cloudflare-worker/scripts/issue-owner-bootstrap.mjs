#!/usr/bin/env node
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { existsSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

// Offline issuer only. It never calls Cloudflare, D1, or a network endpoint.
// The owner must deliver the one-time code out-of-band and apply the SQL
// separately after verifying the V2 staging account/database identity.
const outputPath = resolve(process.cwd(), process.argv[2] ?? "owner-bootstrap-issue.sql");
if (existsSync(outputPath)) {
  throw new Error("Refusing to overwrite an existing bootstrap SQL file.");
}

const code = randomBytes(32).toString("base64url");
const digest = createHash("sha256").update(code, "utf8").digest("hex");
const bootstrapId = randomUUID();
const issuedAt = Math.floor(Date.now() / 1000);
const expiresAt = issuedAt + 30 * 60;

const sql = [
  "INSERT INTO owner_bootstrap (bootstrap_id, code_sha256_hex, status, issued_at, expires_at, failed_attempts, max_attempts)",
  `VALUES ('${bootstrapId}', '${digest}', 'ISSUED', ${issuedAt}, ${expiresAt}, 0, 5);`,
  "",
  "-- Apply only to the separately verified FANEVA V2 STAGING D1 database.",
].join("\n");

writeFileSync(outputPath, sql, { flag: "wx", mode: 0o600 });
process.stdout.write("OWNER BOOTSTRAP CODE (show once; deliver out-of-band):\n");
process.stdout.write(code + "\n");
process.stdout.write(`SQL file containing digest only: ${outputPath}\n`);
process.stdout.write("The SQL has NOT been applied. Verify the target D1 before any remote execution.\n");
