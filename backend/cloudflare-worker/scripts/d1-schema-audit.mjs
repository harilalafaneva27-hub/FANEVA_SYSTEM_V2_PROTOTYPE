import assert from "node:assert/strict";

export function normalizeSchemaRows(rows) {
  assert.ok(Array.isArray(rows), "Schema audit rows must be an array");
  return rows
    .filter(row =>
      typeof row?.name === "string" &&
      !row.name.startsWith("sqlite_") &&
      !row.name.startsWith("_cf_") &&
      row.name !== "d1_migrations")
    .map(row => ({
      type: row.type,
      name: row.name,
      tbl_name: row.tbl_name,
      sql: row.sql ?? null,
    }))
    .sort((a, b) =>
      a.type.localeCompare(b.type) ||
      a.name.localeCompare(b.name) ||
      String(a.tbl_name).localeCompare(String(b.tbl_name)));
}

export function assertSchemaDefinitionsMatch(actualRows, expectedRows) {
  const actual = normalizeSchemaRows(actualRows);
  const expected = normalizeSchemaRows(expectedRows);
  assert.deepEqual(
    actual,
    expected,
    "D1 full schema definitions differ from the schema produced by the seven reviewed migrations",
  );
  return Object.freeze({ schemaObjectCount: actual.length });
}
