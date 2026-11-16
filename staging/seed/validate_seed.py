#!/usr/bin/env python3
"""Offline validator for the schema-neutral FANEVA V2 synthetic STAGING fixture.

Uses only Python's standard library. It validates fixture integrity; it does not
connect to Cloudflare, mutate D1, enroll devices, or validate production schemas.
"""
from __future__ import annotations

import hashlib
import json
import sys
from pathlib import Path

MAX_MGA = 9_007_199_254_740_991
SECRET_KEYS = {"password", "passwordhash", "token", "accesstoken", "refreshtoken",
               "secret", "clientsecret", "privatekey", "activationproof", "apikey",
               "apitoken", "bearertoken", "authorization", "credential"}


class SeedValidationError(ValueError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise SeedValidationError(message)


def _unique(rows, label):
    ids = [row.get("id") for row in rows]
    _require(all(isinstance(value, str) and value for value in ids),
             f"{label}: every record must have a non-empty string id")
    _require(len(ids) == len(set(ids)), f"{label}: duplicate id")


def _money(value, label):
    _require(type(value) is int and 0 <= value <= MAX_MGA,
             f"{label}: must be a whole non-negative MGA integer within range")


def _scan_secrets(value, path="$"):
    if isinstance(value, dict):
        for key, child in value.items():
            normalized = "".join(ch for ch in key.lower() if ch.isalnum())
            _require(normalized not in SECRET_KEYS, f"secret-like field prohibited: {path}.{key}")
            _scan_secrets(child, f"{path}.{key}")
    elif isinstance(value, list):
        for index, child in enumerate(value):
            _scan_secrets(child, f"{path}[{index}]")


def _reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON object key: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value):
    raise SeedValidationError(f"non-finite JSON number prohibited: {value}")


def load_seed_json(raw_text):
    """Parse strict JSON: reject duplicate keys and NaN/Infinity at every depth."""
    try:
        return json.loads(raw_text, object_pairs_hook=_reject_duplicate_keys,
                          parse_constant=_reject_nonfinite)
    except json.JSONDecodeError as exc:
        raise SeedValidationError(f"invalid JSON: {exc}") from exc


def validate_seed(data):
    _require(isinstance(data, dict), "seed root must be a JSON object")
    _require(data.get("environment") == "STAGING", "environment must be STAGING")
    _require(data.get("seedVersion") == 1, "unsupported seedVersion")
    _require(data.get("seedId") == "faneva-v2-staging-synthetic-v1", "unexpected seedId")
    _require(data.get("currency") == "MGA", "currency must be MGA")
    _require(data.get("provenance", "").startswith("Synthetic fixture"), "synthetic provenance missing")
    _scan_secrets(data)

    required_arrays = ("stores", "products", "customers", "suppliers", "actors",
                       "openingStock", "purchases", "sales", "debts",
                       "stockMovements", "auditEvents")
    for key in required_arrays:
        _require(isinstance(data.get(key), list), f"{key} must be an array")

    stores = data["stores"]
    products = data["products"]
    customers = data["customers"]
    suppliers = data["suppliers"]
    actors = data["actors"]
    for key, rows in (("stores", stores), ("products", products), ("customers", customers),
                      ("suppliers", suppliers), ("actors", actors),
                      ("openingStock", data.get("openingStock", [])),
                      ("purchases", data.get("purchases", [])), ("sales", data.get("sales", [])),
                      ("debts", data.get("debts", [])),
                      ("stockMovements", data.get("stockMovements", [])),
                      ("auditEvents", data.get("auditEvents", []))):
        _unique(rows, key)

    _require([row["id"] for row in stores] == ["A1", "A2", "A3", "A4", "A5"],
             "stores must be exactly A1-A5 in declared order")
    store_ids = {row["id"] for row in stores}
    product_ids = {row["id"] for row in products}
    customer_ids = {row["id"] for row in customers}
    supplier_ids = {row["id"] for row in suppliers}
    actor_by_id = {row["id"]: row for row in actors}
    sale_by_id = {row["id"]: row for row in data.get("sales", [])}

    _require(sum(a.get("role") == "ADMIN" for a in actors) == 2, "exactly two ADMIN fixtures required")
    admins = [a for a in actors if a.get("role") == "ADMIN"]
    _require(admins[0].get("storeScope") == admins[1].get("storeScope"),
             "ADMIN fixtures must have equal store scopes")
    for actor in actors:
        _require(actor.get("role") in {"ADMIN", "GERANT", "VENDEUR"}, "unknown actor role")
        scope = actor.get("storeScope")
        _require(isinstance(scope, list) and scope, f"{actor['id']}: explicit non-empty storeScope required")
        _require(set(scope) <= store_ids, f"{actor['id']}: unknown store in scope")
        if actor["role"] == "VENDEUR":
            _require(len(scope) == 1, f"{actor['id']}: VENDEUR must have one assigned store")

    for row in data.get("openingStock", []):
        _require(row.get("storeId") in store_ids, f"{row['id']}: unknown store")
        _require(row.get("productId") in product_ids, f"{row['id']}: unknown product")
        _require(type(row.get("quantity")) is int and row["quantity"] >= 0,
                 f"{row['id']}: invalid stock quantity")

    for purchase in data.get("purchases", []):
        _require(purchase.get("storeId") in store_ids, f"{purchase['id']}: unknown store")
        _require(purchase.get("supplierId") in supplier_ids, f"{purchase['id']}: unknown supplier")
        total = 0
        for line in purchase.get("lines", []):
            _require(line.get("productId") in product_ids, f"{purchase['id']}: unknown product")
            _require(type(line.get("quantity")) is int and line["quantity"] > 0, "purchase quantity must be positive integer")
            _money(line.get("unitCostMGA"), "purchase unitCostMGA")
            total += line["quantity"] * line["unitCostMGA"]
        _money(purchase.get("totalMGA"), "purchase totalMGA")
        _require(total == purchase["totalMGA"], f"{purchase['id']}: line total mismatch")

    for sale in data.get("sales", []):
        _require(sale.get("storeId") in store_ids, f"{sale['id']}: unknown store")
        sale_type = sale.get("saleType")
        _require(sale_type in {"CASH", "CREDIT"}, f"{sale['id']}: saleType must be CASH or CREDIT")
        customer_id = sale.get("customerId")
        if sale_type == "CASH":
            _require(customer_id is None or customer_id in customer_ids,
                     f"{sale['id']}: CASH customerId must be null or reference a known customer")
        else:
            _require(customer_id in customer_ids,
                     f"{sale['id']}: CREDIT sale requires a known customer")
        actor = actor_by_id.get(sale.get("actorId"))
        _require(actor is not None, f"{sale['id']}: unknown actor")
        _require(sale["storeId"] in actor["storeScope"], f"{sale['id']}: actor outside assigned store")
        total = 0
        for line in sale.get("lines", []):
            _require(line.get("productId") in product_ids, f"{sale['id']}: unknown product")
            _require(type(line.get("quantity")) is int and line["quantity"] > 0, "sale quantity must be positive integer")
            _money(line.get("unitPriceMGA"), "sale unitPriceMGA")
            total += line["quantity"] * line["unitPriceMGA"]
        _money(sale.get("totalMGA"), "sale totalMGA")
        _money(sale.get("paidMGA"), "sale paidMGA")
        _require(total == sale["totalMGA"], f"{sale['id']}: line total mismatch")
        _require(sale["paidMGA"] <= sale["totalMGA"], f"{sale['id']}: paid exceeds total")

    debts_by_sale = {}
    for debt in data.get("debts", []):
        sale_id = debt.get("saleId")
        _require(sale_id not in debts_by_sale, f"duplicate debt record for sale: {sale_id}")
        debts_by_sale[sale_id] = debt
        sale = sale_by_id.get(sale_id)
        _require(sale is not None, f"{debt['id']}: unknown source sale")
        _require(sale.get("saleType") == "CREDIT",
                 f"{debt['id']}: debt may reference CREDIT sales only")
        _require(debt.get("storeId") == sale["storeId"], f"{debt['id']}: store differs from source sale")
        _require(debt.get("customerId") == sale["customerId"], f"{debt['id']}: customer differs from source sale")
        for field in ("originalMGA", "paidMGA", "balanceMGA"):
            _money(debt.get(field), f"{debt['id']}.{field}")
        _require(debt["originalMGA"] == sale["totalMGA"] - sale["paidMGA"],
                 f"{debt['id']}: original debt does not reconcile to sale")
        _require(debt["paidMGA"] <= debt["originalMGA"],
                 f"{debt['id']}: paid exceeds original debt")
        _require(debt["balanceMGA"] == debt["originalMGA"] - debt["paidMGA"],
                 f"{debt['id']}: debt balance does not reconcile")

    for sale in data.get("sales", []):
        outstanding = sale["totalMGA"] - sale["paidMGA"]
        debt = debts_by_sale.get(sale["id"])
        if sale["saleType"] == "CASH":
            _require(debt is None, f"{sale['id']}: CASH sale cannot own a debt record")
        elif outstanding > 0:
            _require(debt is not None,
                     f"{sale['id']}: CREDIT sale with outstanding balance requires exactly one debt record")
            _require(debt["originalMGA"] == outstanding,
                     f"{sale['id']}: debt original amount must equal sale outstanding balance")

    for movement in data.get("stockMovements", []):
        _require(movement.get("storeId") in store_ids, f"{movement['id']}: unknown store")
        _require(movement.get("productId") in product_ids, f"{movement['id']}: unknown product")
        _require(type(movement.get("quantityDelta")) is int, f"{movement['id']}: quantityDelta must be integer")
    # Option A: OPENING movements are the sole canonical opening-stock events.
    # openingStock is a fixture assertion/manifest section, never a second ledger.
    opening_expected = {}
    for stock in data.get("openingStock", []):
        key = (stock["storeId"], stock["productId"])
        _require(key not in opening_expected, f"duplicate openingStock position: {key}")
        opening_expected[key] = stock["quantity"]
    opening_events = {}
    for movement in data.get("stockMovements", []):
        if movement.get("kind") == "OPENING":
            key = (movement["storeId"], movement["productId"])
            _require(key not in opening_events, f"duplicate OPENING movement: {key}")
            _require(movement["quantityDelta"] >= 0, f"{movement['id']}: OPENING quantity cannot be negative")
            opening_events[key] = movement["quantityDelta"]
    _require(opening_events == opening_expected,
             "OPENING movements must exactly match openingStock assertions one-to-one")

    # Reconcile every seeded stock position against its complete movement ledger.
    movement_totals = {}
    for movement in data.get("stockMovements", []):
        key = (movement["storeId"], movement["productId"])
        movement_totals[key] = movement_totals.get(key, 0) + movement["quantityDelta"]
    expected_totals = {}
    for stock in data.get("openingStock", []):
        key = (stock["storeId"], stock["productId"])
        expected_totals[key] = expected_totals.get(key, 0) + stock["quantity"]
    for purchase in data.get("purchases", []):
        for line in purchase.get("lines", []):
            key = (purchase["storeId"], line["productId"])
            expected_totals[key] = expected_totals.get(key, 0) + line["quantity"]
    for sale in data.get("sales", []):
        for line in sale.get("lines", []):
            key = (sale["storeId"], line["productId"])
            expected_totals[key] = expected_totals.get(key, 0) - line["quantity"]
    _require(movement_totals == expected_totals,
             "stock movement ledger does not reconcile with opening stock, purchases, and sales")
    for (store_id, product_id), final_quantity in movement_totals.items():
        _require(final_quantity >= 0,
                 f"negative final stock for store={store_id}, product={product_id}: {final_quantity}")

    for event in data.get("auditEvents", []):
        _require(event.get("actorId") in actor_by_id, f"{event['id']}: unknown audit actor")
        _require(isinstance(event.get("occurredAtUtc"), str) and event["occurredAtUtc"].endswith("Z"),
                 f"{event['id']}: timestamp must be UTC ISO-8601 ending in Z")

    canonical = json.dumps(data, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
    checksum = hashlib.sha256(canonical).hexdigest()
    counts = {key: len(data.get(key, [])) for key in (
        "stores", "products", "customers", "suppliers", "actors", "openingStock",
        "purchases", "sales", "debts", "stockMovements", "auditEvents")}
    return {"valid": True, "seedId": data["seedId"], "sha256": checksum, "counts": counts}


def main():
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).with_name("staging-seed-v1.json")
    try:
        report = validate_seed(load_seed_json(path.read_text(encoding="utf-8")))
    except (OSError, json.JSONDecodeError, SeedValidationError) as exc:
        print(json.dumps({"valid": False, "error": str(exc)}, indent=2))
        return 1
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
