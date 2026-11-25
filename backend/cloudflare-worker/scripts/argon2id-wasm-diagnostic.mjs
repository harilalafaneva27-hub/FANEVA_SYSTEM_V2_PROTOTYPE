import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const port = 8789;
const origin = `http://127.0.0.1:${port}`;
const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? "";
const token = process.env.CLOUDFLARE_API_TOKEN ?? "";
assert.match(accountId, /^[a-f0-9]{32}$/i, "Cloudflare account ID is missing or invalid");
assert.ok(token, "Cloudflare API token is missing");

const challenge = randomBytes(32).toString("hex");
const config = [
  'name = "faneva-v2-argon2id-isolated-diagnostic"',
  'main = "scripts/argon2id-wasm-diagnostic-worker.ts"',
  'compatibility_date = "2026-11-18"',
  `account_id = "${accountId}"`,
  "",
  "[vars]",
  `DIAGNOSTIC_TOKEN = "${challenge}"`,
  "",
].join("\n");
assert.doesNotMatch(config, /d1_databases|database_id|database_name|binding\s*=\s*"DB"/i);

const configPath = join(process.cwd(), ".wrangler-argon2id-" + randomBytes(8).toString("hex") + ".toml");
await writeFile(configPath, config, { mode: 0o600 });
let child;
try {
  child = spawn("npx", ["--yes", "wrangler@4", "dev", "--remote", "--ip", "127.0.0.1",
    "--port", String(port), "--config", configPath], { stdio: ["ignore", "pipe", "pipe"], env: process.env });
  let output = "";
  child.stdout.on("data", chunk => { output = (output + chunk.toString()).slice(-8000); });
  child.stderr.on("data", chunk => { output = (output + chunk.toString()).slice(-8000); });
  let completed = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    if (child.exitCode !== null) throw new Error("Remote preview exited before readiness: " + output.slice(-2000));
    try {
      const response = await fetch(origin + "/benchmark", {
        method: "POST",
        headers: { authorization: "Bearer " + challenge },
        signal: AbortSignal.timeout(15000),
      });
      if (response.status === 200 || response.status === 500) {
        const result = await response.json();
        if (result.ok === true && result.stage === "ARGON2ID_STATIC_WASM_RUNTIME_PASS" &&
            result.hashLength === 64 && result.d1Configured === false) {
          console.log("CLOUDFLARE_REMOTE_ARGON2ID=PASS");
          console.log(JSON.stringify(result));
          completed = true;
          break;
        }
        console.error("Cloudflare remote diagnostic response:", JSON.stringify({ status: response.status, result }));
        throw new Error("Cloudflare remote Argon2id failed: " + String(result.stage ?? "UNKNOWN"));
      }
    } catch (error) {
      if (error instanceof Error && error.message.startsWith("Cloudflare remote Argon2id")) throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  if (!completed) throw new Error("Remote Argon2id diagnostic timed out: " + output.slice(-2000));
} finally {
  if (child) {
    child.kill("SIGTERM");
    await new Promise(resolve => {
      const timeout = setTimeout(() => { child.kill("SIGKILL"); resolve(); }, 5000);
      child.once("exit", () => { clearTimeout(timeout); resolve(); });
    });
  }
  await rm(configPath, { force: true });
  await Promise.all([
    rm(join(process.cwd(), "scripts/argon2id-simd.wasm"), { force: true }),
    rm(join(process.cwd(), "scripts/argon2id-no-simd.wasm"), { force: true }),
  ]);
}
console.log("CLOUDFLARE_REMOTE_ARGON2ID_DIAGNOSTIC=SUCCESS");
