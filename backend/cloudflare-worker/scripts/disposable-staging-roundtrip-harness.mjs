/**
 * Offline safety primitives for the future disposable-STAGING live round-trip.
 *
 * This module deliberately has no Cloudflare SDK, Wrangler, fetch, or D1 client.
 * It can only validate a target and drive a caller-supplied adapter; importing it
 * or running its unit tests cannot contact or mutate Cloudflare.
 */

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const DISPOSABLE_DB_RE = /^faneva-v2-disposable-[a-z0-9-]{8,48}$/;

export function assertDisposableStagingTarget(target) {
  const problems = [];
  if (target?.environment !== "staging") problems.push("environment must be exactly staging");
  if (!DISPOSABLE_DB_RE.test(target?.databaseName ?? "")) {
    problems.push("databaseName must use the faneva-v2-disposable- prefix");
  }
  if (!UUID_RE.test(target?.databaseId ?? "")) problems.push("databaseId must be a UUID");
  if (target?.confirmDatabaseName !== target?.databaseName) {
    problems.push("confirmDatabaseName must exactly match databaseName");
  }
  if (target?.confirmDatabaseId !== target?.databaseId) {
    problems.push("confirmDatabaseId must exactly match databaseId");
  }
  if (target?.allowLiveWrites !== "YES") problems.push("allowLiveWrites must be exactly YES");
  if (problems.length) {
    const error = new Error("DISPOSABLE_STAGING_TARGET_REJECTED: " + problems.join("; "));
    error.code = "DISPOSABLE_STAGING_TARGET_REJECTED";
    error.problems = problems;
    throw error;
  }
  return Object.freeze({
    environment: "staging",
    databaseName: target.databaseName,
    databaseId: target.databaseId,
  });
}

export function createFixtureManifest(runId) {
  if (!UUID_RE.test(runId ?? "")) throw new TypeError("runId must be a UUID");
  const compact = runId.replaceAll("-", "").toLowerCase();
  const id = (prefix, suffix) => `${prefix}_${compact}_${suffix}`;
  return Object.freeze({
    runId,
    principalId: id("usr", "admin"),
    deviceId: id("dev", "device"),
    sessionId: id("ses", "session"),
    customerId: id("cus", "customer"),
    productId: id("prd", "product"),
    saleId: id("sal", "sale"),
    transferId: id("trf", "transfer"),
    paymentId: id("pay", "payment"),
    mutationIds: Object.freeze(["sale", "transfer", "payment"].map(x => id("mut", x))),
    saleLineIds: Object.freeze([id("sln", "sale-line")]),
    ledgerEntryIds: Object.freeze([id("led", "sale-ledger"), id("led", "payment-ledger")]),
    movementIds: Object.freeze([
      id("mov", "sale-stock"), id("mov", "transfer-out"), id("mov", "transfer-in"),
    ]),
    syncEventIds: Object.freeze([
      id("evt", "sale"), id("evt", "transfer-out"), id("evt", "transfer-in"), id("evt", "payment"),
    ]),
  });
}

// Delete specifications are intentionally child-first. Each value is a
// generated fixture ID; adapters must use bound parameters, never SQL string
// interpolation. Broad table wipes and wildcard/prefix deletes are forbidden.
export function buildCleanupPlan(f) {
  const businessIds = [f.customerId, f.productId, f.saleId, f.transferId, f.paymentId];
  return Object.freeze([
    { table: "sync_events", column: "entity_id", values: businessIds },
    { table: "sync_events", column: "event_id", values: f.syncEventIds },
    { table: "sync_entity_versions", column: "entity_id", values: businessIds },
    { table: "sync_mutations", column: "mutation_id", values: f.mutationIds },
    { table: "sync_mutations", column: "principal_id", values: [f.principalId] },
    { table: "sync_mutations", column: "device_id", values: [f.deviceId] },
    { table: "business_stock_movements", column: "movement_id", values: f.movementIds },
    { table: "business_stock_movements", column: "reference_id", values: [f.saleId, f.transferId] },
    { table: "business_stock_movements", column: "product_id", values: [f.productId] },
    { table: "business_customer_ledger", column: "ledger_entry_id", values: f.ledgerEntryIds },
    { table: "business_customer_ledger", column: "sale_id", values: [f.saleId] },
    { table: "business_customer_ledger", column: "payment_id", values: [f.paymentId] },
    { table: "business_customer_ledger", column: "customer_id", values: [f.customerId] },
    { table: "business_debt_payments", column: "payment_id", values: [f.paymentId] },
    { table: "business_debt_payments", column: "customer_id", values: [f.customerId] },
    { table: "business_sale_lines", column: "sale_line_id", values: f.saleLineIds },
    { table: "business_sale_lines", column: "sale_id", values: [f.saleId] },
    { table: "business_sale_lines", column: "product_id", values: [f.productId] },
    { table: "business_sales", column: "sale_id", values: [f.saleId] },
    { table: "business_sales", column: "customer_id", values: [f.customerId] },
    { table: "business_sales", column: "actor_principal_id", values: [f.principalId] },
    { table: "business_transfers", column: "transfer_id", values: [f.transferId] },
    { table: "business_transfers", column: "product_id", values: [f.productId] },
    { table: "business_transfers", column: "actor_principal_id", values: [f.principalId] },
    { table: "business_stock", column: "product_id", values: [f.productId] },
    { table: "business_customers", column: "customer_id", values: [f.customerId] },
    { table: "business_products", column: "product_id", values: [f.productId] },
    { table: "auth_store_grants", column: "principal_id", values: [f.principalId] },
    { table: "auth_store_grants", column: "granted_by_principal_id", values: [f.principalId] },
    { table: "auth_sessions", column: "session_id", values: [f.sessionId] },
    { table: "auth_sessions", column: "principal_id", values: [f.principalId] },
    { table: "auth_sessions", column: "device_id", values: [f.deviceId] },
    { table: "request_nonces", column: "device_id", values: [f.deviceId] },
    { table: "auth_principals", column: "principal_id", values: [f.principalId] },
    { table: "device_registry", column: "device_id", values: [f.deviceId] },
  ].map(step => Object.freeze({ ...step, values: Object.freeze([...step.values]) })));
}

export async function cleanupAndVerify(adapter, manifest) {
  if (!adapter || typeof adapter.deleteRows !== "function" || typeof adapter.countRows !== "function") {
    throw new TypeError("adapter must implement deleteRows and countRows");
  }
  const plan = buildCleanupPlan(manifest);
  const failures = [];

  for (const step of plan) {
    try {
      await adapter.deleteRows(step);
    } catch (error) {
      failures.push({ phase: "delete", step, error });
    }
  }

  const residuals = [];
  for (const step of plan) {
    try {
      const count = await adapter.countRows(step);
      if (!Number.isSafeInteger(count) || count < 0) {
        residuals.push({ step, count, reason: "invalid-count" });
      } else if (count !== 0) {
        residuals.push({ step, count, reason: "rows-remain" });
      }
    } catch (error) {
      residuals.push({ step, reason: "read-back-failed", error });
    }
  }

  if (failures.length || residuals.length) {
    const error = new Error(
      `FIXTURE_CLEANUP_UNVERIFIED: deleteFailures=${failures.length}, residualChecks=${residuals.length}`,
    );
    error.code = "FIXTURE_CLEANUP_UNVERIFIED";
    error.failures = failures;
    error.residuals = residuals;
    throw error;
  }
  return Object.freeze({ status: "CLEANUP_VERIFIED", deleteSteps: plan.length, residualRows: 0 });
}

export async function runWithVerifiedCleanup(adapter, manifest, operation) {
  let result;
  let operationError;
  try {
    result = await operation();
  } catch (error) {
    operationError = error;
  }

  let cleanupError;
  try {
    await cleanupAndVerify(adapter, manifest);
  } catch (error) {
    cleanupError = error;
  }

  if (operationError && cleanupError) {
    throw new AggregateError([operationError, cleanupError], "Round-trip failed and fixture cleanup was not verified");
  }
  if (cleanupError) throw cleanupError;
  if (operationError) throw operationError;
  return result;
}
