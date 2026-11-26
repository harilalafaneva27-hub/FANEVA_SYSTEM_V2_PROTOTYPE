import { readFileSync } from "node:fs";

const config = readFileSync(new URL("../wrangler.argon2id-preview.toml", import.meta.url), "utf8");
const worker = readFileSync(new URL("./argon2id-security-validation-worker.ts", import.meta.url), "utf8");
const deploymentWorkflow = readFileSync(
  new URL("../../../.github/workflows/argon2id-preview-deployment-gated.yml", import.meta.url),
  "utf8",
);

const requiredConfig = [
  'name = "faneva-v2-argon2id-preview-diagnostic"',
  'main = "scripts/argon2id-security-validation-worker.ts"',
  "workers_dev = false",
  "preview_urls = true",
  "upload_source_maps = true",
  "[observability]",
  "enabled = true",
];
for (const setting of requiredConfig) {
  if (!config.includes(setting)) throw new Error("Preview config missing required setting: " + setting);
}

const forbiddenConfigOrWorker = [
  "d1_databases", "database_id", "database_name", "D1Database",
  "CLOUDFLARE_API_TOKEN", "CLOUDFLARE_ACCOUNT_ID", "wrangler deploy",
];
for (const marker of forbiddenConfigOrWorker) {
  if (config.includes(marker)) throw new Error("Preview config contains forbidden reference: " + marker);
  if (worker.includes(marker)) throw new Error("Diagnostic Worker source contains forbidden reference: " + marker);
}
if (!worker.includes('from "argon2id/lib/setup.js"')) throw new Error("Diagnostic Worker Argon2id loader import is missing");
if (!worker.includes("./argon2id-simd.wasm") || !worker.includes("./argon2id-no-simd.wasm")) {
  throw new Error("Diagnostic Worker WASM imports are missing");
}
if (!worker.includes("d1Configured: false")) throw new Error("Diagnostic Worker must explicitly report D1 disabled");
if (!worker.includes("timingAvailable: false")) throw new Error("Diagnostic Worker must not claim timer-based KDF timing");

// Deployment must be manual, explicitly confirmed, and restricted to this branch.
if (!deploymentWorkflow.includes("on:\n  workflow_dispatch:")) {
  throw new Error("Diagnostic deployment must use workflow_dispatch");
}
for (const trigger of ["pull_request:", "push:", "schedule:", "repository_dispatch:"]) {
  if (deploymentWorkflow.includes("\n  " + trigger)) {
    throw new Error("Diagnostic deployment workflow contains an automatic trigger: " + trigger);
  }
}
if (!deploymentWorkflow.includes("github.ref == 'refs/heads/feature/argon2id-preview-deployment-gated'") ||
    !deploymentWorkflow.includes("inputs.confirm_deployment == 'DEPLOY_ISOLATED_PREVIEW'")) {
  throw new Error("Diagnostic deployment is missing its exact branch and confirmation gate");
}
if (!deploymentWorkflow.includes("wrangler@4 deploy --config wrangler.argon2id-preview.toml") ||
    !deploymentWorkflow.includes("wrangler@4 secret put DIAGNOSTIC_TOKEN --config wrangler.argon2id-preview.toml")) {
  throw new Error("Deployment must use only the dedicated config and provision the runtime diagnostic secret");
}
for (const secretReference of [
  "CLOUDFLARE_API_TOKEN: ${{ secrets.CLOUDFLARE_API_TOKEN }}",
  "CLOUDFLARE_ACCOUNT_ID: ${{ secrets.CLOUDFLARE_ACCOUNT_ID }}",
  "DIAGNOSTIC_TOKEN: ${{ secrets.DIAGNOSTIC_TOKEN }}",
]) {
  if (!deploymentWorkflow.includes(secretReference)) {
    throw new Error("Deployment workflow is missing a required GitHub Actions secret reference");
  }
}
if (deploymentWorkflow.includes("--config wrangler.toml") ||
    deploymentWorkflow.includes("--remote") ||
    deploymentWorkflow.includes("versions upload") ||
    deploymentWorkflow.includes("d1_databases") ||
    deploymentWorkflow.includes("database_id") ||
    deploymentWorkflow.includes("database_name")) {
  throw new Error("Deployment workflow references a staging config, remote override, or D1");
}
if (!deploymentWorkflow.includes("printf '%s' \"$DIAGNOSTIC_TOKEN\" | npx --yes wrangler@4 secret put DIAGNOSTIC_TOKEN") ||
    deploymentWorkflow.includes("set -x")) {
  throw new Error("Runtime secret must be piped from the masked environment without shell tracing");
}
if (!deploymentWorkflow.includes('ln -s "$package_root/node_modules" node_modules')) {
  throw new Error("Deployment workflow must make the pinned Argon2id loader resolvable to Wrangler");
}

console.log("PASS: isolated Preview config, Worker guardrails, and manually gated deployment workflow");
