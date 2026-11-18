#!/usr/bin/env node
import { createHash, randomBytes, randomUUID } from "node:crypto";
import { existsSync, writeFileSync } from "node:fs";
import { resolve } from "node:path";

// Offline issuer only. It never calls Cloudflare, D1, or a network endpoint.
// Keep the .code file private and deliver its contents out-of-band.
const prefix = resolve(process.cwd(), process.argv[2] ?? "owner-bootstrap-issue");
const codePath = `${prefix}.code`;
const sqlPath = `${prefix}.sql`;
if (existsSync(codePath) || existsSync(sqlPath)) {
  throw new Error("Refusing to overwrite an existing bootstrap output file.");
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
].join("\\n");

writeFileSync(codePath, code + "\\n", { flag: "wx", mode: 0o600 });
writeFileSync(sqlPath, sql, { flag: "wx", mode: 0o600 });
process.stdout.write("Bootstrap prepared offline; no database was contacted.\\n");
process.stdout.write(`One-time code file (mode 0600): ${codePath}\\n`);
process.stdout.write(`Digest-only SQL file (mode 0600): ${sqlPath}\\n`);
process.stdout.write("Manually inspect both files, deliver the code out-of-band, then securely dispose of the .code file.\\n");
process.stdout.write("Do not apply SQL until the V2 STAGING account and D1 ID are independently verified.\\n");
