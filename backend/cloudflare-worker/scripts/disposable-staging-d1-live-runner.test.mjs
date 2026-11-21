import assert from "node:assert/strict";
import test from "node:test";
import { validateInputs, makeTemporaryWranglerConfig } from "./disposable-staging-d1-live-runner.mjs";

const valid = {
  environment: "staging",
  databaseName: "faneva-v2-disposable-qa-20261121",
  databaseId: "123e4567-e89b-42d3-a456-426614174000",
  confirmDatabaseName: "faneva-v2-disposable-qa-20261121",
  confirmDatabaseId: "123e4567-e89b-42d3-a456-426614174000",
  allowLiveWrites: "YES",
};

test("accepts only an explicitly confirmed disposable STAGING D1 target", () => {
  assert.deepEqual(validateInputs(valid), {
    environment: "staging",
    databaseName: valid.databaseName,
    databaseId: valid.databaseId,
  });
});

test("rejects the main STAGING database name", () => {
  assert.throws(() => validateInputs({
    ...valid,
    databaseName: "faneva-v2-staging-db",
    confirmDatabaseName: "faneva-v2-staging-db",
  }), /DISPOSABLE_STAGING_TARGET_REJECTED/);
});

test("rejects mismatched UUID confirmation and missing live-write confirmation", () => {
  assert.throws(() => validateInputs({ ...valid, confirmDatabaseId: "123e4567-e89b-42d3-a456-426614174001" }),
    /DISPOSABLE_STAGING_TARGET_REJECTED/);
  assert.throws(() => validateInputs({ ...valid, allowLiveWrites: "NO" }),
    /DISPOSABLE_STAGING_TARGET_REJECTED/);
});

test("rewrites only the isolated preview name and disposable D1 binding", () => {
  const source = [
    'name = "faneva-v2-staging-device-auth"',
    'main = "src/index.ts"',
    'compatibility_date = "2026-11-18"',
    "",
    "[vars]",
    'FANEVA_ENVIRONMENT = "staging"',
    "",
    "[[d1_databases]]",
    'binding = "DB"',
    'database_name = "faneva-v2-staging-db"',
    'database_id = "REPLACE_WITH_VERIFIED_STAGING_DATABASE_ID"',
    'migrations_dir = "migrations"',
    "",
  ].join("\n");
  const output = makeTemporaryWranglerConfig(source, valid.databaseName, valid.databaseId,
    "12345678-1234-4234-8234-123456789abc");
  assert.match(output, /name = "faneva-v2-disposable-preview-12345678-1234"/);
  assert.match(output, /database_name = "faneva-v2-disposable-qa-20261121"/);
  assert.match(output, /database_id = "123e4567-e89b-42d3-a456-426614174000"/);
  assert.match(output, /FANEVA_ENVIRONMENT = "staging"/);
  assert.doesNotMatch(output, /faneva-v2-staging-db/);
});

test("refuses unexpected Wrangler D1 binding layouts", () => {
  assert.throws(() => makeTemporaryWranglerConfig('name = "worker"\n', valid.databaseName,
    valid.databaseId, "12345678-1234-4234-8234-123456789abc"), /Unexpected wrangler.toml D1 binding layout/);
});
