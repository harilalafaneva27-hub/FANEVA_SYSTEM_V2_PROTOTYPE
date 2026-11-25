import { describe, expect, it } from "vitest";
import { assertSchemaDefinitionsMatch } from "../scripts/d1-schema-audit.mjs";

const expected = [
  {
    type: "table",
    name: "business_stock",
    tbl_name: "business_stock",
    sql: "CREATE TABLE business_stock (store_id TEXT NOT NULL, product_id TEXT NOT NULL, quantity INTEGER NOT NULL CHECK(quantity >= 0), PRIMARY KEY(store_id, product_id))",
  },
  {
    type: "index",
    name: "idx_business_stock_product",
    tbl_name: "business_stock",
    sql: "CREATE INDEX idx_business_stock_product ON business_stock(product_id)",
  },
];

describe("full D1 schema definition audit", () => {
  it("accepts identical table and index definitions", () => {
    expect(assertSchemaDefinitionsMatch(expected, expected).schemaObjectCount).toBe(2);
  });

  it("rejects a changed column while preserving every schema object name", () => {
    const altered = expected.map(row => ({ ...row }));
    altered[0].sql = altered[0].sql.replace("quantity INTEGER", "quantity TEXT");
    expect(altered.map(row => row.name)).toEqual(expected.map(row => row.name));
    expect(() => assertSchemaDefinitionsMatch(altered, expected)).toThrow(/full schema definitions differ/);
  });

  it("rejects a changed constraint while preserving every schema object name", () => {
    const altered = expected.map(row => ({ ...row }));
    altered[0].sql = altered[0].sql.replace("CHECK(quantity >= 0)", "CHECK(quantity > 0)");
    expect(altered.map(row => row.name)).toEqual(expected.map(row => row.name));
    expect(() => assertSchemaDefinitionsMatch(altered, expected)).toThrow(/full schema definitions differ/);
  });

  it("rejects a changed index definition with the same index name", () => {
    const altered = expected.map(row => ({ ...row }));
    altered[1].sql = altered[1].sql.replace("product_id)", "store_id)");
    expect(() => assertSchemaDefinitionsMatch(altered, expected)).toThrow(/full schema definitions differ/);
  });
});
