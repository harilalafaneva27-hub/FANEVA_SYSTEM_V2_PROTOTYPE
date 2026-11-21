/**
 * D1-compatible cleanup adapter.
 *
 * SQL identifiers are selected only from this closed allowlist. All fixture
 * values are bound parameters. No raw SQL, table names, or column names may be
 * supplied by a caller.
 */

const CLEANUP_ALLOWLIST = Object.freeze({
  sync_events: Object.freeze(["entity_id", "event_id"]),
  sync_entity_versions: Object.freeze(["entity_id"]),
  sync_mutations: Object.freeze(["mutation_id", "principal_id", "device_id", "idempotency_key"]),
  business_stock_movements: Object.freeze(["movement_id", "reference_id", "product_id", "actor_principal_id"]),
  business_customer_ledger: Object.freeze(["ledger_entry_id", "sale_id", "payment_id", "customer_id", "actor_principal_id"]),
  business_debt_payments: Object.freeze(["payment_id", "customer_id", "actor_principal_id"]),
  business_sale_lines: Object.freeze(["sale_line_id", "sale_id", "product_id"]),
  business_sales: Object.freeze(["sale_id", "customer_id", "actor_principal_id"]),
  business_transfers: Object.freeze(["transfer_id", "product_id", "actor_principal_id"]),
  business_stock: Object.freeze(["product_id"]),
  business_customers: Object.freeze(["customer_id"]),
  business_products: Object.freeze(["product_id"]),
  auth_store_grants: Object.freeze(["principal_id", "granted_by_principal_id"]),
  auth_sessions: Object.freeze(["session_id", "principal_id", "device_id"]),
  request_nonces: Object.freeze(["nonce", "device_id"]),
  auth_login_throttles: Object.freeze(["throttle_key_sha256"]),
  auth_principals: Object.freeze(["principal_id"]),
  owner_bootstrap: Object.freeze(["bootstrap_id"]),
  device_registry: Object.freeze(["device_id"]),
});

function assertAllowedPredicate(step) {
  if (!step || typeof step.table !== "string" || typeof step.column !== "string") {
    throw new TypeError("cleanup predicate must contain a table and column");
  }
  const columns = CLEANUP_ALLOWLIST[step.table];
  if (!columns || !columns.includes(step.column)) {
    const error = new Error("CLEANUP_IDENTIFIER_NOT_ALLOWLISTED");
    error.code = "CLEANUP_IDENTIFIER_NOT_ALLOWLISTED";
    throw error;
  }
  if (!Array.isArray(step.values) || step.values.length === 0
      || step.values.some(value => typeof value !== "string" || value.length === 0)) {
    throw new TypeError("cleanup values must be a non-empty array of non-empty strings");
  }
}

function buildPredicateSql(step, operation) {
  assertAllowedPredicate(step);
  const placeholders = step.values.map(() => "?").join(", ");
  if (operation === "delete") {
    return `DELETE FROM "${step.table}" WHERE "${step.column}" IN (${placeholders})`;
  }
  return `SELECT COUNT(*) AS count FROM "${step.table}" WHERE "${step.column}" IN (${placeholders})`;
}

/**
 * @param {{prepare(sql: string): {bind(...values: string[]): {run(): Promise<unknown>, first(): Promise<{count: number}|null>}}}} db Cloudflare D1 binding
 */
export function createD1CleanupAdapter(db) {
  if (!db || typeof db.prepare !== "function") {
    throw new TypeError("db must be a Cloudflare D1-compatible binding");
  }
  return Object.freeze({
    async deleteRows(step) {
      const sql = buildPredicateSql(step, "delete");
      await db.prepare(sql).bind(...step.values).run();
    },
    async countRows(step) {
      const sql = buildPredicateSql(step, "count");
      const row = await db.prepare(sql).bind(...step.values).first();
      const count = Number(row?.count);
      if (!Number.isSafeInteger(count) || count < 0) {
        throw new Error("D1 cleanup COUNT returned an invalid count");
      }
      return count;
    },
  });
}

export { CLEANUP_ALLOWLIST };
