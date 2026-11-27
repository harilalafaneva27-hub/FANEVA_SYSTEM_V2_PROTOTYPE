import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

const packageRoot = process.env.ARGON2ID_PACKAGE_ROOT;
assert.ok(packageRoot, "ARGON2ID_PACKAGE_ROOT must point to the pinned argon2id package");
const setupModule = await import(pathToFileURL(join(packageRoot, "lib/setup.js")).href);
const setupWasm = setupModule.default;
assert.equal(typeof setupWasm, "function", "Pinned Argon2id setup loader must be available");

const simdWasm = await readFile(join(packageRoot, "dist/simd.wasm"));
const noSimdWasm = await readFile(join(packageRoot, "dist/no-simd.wasm"));
const argon2id = await setupWasm(
  async imports => ({ instance: await WebAssembly.instantiate(simdWasm, imports) }),
  async imports => ({ instance: await WebAssembly.instantiate(noSimdWasm, imports) }),
);

const password = Uint8Array.from({ length: 32 }, (_, index) => index);
const salt = Uint8Array.from({ length: 16 }, (_, index) => index + 16);
const output = argon2id({
  password,
  salt,
  parallelism: 1,
  passes: 2,
  memorySize: 19 * 1024,
  tagLength: 32,
});
const actualHex = Array.from(output, byte => byte.toString(16).padStart(2, "0")).join("");
const expectedHex = "5d2c6c66cf59f37a108b6da3d9f1d72d6a509729f80bffdcb195bd373c43b0bc";

// Independently generated with libargon2 through argon2-cffi 25.1.0.
// Argon2 version 0x13; password=00..1f; salt=10..1f;
// p=1, t=2, m=19456 KiB, tag=32 bytes; secret and associated data empty.
assert.equal(actualHex, expectedHex, "WASM Argon2id output must match the independent reference KAT");
console.log("ARGON2ID_OFFLINE_KAT_RESULT " + JSON.stringify({
  status: "PASS",
  package: "argon2id@1.0.1",
  outputLength: output.length,
  reference: "libargon2 via argon2-cffi 25.1.0",
  parameters: { version: 19, parallelism: 1, passes: 2, memorySizeKiB: 19456, tagLength: 32 },
}));
