import { readFileSync } from "node:fs";

const config = readFileSync(new URL("../wrangler.argon2id-preview.toml", import.meta.url), "utf8");
const worker = readFileSync(new URL("./argon2id-security-validation-worker.ts", import.meta.url), "utf8");
const deploymentWorkflow = readFileSync(
  new URL("../../../.github/workflows/argon2id-preview-deployment-gated.yml", import.meta.url),
  "utf8",
);

const required = [
  /^name = "faneva-v2-argon2id-preview-diagnostic"$/m,
  /^main = "scripts\\/argon2id-security-validation-worker\\.ts"$/m,
  /^workers_dev = false$/m,
  /^preview_urls = true$/m,
  /^upload_source_maps = true$/m,
  /^\\[observability\\]\\s*\\nenabled = true$/m,
];
for (const pattern of required) {
  if (!pattern.test(config)) throw new Error("Preview config missing required setting: " + pattern);
}

const forbidden = /d1_databases|database_id|database_name|\\bD1Database\\b|binding\\s*=\\s*["']DB["']|CLOUDFLARE_API_TOKEN|CLOUDFLARE_ACCOUNT_ID|wrangler\\s+(deploy|versions\\s+upload)/i;
if (forbidden.test(config)) throw new Error("Preview config contains a forbidden D1, credential, or deployment reference");
if (forbidden.test(worker)) throw new Error("Diagnostic Worker source contains a forbidden D1, credential, or deployment reference");
if (!/from\\s+["']argon2id\\/lib\\/setup\\.js["']/.test(worker)) throw new Error("Diagnostic Worker Argon2id loader import is missing");
if (!/argon2id-simd\\.wasm/.test(worker) || !/argon2id-no-simd\\.wasm/.test(worker)) throw new Error("Diagnostic Worker WASM imports are missing");
if (!/d1Configured:\\s*false/.test(worker)) throw new Error("Diagnostic Worker must explicitly report D1 disabled");
if (!/timingAvailable:\\s*false/.test(worker)) throw new Error("Diagnostic Worker must not claim timer-based KDF timing");

// Deployment must be manual, explicitly confirmed, and restricted to this branch.
if (!/^on:\\s*\\n\\s+workflow_dispatch:/m.test(deploymentWorkflow)) {
  throw new Error("Diagnostic deployment must use workflow_dispatch only");
}
if (/^\\s+(pull_request|push|schedule|repository_dispatch):/m.test(deploymentWorkflow)) {
  throw new Error("Diagnostic deployment workflow contains an automatic trigger");
}
if (!/github\\.ref == ['"]refs\\/heads\\/feature\\/argon2id-preview-deployment-gated['"]/.test(deploymentWorkflow) ||
    !/inputs\\.confirm_deployment == ['"]DEPLOY_ISOLATED_PREVIEW['"]/.test(deploymentWorkflow)) {
  throw new Error("Diagnostic deployment is missing its exact branch and confirmation gate");
}
if (!/wrangler@4 deploy --config wrangler\\.argon2id-preview\\.toml/.test(deploymentWorkflow) ||
    !/wrangler@4 secret put DIAGNOSTIC_TOKEN --config wrangler\\.argon2id-preview\\.toml/.test(deploymentWorkflow)) {
  throw new Error("Deployment must use only the dedicated config and provision the runtime diagnostic secret");
}
if (!/CLOUDFLARE_API_TOKEN:\\s*\\$\\{\\{ secrets\\.CLOUDFLARE_API_TOKEN \\}\\}/.test(deploymentWorkflow) ||
    !/CLOUDFLARE_ACCOUNT_ID:\\s*\\$\\{\\{ secrets\\.CLOUDFLARE_ACCOUNT_ID \\}\\}/.test(deploymentWorkflow) ||
    !/DIAGNOSTIC_TOKEN:\\s*\\$\\{\\{ secrets\\.DIAGNOSTIC_TOKEN \\}\\}/.test(deploymentWorkflow)) {
  throw new Error("Deployment workflow must source credentials only from GitHub Actions secrets");
}
if (/wrangler\\.toml(?:\\s|$)/m.test(deploymentWorkflow) ||
    /--remote|versions\\s+upload|d1_databases|database_id|database_name/i.test(deploymentWorkflow)) {
  throw new Error("Deployment workflow references a staging config, remote override, or D1");
}
if (!/printf '%s' "\\$DIAGNOSTIC_TOKEN" \\| npx --yes wrangler@4 secret put DIAGNOSTIC_TOKEN/.test(deploymentWorkflow) ||
    /set -x/.test(deploymentWorkflow)) {
  throw new Error("Runtime secret must be piped from the masked environment without shell tracing");
}

console.log("PASS: isolated Preview config, Worker guardrails, and manually gated deployment workflow");
