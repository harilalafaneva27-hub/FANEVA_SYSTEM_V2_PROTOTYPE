import test from "node:test";
import { readFile } from "node:fs/promises";
import assert from "node:assert/strict";
import { KdfTimingError, measureSynchronousKdf } from "./argon2id-kdf-timing.mjs";

test("measures exactly one synchronous KDF call with monotonic elapsed time", () => {
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

test("rejects zero elapsed time", () => {
  assert.throws(() => measureSynchronousKdf(() => "result", () => 7),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("rejects negative elapsed time", () => {
  const readings = [9, 8];
  assert.throws(() => measureSynchronousKdf(() => "result", () => readings.shift()),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("rejects non-finite finish readings", () => {
  const readings = [1, Number.NaN];
  assert.throws(() => measureSynchronousKdf(() => "result", () => readings.shift()),
    error => error instanceof KdfTimingError && error.message === "KDF_ELAPSED_TIME_INVALID");
});

test("rejects invalid start readings before invoking the KDF", () => {
  let calls = 0;
  assert.throws(() => measureSynchronousKdf(() => { calls += 1; }, () => Infinity),
    error => error instanceof KdfTimingError && error.message === "KDF_START_TIME_INVALID");
  assert.equal(calls, 0);
});

test("preserves KDF exceptions", () => {
  const expected = new Error("KDF_FAILED");
  assert.throws(() => measureSynchronousKdf(() => { throw expected; }, () => 1),
    error => error === expected);
});

test("rejects asynchronous operations", () => {
  let reading = 0;
  assert.throws(() => measureSynchronousKdf(() => Promise.resolve("result"), () => reading++),
    /must be synchronous/);
});

test("diagnostic Worker uses one measured KDF boundary and returns its elapsed value", async () => {
  const source = await readFile(new URL("./argon2id-security-validation-worker.ts", import.meta.url), "utf8");
  assert.match(source, /import \{ measureSynchronousKdf \} from "\.\/argon2id-kdf-timing\.mjs"/);
  assert.equal((source.match(/measureSynchronousKdf\(/g) ?? []).length, 1);
  assert.match(source, /kdfElapsedMs: measured\.elapsedMs/);
  assert.doesNotMatch(source, /performance\.now|Date\.now|wallClockElapsedMs|performanceElapsedMs/);
});

test("smoke script rejects zero KDF time and reports only the KDF measurement", async () => {
  const source = await readFile(new URL("./argon2id-single-request-smoke.mjs", import.meta.url), "utf8");
  assert.match(source, /Number\.isFinite\(result\.kdfElapsedMs\) && result\.kdfElapsedMs > 0/);
  assert.match(source, /deriveRequestCount: 1/);
  assert.match(source, /kdfElapsedMs: result\.kdfElapsedMs/);
  assert.doesNotMatch(source, /performanceElapsedMs|wallClockElapsedMs|kdfWallClockElapsedMs/);
});
