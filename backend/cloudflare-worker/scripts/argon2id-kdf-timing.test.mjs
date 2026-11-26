import test from "node:test";
import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { KdfTimingError, measureSynchronousKdf } from "./argon2id-kdf-timing.mjs";

test("local-only helper measures one synchronous operation with a supplied clock", () => {
  const readings = [12.125, 15.875];
  let clockCalls = 0;
  let operationCalls = 0;
  const measured = measureSynchronousKdf(() => {
    operationCalls += 1;
    return new Uint8Array(32);
  }, () => readings[clockCalls++]);
  assert.equal(operationCalls, 1);
  assert.equal(clockCalls, 2);
  assert.equal(measured.value.length, 32);
  assert.equal(measured.elapsedMs, 3.75);
});

test("local-only helper rejects zero elapsed time", () => {
  assert.throws(() => measureSynchronousKdf(() => "result", () => 7),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("local-only helper rejects negative elapsed time", () => {
  const readings = [9, 8];
  assert.throws(() => measureSynchronousKdf(() => "result", () => readings.shift()),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("local-only helper rejects non-finite finish readings", () => {
  const readings = [1, Number.NaN];
  assert.throws(() => measureSynchronousKdf(() => "result", () => readings.shift()),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("local-only helper rejects invalid start readings before invoking the KDF", () => {
  let calls = 0;
  assert.throws(() => measureSynchronousKdf(() => { calls += 1; }, () => Infinity),
    error => error instanceof KdfTimingError && error.message === "KDF_START_TIME_INVALID");
  assert.equal(calls, 0);
});

test("local-only helper preserves KDF exceptions", () => {
  const expected = new Error("KDF_FAILED");
  assert.throws(() => measureSynchronousKdf(() => { throw expected; }, () => 1),
    error => error === expected);
});

test("local-only helper rejects asynchronous operations", () => {
  let reading = 0;
  assert.throws(() => measureSynchronousKdf(() => Promise.resolve("result"), () => reading++),
    /must be synchronous/);
});

test("deployed diagnostic Worker does not expose a per-KDF elapsed-time claim", async () => {
  const source = await readFile(new URL("./argon2id-security-validation-worker.ts", import.meta.url), "utf8");
  assert.doesNotMatch(source, /measureSynchronousKdf|performance\.now|Date\.now|kdfElapsedMs/);
  assert.match(source, /timingAvailable: false/);
  assert.match(source, /use Cloudflare CPU profiling/);
  assert.match(source, /const output = argon2id\(/);
});

test("smoke script labels client round-trip separately and rejects KDF timing fields", async () => {
  const source = await readFile(new URL("./argon2id-single-request-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /clientRoundTripIsNotKdfTime: true/);
  assert.match(source, /Object\.hasOwn\(result, "kdfElapsedMs"\)/);
  assert.match(source, /deriveRequestCount: 1/);
  assert.doesNotMatch(source, /kdfElapsedMs: result\.kdfElapsedMs/);
});
