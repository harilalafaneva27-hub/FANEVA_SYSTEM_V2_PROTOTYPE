import copy
import json
import unittest
from pathlib import Path

from validate_seed import SeedValidationError, validate_seed

FIXTURE = Path(__file__).with_name("staging-seed-v1.json")


class SyntheticSeedValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.valid_seed = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_valid_fixture_and_checksum(self):
        report = validate_seed(copy.deepcopy(self.valid_seed))
        self.assertTrue(report["valid"])
        self.assertEqual(report["counts"]["stores"], 5)
        self.assertEqual(report["counts"]["products"], 12)
        self.assertEqual(len(report["sha256"]), 64)

    def assert_invalid(self, mutate):
        candidate = copy.deepcopy(self.valid_seed)
        mutate(candidate)
        with self.assertRaises(SeedValidationError):
            validate_seed(candidate)

    def test_duplicate_product_id_rejected(self):
        self.assert_invalid(lambda d: d["products"].append(copy.deepcopy(d["products"][0])))

    def test_unknown_store_rejected(self):
        self.assert_invalid(lambda d: d["openingStock"][0].update(storeId="A9"))

    def test_fractional_mga_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].update(totalMGA=30000.5))

    def test_negative_mga_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].update(paidMGA=-1))

    def test_overflow_mga_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].update(totalMGA=9_007_199_254_740_992))

    def test_dangling_product_reference_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0]["lines"][0].update(productId="missing-product"))

    def test_anonymous_cash_sale_accepted(self):
        candidate = copy.deepcopy(self.valid_seed)
        candidate["sales"][0]["saleType"] = "CASH"
        candidate["sales"][0]["customerId"] = None
        self.assertTrue(validate_seed(candidate)["valid"])

    def test_credit_sale_without_customer_rejected(self):
        self.assert_invalid(lambda d: d["sales"][1].update(saleType="CREDIT", customerId=None))

    def test_cash_sale_with_unknown_customer_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].update(saleType="CASH", customerId="missing-customer"))

    def test_sale_without_explicit_type_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].pop("saleType", None))

    def test_negative_final_stock_rejected(self):
        def mutate(d):
            d["sales"][0]["lines"][0]["quantity"] = 31
            d["sales"][0]["totalMGA"] = 465000
            d["sales"][0]["paidMGA"] = 465000
            d["stockMovements"][2]["quantityDelta"] = -31
        self.assert_invalid(mutate)

    def test_seller_cross_store_sale_rejected(self):
        self.assert_invalid(lambda d: d["sales"][0].update(storeId="A2"))

    def test_stock_movement_mismatch_rejected(self):
        self.assert_invalid(lambda d: d["stockMovements"][0].update(quantityDelta=999))

    def test_duplicate_opening_movement_rejected(self):
        self.assert_invalid(lambda d: d["stockMovements"].append(copy.deepcopy(d["stockMovements"][0])))

    def test_opening_movement_must_match_opening_stock(self):
        self.assert_invalid(lambda d: d["stockMovements"][0].update(quantityDelta=19))

    def test_duplicate_opening_stock_position_rejected(self):
        self.assert_invalid(lambda d: d["openingStock"].append(copy.deepcopy(d["openingStock"][0])))

    def test_debt_mismatch_rejected(self):
        self.assert_invalid(lambda d: d["debts"][0].update(balanceMGA=14999))

    def test_secret_field_rejected(self):
        self.assert_invalid(lambda d: d.update(apiToken="not-a-real-token"))

    def test_nested_bearer_token_rejected(self):
        self.assert_invalid(lambda d: d["actors"][0].update(auth={"bearerToken": "fake"}))

    def test_admin_scope_parity_rejected(self):
        self.assert_invalid(lambda d: d["actors"][1].update(storeScope=["A1"]))


if __name__ == "__main__":
    unittest.main()
