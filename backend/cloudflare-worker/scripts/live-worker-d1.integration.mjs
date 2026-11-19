import {
  createHash,
  generateKeyPairSync,
  randomBytes,
  sign,
} from "node:crypto";
import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";

const baseUrl = process.env.FANEVA_WORKER_BASE_URL ?? "http://127.0.0.1:8787";
const d1Name = "faneva-v2-staging";
const config = "wrangler.toml";

function sha256Hex(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

function makeSignedRequest(pathname, body, deviceId, privateKey, nonce = randomBytes(16).toString("hex")) {
  const timestamp = Math.floor(Date.now() / 1000).toString().padStart(10, "0");
  const bodyBytes = Buffer.from(body, "utf8");
  const canonical = [
    "FANEVA-V2-REQUEST",
    "1",
    "POST",
    pathname,
    timestamp,
    nonce,
    deviceId,
    sha256Hex(bodyBytes),
  ].join("\n");
  const signature = sign("sha256", Buffer.from(canonical, "utf8"), privateKey).toString("base64url");
  return {
    url: `${baseUrl}${pathname}`,
    init: {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "X-Faneva-Device-Id": deviceId,
        "X-Faneva-Timestamp": timestamp,
        "X-Faneva-Nonce": nonce,
        "X-Faneva-Signature": signature,
      },
      body,
    },
  };
}

function executeD1(sql) {
  return execFileSync(
    "npx",
    ["--yes", "wrangler@4", "d1", "execute", d1Name, "--remote", "--config", config, "--command", sql],
    { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] },
  );
}

async function main() {
  const health = await fetch(`${baseUrl}/health`);
  assert.equal(health.status, 200, "remote Worker preview health must be ready");
  const healthJson = await health.json();
  assert.equal(healthJson.environment, "staging");

  const { publicKey, privateKey } = generateKeyPairSync("ec", { namedCurve: "prime256v1" });
  const spki = publicKey.export({ format: "der", type: "spki" });
  const deviceId = `dev_${sha256Hex(spki)}`;
  const publicKeySpkiB64Url = spki.toString("base64url");
  const enrollmentBody = JSON.stringify({ deviceId, publicKeySpkiB64Url });
  const enrollment = makeSignedRequest("/v1/devices/enroll", enrollmentBody, deviceId, privateKey);

  let enrolled = false;
  try {
    const enrollmentResponse = await fetch(enrollment.url, enrollment.init);
    assert.equal(enrollmentResponse.status, 202, "enrollment must be accepted as PENDING");
    enrolled = true;
    const enrollmentJson = await enrollmentResponse.json();
    assert.equal(enrollmentJson.enrollmentStatus, "PENDING_OWNER_VERIFICATION");

    const enrollmentReplay = await fetch(enrollment.url, enrollment.init);
    assert.equal(enrollmentReplay.status, 409, "enrollment nonce replay must be rejected");

    const activationOutput = executeD1(
      `UPDATE device_registry SET status='ACTIVE', enrolled_at=unixepoch() WHERE device_id='${deviceId}' AND status='PENDING'; SELECT CASE WHEN changes()=1 THEN 'DEVICE_ACTIVATED' ELSE 'DEVICE_ACTIVATION_FAILED' END AS result;`,
    );
    assert.match(activationOutput, /DEVICE_ACTIVATED/, "temporary staging test device must activate");

    const verifyBody = "{}";
    const verify = makeSignedRequest("/v1/security/verify", verifyBody, deviceId, privateKey);
    const verifyResponse = await fetch(verify.url, verify.init);
    assert.equal(verifyResponse.status, 200, "active enrolled device signature must verify");

    const verifyReplay = await fetch(verify.url, verify.init);
    assert.equal(verifyReplay.status, 409, "authenticated request nonce replay must be rejected");

    const syncBody = JSON.stringify({
      protocolVersion: 2,
      deviceId,
      fromCursor: 0,
      operations: [],
    });
    for (const path of ["/v1/sync/bootstrap", "/v1/sync/push", "/v1/sync/pull"]) {
      // A valid device signature is not a user session. The sync handlers must
      // reject this request before reading or applying business mutations.
      const sync = makeSignedRequest(path, syncBody, deviceId, privateKey);
      const syncResponse = await fetch(sync.url, sync.init);
      assert.equal(syncResponse.status, 401, `${path} must require a bearer session`);
      const syncJson = await syncResponse.json();
      assert.equal(syncJson.error, "SESSION_INVALID", `${path} must fail closed without a bearer session`);
    }

    console.log("LIVE_WORKER_D1_HEALTH=PASS");
    console.log("LIVE_WORKER_D1_ENROLLMENT_PENDING=PASS");
    console.log("LIVE_WORKER_D1_ENROLLMENT_REPLAY_REJECTED=PASS");
    console.log("LIVE_WORKER_D1_ACTIVE_DEVICE_SIGNATURE=PASS");
    console.log("LIVE_WORKER_D1_AUTH_REPLAY_REJECTED=PASS");
    console.log("LIVE_WORKER_D1_SYNC_SESSION_REQUIRED=PASS");
  } finally {
    if (enrolled) {
      executeD1(
        `DELETE FROM request_nonces WHERE device_id='${deviceId}'; DELETE FROM device_registry WHERE device_id='${deviceId}';`,
      );
      console.log("LIVE_WORKER_D1_TEST_DEVICE_CLEANUP=PASS");
    }
  }
}

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});
