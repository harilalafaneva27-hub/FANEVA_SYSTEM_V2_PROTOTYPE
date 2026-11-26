import { readFileSync } from "node:fs";

const config = readFileSync(new URL("../wrangler.argon2id-preview.toml", import.meta.url), "utf8");
const worker = readFileSync(new URL("./argon2id-security-validation-worker.ts", import.meta.url), "utf8");

const required = [
  /^name = "faneva-v2-argon2id-preview-diagnostic"$/m,
  /^main = "scripts\/argon2id-security-validation-worker\.ts"$/m,
  /^workers_dev = false$/m,
  /^preview_urls = true$/m,
  /^upload_source_maps = true$/m,
  /^\[observability\]\s*\nenabled = true$/m,
];
for (const pattern of required) {
  if (!pattern.test(config)) throw new Error("Preview config missing required setting: " + pattern);
}

const forbidden = /d1_databases|database_id|database_name|\bD1Database\b|binding\s*=\s*["']DB["']|CLOUDFLARE_API_TOKEN|CLOUDFLARE_ACCOUNT_ID|wrangler\s+(deploy|versions\s+upload)/i;
if (forbidden.test(config)) throw new Error("Preview config contains a forbidden D1, credential, or deployment reference");
if (forbidden.test(worker)) throw new Error("Diagnostic Worker source contains a forbidden D1, credential, or deployment reference");
if (!/from\s+["']argon2id\/lib\/setup\.js["']/.test(worker)) throw new Error("Diagnostic Worker Argon2id loader import is missing");
if (!/argon2id-simd\.wasm/.test(worker) || !/argon2id-no-simd\.wasm/.test(worker)) throw new Error("Diagnostic Worker WASM imports are missing");
if (!/d1Configured:\s*false/.test(worker)) throw new Error("Diagnostic Worker must explicitly report D1 disabled");
if (!/timingAvailable:\s*false/.test(worker)) throw new Error("Diagnostic Worker must not claim timer-based KDF timing");

console.log("PASS: isolated Preview config and diagnostic source guardrails");
