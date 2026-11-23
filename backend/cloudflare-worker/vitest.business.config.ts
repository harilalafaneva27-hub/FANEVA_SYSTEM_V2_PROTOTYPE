import { cloudflareTest, readD1Migrations } from "@cloudflare/vitest-plugin";
import { defineConfig } from "vitest/config";

export default defineConfig({
  plugins: [
    cloudflareTest(async () => {
      const migrations = await readD1Migrations("./migrations");
      return {
        main: "./src/index.ts",
        miniflare: {
          compatibilityDate: "2026-11-18",
          d1Databases: { DB: "faneva-v2-business-handler-tests" },
          bindings: {
            FANEVA_ENVIRONMENT: "staging",
            TEST_MIGRATIONS: migrations
          }
        }
      };
    })
  ],
  test: {
    include: [
      "test/business_handlers.d1.integration.test.ts",
      "test/auth.crypto.runtime.test.ts"
    ],
    setupFiles: ["./test/apply-business-migrations.ts"],
    testTimeout: 30000
  }
});
