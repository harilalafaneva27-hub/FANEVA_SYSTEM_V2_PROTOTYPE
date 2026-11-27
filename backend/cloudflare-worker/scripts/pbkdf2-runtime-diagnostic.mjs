import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { rm, writeFile } from "node:fs/promises";
import { join } from "node:path";

const port = 8788;
const origin = `http://127.0.0.1:${port}`;
const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? "";
const token = process.env.CLOUDFLARE_API_TOKEN ?? "";
assert.match(accountId, /^[a-f0-9]{32}$/i, "Cloudflare account ID is missing or invalid");
assert.ok(token, "Cloudflare API token is missing");

const challenge = randomBytes(32).toString("hex");
const config = [
  'name = "faneva-v2-pbkdf2-diagnostic"',
  'main = "scripts/pbkdf2-runtime-diagnostic-worker.ts"',
  'compatibility_date = "2026-11-18"',
  `account_id = "${accountId}"`,
  "",
  "[vars]",
  `DIAGNOSTIC_TOKEN = "${challenge}"`,
  "",
].join("\n");
assert.doesNotMatch(config, /d1_databases|database_id|database_name|binding\\s*=\\s*"DB"/i,
  "Isolated diagnostic config must not contain a D1 binding");

const configPath = join(process.cwd(), ".wrangler-pbkdf2-runtime-" + randomBytes(8).toString("hex") + ".toml");
await writeFile(configPath, config, { mode: 0o600 });
let child;
try {
  // A detached process group lets cleanup terminate npm, Wrangler, and workerd together.
  child = spawn("npx", ["--yes", "wrangler@4", "dev", "--remote", "--ip", "127.0.0.1",
    "--port", String(port), "--config", configPath], {
    stdio: ["ignore", "pipe", "pipe"],
    env: process.env,
    detached: true,
  });
  child.stdout.on("data", () => {});
  child.stderr.on("data", () => {});
  let ready = false;
  for (let attempt = 0; attempt < 90; attempt++) {
    if (child.exitCode !== null) throw new Error("Remote preview exited before becoming ready");
    try {
      const response = await fetch(origin + "/diagnostic", {
        method: "POST",
        headers: { authorization: "Bearer " + challenge },
        signal: AbortSignal.timeout(5000),
      });
      if (response.status === 200 || response.status === 500) {
        const result = await response.json();
        if (result.ok === true && result.stage === "PBKDF2_MATRIX_COMPLETE" &&
            Array.isArray(result.cases) && result.cases.length === 10 &&
            result.productionHelper && typeof result.productionHelper.ok === "boolean") {
          for (const item of result.cases) {
            console.log(`PBKDF2_ITERATIONS_${item.iterations}=${item.ok ? "PASS" : "FAIL"}` +
              (item.errorName ? ` ERROR_CODE=${String(item.errorName).slice(0, 48)}` : ""));
          }
          console.log(`PBKDF2_PRODUCTION_HELPER=${result.productionHelper.ok ? "PASS" : "FAIL"}` +
            (result.productionHelper.errorName ? ` ERROR_CODE=${String(result.productionHelper.errorName).slice(0, 48)}` : ""));
          console.log("CLOUDFLARE_REMOTE_PBKDF2_DIAGNOSTIC=RESULTS_CAPTURED");
          ready = true;
          break;
        }
        throw new Error("Cloudflare remote PBKDF2 diagnostic failed at stage: " +
          String(result.stage ?? "UNKNOWN").slice(0, 64));
      }
    } catch (error) {
      if (error instanceof Error && error.message.startsWith("Cloudflare remote")) throw error;
    }
    await new Promise(resolve => setTimeout(resolve, 2000));
  }
  if (!ready) throw new Error("Remote PBKDF2 diagnostic timed out");
} finally {
  if (child?.pid) {
    const signalGroup = signal => {
      try { process.kill(-child.pid, signal); } catch (error) {
        if (error?.code !== "ESRCH") throw error;
      }
    };
    signalGroup("SIGTERM");
    await Promise.race([
      new Promise(resolve => child.once("exit", resolve)),
      new Promise(resolve => setTimeout(resolve, 5000)),
    ]);
    // Kill any Wrangler/workerd descendants still alive after the graceful shutdown.
    signalGroup("SIGKILL");
  }
  await rm(configPath, { force: true });
}
console.log("CLOUDFLARE_REMOTE_PBKDF2_DIAGNOSTIC=SUCCESS");
