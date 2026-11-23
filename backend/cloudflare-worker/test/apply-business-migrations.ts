import { env } from "cloudflare:workers";
import { applyD1Migrations } from "cloudflare:test";

type TestBindings = {
  DB: D1Database;
  TEST_MIGRATIONS: Parameters<typeof applyD1Migrations>[1];
};

const bindings = env as unknown as TestBindings;
await applyD1Migrations(bindings.DB, bindings.TEST_MIGRATIONS);