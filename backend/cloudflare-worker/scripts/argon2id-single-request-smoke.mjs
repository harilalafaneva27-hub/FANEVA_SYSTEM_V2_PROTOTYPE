import assert from "node:assert/strict";
import { randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { extractCloudflareErrorDiagnostics } from "./argon2id-preview-response.mjs";

const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? "";
const token = process.env.CLOUDFLARE_API_TOKEN ?? "";
assert.match(accountId, /^[a-f0-9]{32}$/i, "Cloudflare account ID is missing or invalid");
assert.ok(token, "Cloudflare API token is missing");
const correctnessMode = process.argv.includes("--correctness");

const port = 8791;
const origin = `http://127.0.0.1:${port}`;
const challenge = randomBytes(32).toString("hex");
const config = [
  'name = "faneva-v2-argon2id-validation-isolated"',
  'main = "scripts/argon2id-security-validation-worker.ts"',
  'compatibility_date = "2026-11-18"',
  `account_id = "${accountId}"`,
  "",
  "[observability]",
  "enabled = false",
  "",
  "[observability.logs]",
  "enabled = false",
  "invocation_logs = false",
  "persist = false",
  "",
  "[observability.traces]",
  "enabled = false",
  "persist = false",
  "",
  "[vars]",
  `DIAGNOSTIC_TOKEN = "${challenge}"`,
  "",
].join("\n");
const configPath = join(process.cwd(), ".wrangler-argon2id-smoke-" + randomBytes(8).toString("hex") + ".toml");
let child;
let output = "";
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const appendOutput = chunk => {
  const safe = chunk.toString().replaceAll(token, "[REDACTED]").replaceAll(challenge, "[REDACTED]");
  output = (output + safe).slice(-8000);
};

const diagnostics = async (response, label) => {
  const raw = await response.text();
  let body = null;
  try { body = JSON.parse(raw); } catch {}
  if (!response.ok || body === null) {
    const details = extractCloudflareErrorDiagnostics(raw, response.headers);
    throw new Error(label + ": " + JSON.stringify({
      status: response.status,
      contentType: response.headers.get("content-type") ?? "(missing)",
      ...details,
      jsonBody: body === null ? undefined : JSON.stringify(body).slice(0, 500),
      wranglerOutputTail: output.slice(-2500) || "(none)",
    }));
  }
  return body;
};

try {
  await writeFile(configPath, config, { mode: 0o600 });
  child = spawn(
    "npx",
    ["--yes", "wrangler@4", "dev", "--remote", "--ip", "127.0.0.1",
      "--port", String(port), "--config", configPath],
    { detached: true, stdio: ["ignore", "pipe", "pipe"], env: process.env },
  );
  child.stdout.on("data", appendOutput);
  child.stderr.on("data", appendOutput);
  child.on("error", error => appendOutput("\\n[wrangler spawn error] " + error.message));
  child.on("exit", (code, signal) =>
    appendOutput("\\n[wrangler process exit] code=" + code + " signal=" + signal));

  let ready = false;
  let lastHealthError = "(no response)";
  for (let attempt = 0; attempt < 90; attempt++) {
    if (child.exitCode !== null) throw new Error("Remote preview exited before health check: " + output.slice(-2000));
    try {
      const response = await fetch(origin + "/health", {
        method: "GET",
        headers: { authorization: "Bearer " + challenge },
        signal: AbortSignal.timeout(5000),
      });
      const body = await diagnostics(response, "Preview health check");
      assert.equal(body.ok, true);
      assert.equal(body.d1Configured, false);
      assert.equal(body.kdfExecuted, false, "Health probe must not execute Argon2id");
      ready = true;
      break;
    } catch (error) {
      lastHealthError = error instanceof Error ? error.message : String(error);
      if (lastHealthError.includes('"status":5')) throw error;
      await sleep(1000);
    }
  }
  if (!ready) {
    throw new Error("Preview health check timed out: " + JSON.stringify({
      lastHealthError,
      wranglerExitCode: child.exitCode,
      wranglerSignalCode: child.signalCode,
      wranglerOutputTail: output.slice(-6000) || "(no Wrangler output captured)",
    }));
  }

  const unauthorized = await fetch(origin + "/health");
  assert.equal(unauthorized.status, 401, "Health endpoint must require diagnostic token");

  const started = performance.now();
  const response = await fetch(origin + (correctnessMode ? "/correctness" : "/derive"), {
    method: "POST",
    headers: {
      authorization: "Bearer " + challenge,
      "content-type": "application/json",
    },
    body: "{}",
    signal: AbortSignal.timeout(30000),
  });
  const clientRoundTripMs = performance.now() - started;
  const result = await diagnostics(response, correctnessMode ? "Argon2id correctness suite" : "Single Argon2id derive");
  assert.equal(result.ok, true);
  assert.equal(result.d1Configured, false);
  assert.equal(result.outputLength, 32);

  if (correctnessMode) {
    assert.equal(result.stage, "CORRECTNESS_PASS");
    assert.equal(result.deterministic, true);
    assert.equal(result.uniqueSaltChangesOutput, true);
    assert.equal(result.passwordChangesOutput, true);
    assert.equal(result.invalidSaltRejected, true);
    assert.equal(result.invalidParametersRejected, true);
    assert.equal(result.knownAnswerMatches, true);
    assert.equal(result.parameters.parallelism, 1);
    assert.equal(result.parameters.passes, 2);
    assert.equal(result.parameters.memorySize, 19 * 1024);
    assert.equal(result.parameters.memorySizeUnit, "KiB");
    assert.equal(result.parameters.tagLength, 32);
    console.log("ARGON2ID_CORRECTNESS_PREVIEW_RESULT " + JSON.stringify({
      status: "PASS",
      remoteRequestCount: 1,
      kdfDeriveCount: 6,
      deterministic: result.deterministic,
      uniqueSaltChangesOutput: result.uniqueSaltChangesOutput,
      passwordChangesOutput: result.passwordChangesOutput,
      invalidSaltRejected: result.invalidSaltRejected,
      invalidParametersRejected: result.invalidParametersRejected,
      knownAnswerMatches: result.knownAnswerMatches,
      outputLength: result.outputLength,
      d1Configured: false,
      previewMode: "wrangler-dev-remote-temporary",
      deployment: false,
    }));
  } else {
    assert.equal(result.timingAvailable, false);
    assert.match(result.timingNote, /CPU profiling/);
    assert.equal(Object.hasOwn(result, "kdfElapsedMs"), false,
      "The Worker must not claim a per-KDF duration from a frozen timer");
    console.log("ARGON2ID_SINGLE_REQUEST_SMOKE_RESULT " + JSON.stringify({
      status: "PASS",
      deriveRequestCount: 1,
      clientRoundTripMs,
      clientRoundTripIsNotKdfTime: true,
      outputLength: result.outputLength,
      d1Configured: false,
      previewMode: "wrangler-dev-remote-temporary",
      deployment: false,
    }));
  }
} catch (error) {
  console.error("ARGON2ID_SINGLE_REQUEST_SMOKE_FAILURE " +
    (error instanceof Error ? error.message : String(error)));
  throw error;
} finally {
  if (child?.pid) {
    const killGroup = signal => {
      try { process.kill(-child.pid, signal); }
      catch { try { child.kill(signal); } catch {} }
    };
    killGroup("SIGTERM");
    await sleep(3000);
    killGroup("SIGKILL");
  }
  await rm(configPath, { force: true });
}
