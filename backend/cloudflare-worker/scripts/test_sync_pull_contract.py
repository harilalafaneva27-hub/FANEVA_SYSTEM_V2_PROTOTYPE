from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SYNC = (ROOT / "src" / "sync.ts").read_text(encoding="utf-8")
SALE = (ROOT / "src" / "business_transactions.ts").read_text(encoding="utf-8")
TRANSFER_DEBT = (ROOT / "src" / "business_transfer_debt.ts").read_text(encoding="utf-8")


def test_pull_pins_one_high_watermark_across_pages():
    assert "throughCursor?:unknown" in SYNC
    assert "const through=input.throughCursor===undefined?currentHigh:input.throughCursor" in SYNC
    assert "highWatermark:Number(through)" in SYNC
    assert "nextCursor:next" in SYNC
    assert "hasMore:rows.length===Number(limit)" in SYNC


def test_cursor_ahead_is_distinguished_for_bootstrap_recovery():
    ahead = SYNC.index('if (Number(after)>currentHigh) return json({error:"CURSOR_AHEAD_OF_SERVER"')
    watermark = SYNC.index('const through=input.throughCursor===undefined?currentHigh:input.throughCursor')
    assert ahead < watermark


def test_vendor_pull_excludes_customer_financial_and_other_sellers_events():
    assert "entity_type NOT IN ('CUSTOMER','DEBT_PAYMENT','TRANSFER')" in SYNC
    assert "json_extract(payload_json,'$.actorPrincipalId')=?" in SYNC
    assert 'delete payload.customerId' in SYNC
    assert 'delete payload.remainingMga' in SYNC
    assert 'delete payload.purchasePriceMga' in SYNC
    assert 'if (payload.saleType === "CREDIT") payload.customerRedacted = true' in SYNC
    assert 'delete payload.customerId' in SYNC
    assert 'delete payload.remainingMga' in SYNC


def test_vendor_bootstrap_does_not_return_global_customer_directory():
    assert 'role==="VENDEUR"' in SYNC
    assert "FROM business_customers WHERE 1=0" in SYNC
    assert "business_customer_balances" in SYNC
    assert "purchase_price_mga" not in SYNC.split('const productQuery = role==="VENDEUR"', 1)[1].split(":", 1)[0]


def test_bootstrap_reads_watermark_and_projection_in_one_batch():
    assert "export async function readBootstrapSnapshot" in SYNC
    assert "await env.DB.batch([" in SYNC
    assert 'SELECT COALESCE(MAX(cursor),0) AS cursor FROM sync_events' in SYNC
    assert "const snapshot = await readBootstrapSnapshot(env, session.role, stores)" in SYNC


def test_business_events_carry_outbox_and_idempotency_identity_separately():
    assert "mutationId: mutation.mutationId ?? mutation.idempotencyKey" in SALE
    assert "idempotencyKey: mutation.idempotencyKey" in SALE
    assert "mutationId:mutation.mutationId ?? mutation.idempotencyKey" in TRANSFER_DEBT
    assert TRANSFER_DEBT.count("    idempotencyKey:mutation.idempotencyKey,") == 2


if __name__ == "__main__":
    tests = [
        test_pull_pins_one_high_watermark_across_pages,
        test_cursor_ahead_is_distinguished_for_bootstrap_recovery,
        test_vendor_pull_excludes_customer_financial_and_other_sellers_events,
        test_vendor_bootstrap_does_not_return_global_customer_directory,
        test_bootstrap_reads_watermark_and_projection_in_one_batch,
        test_business_events_carry_outbox_and_idempotency_identity_separately,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)} sync pull contract tests")
