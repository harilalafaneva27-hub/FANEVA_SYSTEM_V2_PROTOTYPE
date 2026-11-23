import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";

const source = await readFile(new URL("./audit-disposable-d1-orphan-readonly.mjs", import.meta.url), "utf8");

test("pins the exact approved Cloudflare account and Disposable D1", () => {
  assert.match(source, /APPROVED_ACCOUNT_ID = "b348dbb1c44604771fe12727de99b560"/);
  assert.match(source, /APPROVED_DATABASE_NAME = "faneva-v2-disposable-20261121"/);
  assert.match(source, /APPROVED_DATABASE_ID = "6463c2a0-62bd-4ca7-834c-e5fedd8e5d98"/);
  assert.match(source, /assert\.equal\(ACCOUNT_ID, APPROVED_ACCOUNT_ID/);
});

test("checks account and database identity before issuing D1 SQL", () => {
  const accountCheck = source.indexOf("const account = await request(");
  const databaseCheck = source.indexOf("const db = await request(");
  const firstSql = source.indexOf("const schema = await select(");
  assert.ok(accountCheck >= 0 && databaseCheck > accountCheck && firstSql > databaseCheck);
});

test("contains a strict SELECT-only SQL gate and rejects multi-statements", () => {
  assert.match(source, /assert\.match\(normalized, \/\\^SELECT\\b\/i/);
  assert.match(source, /normalized\.includes\(";")/);
  assert.match(source, /INSERT\|UPDATE\|DELETE\|REPLACE\|DROP\|ALTER\|CREATE/);
  assert.match(source, /Mutating or stateful SQL is forbidden/);
});

test("sanitizes actual CR, LF and tab characters in Cloudflare error messages", () => {
  assert.match(source, /replace\(\/\[\\r\\n\\t\]\+\/g, " "\)/);
  const sanitize = value => String(value ?? "unknown").replace(/[\r\n\t]+/g, " ").slice(0, 300);
  assert.equal(sanitize("first\r\nsecond\tthird"), "first second third");
  assert.equal(sanitize(null), "unknown");
});

test("audits schema objects, migration history, all user-table counts and known fixture IDs", () => {
  assert.match(source, /FROM sqlite_master/);
  assert.match(source, /SELECT \* FROM d1_migrations/);
  assert.match(source, /AUDIT_TABLE_COUNTS=/);
  assert.match(source, /AUDIT_TARGET_FIXTURE_COUNTS=/);
});

test("does not invoke migrations, cleanup, Wrangler deploy, or mutation SQL", () => {
  assert.doesNotMatch(source, /wrangler\s+deploy|wrangler\s+d1\s+migrations\s+apply/i);
  assert.doesNotMatch(source, /\b(INSERT\s+INTO|UPDATE\s+\w+\s+SET|DELETE\s+FROM|DROP\s+TABLE|CREATE\s+TABLE)\b/i);
});
