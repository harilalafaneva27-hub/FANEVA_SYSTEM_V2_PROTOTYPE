from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SYNC = (ROOT / "src" / "sync.ts").read_text(encoding="utf-8")


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


def test_vendor_bootstrap_does_not_return_global_customer_directory():
    assert 'session.role==="VENDEUR" ? {results:[]}' in SYNC
    assert "business_customer_balances" in SYNC


if __name__ == "__main__":
    tests = [
        test_pull_pins_one_high_watermark_across_pages,
        test_cursor_ahead_is_distinguished_for_bootstrap_recovery,
        test_vendor_pull_excludes_customer_financial_and_other_sellers_events,
        test_vendor_bootstrap_does_not_return_global_customer_directory,
    ]
    for test in tests:
        test()
        print(f"PASS {test.__name__}")
    print(f"PASS {len(tests)} sync pull contract tests")
