import assert from "node:assert/strict";
import { createHash, randomBytes } from "node:crypto";
import { spawn } from "node:child_process";
import { rm, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";

const accountId = process.env.CLOUDFLARE_ACCOUNT_ID ?? "";
const token = process.env.CLOUDFLARE_API_TOKEN ?? "";
assert.match(accountId, /^[a-f0-9]{32}$/i, "Cloudflare account ID is missing or invalid");
assert.ok(token, "Cloudflare API token is missing");
const port = 8790;
const origin = `http://127.0.0.1:${port}`;
const samples = [];
const concurrencyResults = [];
const sessionResults = [];
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
const stats = values => {
  const sorted = [...values].sort((a,b) => a-b);
  const percentile = p => sorted[Math.min(sorted.length-1, Math.ceil(p*sorted.length)-1)];
  const mean = values.reduce((a,b)=>a+b,0)/values.length;
  const variance = values.reduce((a,b)=>a+(b-mean)**2,0)/values.length;
  return { count: values.length, minMs: sorted[0], medianMs: percentile(.5),
    p90Ms: percentile(.9), p95Ms: percentile(.95), maxMs: sorted.at(-1),
    meanMs: mean, standardDeviationMs: Math.sqrt(variance) };
};
const sha256 = async path => createHash("sha256").update(await readFile(path)).digest("hex");
const safeFetch = async (path, challenge) => {
  const start = performance.now();
  const response = await fetch(origin + path, { method: "POST",
    headers: { authorization: "Bearer " + challenge, "content-type": "application/json" },
    body: "{}", signal: AbortSignal.timeout(30000) });
  const clientMs = performance.now() - start;
  const body = await response.json();
  if (!response.ok || body.ok !== true || body.d1Configured !== false) {
    throw new Error(`Remote test failed: HTTP ${response.status}, ${body.stage ?? "UNKNOWN"}`);
  }
  return { clientMs };
};
const runSession = async session => {
  const challenge = randomBytes(32).toString("hex");
  const config = [
    'name = "faneva-v2-argon2id-validation-isolated"',
    'main = "scripts/argon2id-security-validation-worker.ts"',
    'compatibility_date = "2026-11-18"',
    `account_id = "${accountId}"`,"","[vars]",`DIAGNOSTIC_TOKEN = "${challenge}"`,""
  ].join("\n");
  const configPath = join(process.cwd(), ".wrangler-argon2id-validation-" + randomBytes(8).toString("hex") + ".toml");
  await writeFile(configPath, config, { mode: 0o600 });
  let child;
  try {
    child = spawn("npx", ["--yes","wrangler@4","dev","--remote","--ip","127.0.0.1",
      "--port",String(port),"--config",configPath], { detached:true, stdio:["ignore","pipe","pipe"], env:process.env });
    let output = "";
    child.stdout.on("data", c => { output=(output+c.toString()).slice(-6000); });
    child.stderr.on("data", c => { output=(output+c.toString()).slice(-6000); });
    let ready = false;
    for (let i=0;i<90;i++) {
      if (child.exitCode !== null) throw new Error("Preview exited: "+output.slice(-1500));
      try {
        const r=await fetch(origin+"/derive",{method:"POST",headers:{authorization:"Bearer "+challenge},body:"{}",signal:AbortSignal.timeout(10000)});
        if (r.status===200) { const b=await r.json(); if(b.ok && b.d1Configured===false){ready=true;break;} }
      } catch {}
      await sleep(2000);
    }
    assert.ok(ready,"Remote preview readiness timed out");
    const unauthorized=await fetch(origin+"/derive",{method:"POST",body:"{}"});
    assert.equal(unauthorized.status,401,"Missing token must be rejected");
    const wrongPath=await fetch(origin+"/unknown",{method:"POST",headers:{authorization:"Bearer "+challenge}});
    assert.equal(wrongPath.status,404,"Unsupported path must be rejected");
    const correctness=await (await fetch(origin+"/correctness",{method:"POST",headers:{authorization:"Bearer "+challenge},body:"{}"})).json();
    assert.equal(correctness.ok,true,"Correctness/salt tests failed");
    const warmups=[];
    for(let i=0;i<3;i++) warmups.push(await safeFetch("/derive",challenge));
    const measured=[];
    for(let i=0;i<30;i++) measured.push(await safeFetch("/derive",challenge));
    const clientValues=measured.map(x=>x.clientMs);
    assert.ok(clientValues.every(x=>Number.isFinite(x)&&x>0),"Invalid client timing samples");
    const levels=[];
    for(const concurrency of [1,2,5]) {
      const level=[];
      for(let offset=0;offset<10;offset+=concurrency) {
        const count=Math.min(concurrency,10-offset);
        const batch=await Promise.all(Array.from({length:count},()=>safeFetch("/derive",challenge)));
        level.push(...batch);
      }
      levels.push({concurrency,requests:level.length,successes:level.length,errors:0,
        latency:stats(level.map(x=>x.clientMs)),rawLatencySamplesMs:level.map(x=>x.clientMs)});
    }
    const result={session,correctness:{deterministic:correctness.deterministic,
      uniqueSaltChangesOutput:correctness.uniqueSaltChangesOutput,
      passwordChangesOutput:correctness.passwordChangesOutput,
      invalidSaltRejected:correctness.invalidSaltRejected,
      invalidParametersRejected:correctness.invalidParametersRejected,outputLength:correctness.outputLength},
      warmupCount:warmups.length,remoteRoundTripLatency:stats(clientValues),
      rawLatencySamplesMs:clientValues,concurrency:levels};
    sessionResults.push(result);
    samples.push(...clientValues);
    concurrencyResults.push(...levels);
    console.log("SESSION_RESULT "+JSON.stringify(result));
  } finally {
    if(child?.pid) {
      const killGroup=signal=>{try{process.kill(-child.pid,signal)}catch{try{child.kill(signal)}catch{}}};
      killGroup("SIGTERM");
      await sleep(5000);
      killGroup("SIGKILL");
    }
    await rm(configPath,{force:true});
  }
};
try {
  const simd=await sha256("scripts/argon2id-simd.wasm");
  const noSimd=await sha256("scripts/argon2id-no-simd.wasm");
  console.log("ARTIFACT_SHA256 "+JSON.stringify({simd,noSimd,packageVersion:"argon2id@1.0.1"}));
  for(let session=1;session<=3;session++) await runSession(session);
  const report={status:"PASS",sessions:sessionResults,overallRemoteRoundTripLatency:stats(samples),
    concurrencyLevels:[1,2,5],totalMeasuredDerivations:samples.length,
    d1Configured:false,productionOrStagingDeployment:false,
    note:"Latency is end-to-end remote-preview round-trip (includes network/dispatch overhead), not isolated KDF CPU time. Configured KDF memory is not a measurement of peak platform memory."};
  console.log("ARGON2ID_SECURITY_PERFORMANCE_REPORT "+JSON.stringify(report));
} finally {
  await Promise.all([rm("scripts/argon2id-simd.wasm",{force:true}),rm("scripts/argon2id-no-simd.wasm",{force:true})]);
}
