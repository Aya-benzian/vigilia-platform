"""
test_feature_validator.py — Comprehensive Unit Tests for M2 V2 Feature Validator.

Tests:
1. Canonical M1 feature specification synchronization (29 purchase, 20 transfer).
2. Happy path validation for purchase-v2 (full 29 features).
3. Happy path validation for transfer-v2 (full 20 features).
4. Feature spec version checking and mismatch handling (HTTP 409 FEATURE_SPEC_MISMATCH).
5. Unsupported model family rejection (HTTP 422 FEATURE_VALIDATION_ERROR).
6. Non-dictionary container rejection (HTTP 422).
7. Missing features rejection (HTTP 422).
8. Unexpected additional features rejection (HTTP 422).
9. Strict type enforcement (reject booleans for numeric features).
10. Rejection of string-to-number silent conversion.
11. Rejection of null (None) values.
12. Rejection of NaN and infinite float values.
13. String feature validation (empty, whitespace, non-string).
14. Float rejected for int fields.
15. Int accepted and cast to float for float fields.
16. Acceptance of valid cold-start values without modification.
17. Preservation of canonical key ordering regardless of input key order.
18. RFC 7807 ProblemDetail serialization for validator exceptions.
"""

import json
import math
from pathlib import Path
from typing import Any, Dict
import pytest

from schemas import (
    CANONICAL_FEATURE_SPECS,
    ERROR_CODE_FEATURE_SPEC_MISMATCH,
    ERROR_CODE_FEATURE_VALIDATION,
    BaseFeatureSpecValidator,
    FeatureSpecMismatchError,
    FeatureSpecValidator,
    FeatureValidationError,
)


@pytest.fixture
def validator() -> FeatureSpecValidator:
    """Fixture providing a fresh FeatureSpecValidator instance."""
    return FeatureSpecValidator()


@pytest.fixture
def valid_purchase_features() -> Dict[str, Any]:
    """Canonical 29 purchase-v2 features with realistic values."""
    return {
        "amount": 73.31,
        "log_amount": 4.3082,
        "hour_of_day": 13,
        "day_of_week": 0,
        "is_weekend": 0,
        "is_night": 0,
        "customer_age": 50.98,
        "distance_home_merchant_km": 95.04,
        "city_pop_log": 12.72,
        "merchant_category": "kids_pets",
        "customer_avg_amount": 75.98,
        "customer_std_amount": 116.87,
        "customer_max_amount": 1126.97,
        "amount_to_avg_ratio": 0.965,
        "amount_zscore_customer": -0.023,
        "hour_share": 0.041,
        "category_share": 0.088,
        "merchant_visit_count": 0,
        "avg_distance_home_km": 75.97,
        "distance_zscore": 0.621,
        "txn_count_1h": 0,
        "txn_count_24h": 4,
        "txn_count_7d": 19,
        "amount_sum_24h": 207.62,
        "time_since_last_txn_s": 4615.0,
        "is_new_merchant": 1,
        "is_new_category": 0,
        "history_length": 170,
        "days_since_first_txn": 68.93,
    }


@pytest.fixture
def valid_transfer_features() -> Dict[str, Any]:
    """Canonical 20 transfer-v2 features with realistic values."""
    return {
        "amount": 183621.15,
        "log_amount": 12.1206,
        "hour_of_day": 9,
        "day_of_week": 2,
        "sender_avg_amount": 798197.47,
        "sender_std_amount": 749689.62,
        "sender_max_amount": 3049437.48,
        "amount_to_avg_ratio": 0.23,
        "sender_amount_zscore": -0.82,
        "is_new_recipient": 1,
        "pair_transfer_count": 0,
        "recipient_share": 0.0,
        "days_since_last_pair_transfer": 30.0,
        "distinct_recipients_30d": 137,
        "recipient_inbound_count_24h": 0,
        "recipient_unique_senders_24h": 0,
        "sender_txn_count_1h": 2,
        "sender_txn_count_24h": 38,
        "amount_to_balance_ratio": 2.97,
        "history_length": 137,
    }


# =====================================================================
# 1. Canonical Specification Synchronization & Metadata
# =====================================================================

def test_canonical_specs_match_m1_artifact_if_present() -> None:
    """Verifies CANONICAL_FEATURE_SPECS aligns 100% with M1 feature_spec.json if present."""
    m1_spec_path = Path(__file__).resolve().parents[3] / "ml" / "training" / "artifacts" / "feature_spec.json"
    if not m1_spec_path.exists():
        pytest.skip(f"M1 feature_spec.json not found at {m1_spec_path}")

    with open(m1_spec_path, "r", encoding="utf-8") as f:
        m1_specs = json.load(f)

    for family in ["purchase", "transfer"]:
        assert family in CANONICAL_FEATURE_SPECS
        m1_fam = m1_specs[family]
        m2_fam = CANONICAL_FEATURE_SPECS[family]
        assert m2_fam["version"] == m1_fam["version"]
        assert m2_fam["feature_count"] == m1_fam["feature_count"]
        assert len(m2_fam["features"]) == len(m1_fam["features"])
        for i, (m2_feat, m1_feat) in enumerate(zip(m2_fam["features"], m1_fam["features"])):
            assert m2_feat["name"] == m1_feat["name"], f"Mismatch at index {i} for {family}"
            assert m2_feat["type"] == m1_feat["type"], f"Type mismatch at {m2_feat['name']}"
            assert m2_feat["cold_start"] == m1_feat["cold_start"]


def test_validator_metadata_methods(validator: FeatureSpecValidator) -> None:
    assert validator.get_canonical_version("purchase") == "purchase-v2"
    assert validator.get_canonical_version("transfer") == "transfer-v2"
    assert validator.get_feature_count("purchase") == 29
    assert validator.get_feature_count("transfer") == 20
    assert len(validator.get_expected_features("purchase")) == 29
    assert len(validator.get_expected_features("transfer")) == 20


def test_base_feature_spec_validator_alias(validator: FeatureSpecValidator) -> None:
    assert BaseFeatureSpecValidator is FeatureSpecValidator


# =====================================================================
# 2. Happy Path Validation & Ordering
# =====================================================================

def test_validate_purchase_success(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    result = validator.validate("purchase", valid_purchase_features, feature_spec_version="purchase-v2")
    assert len(result) == 29
    expected_order = [f["name"] for f in validator.get_expected_features("purchase")]
    assert list(result.keys()) == expected_order
    assert isinstance(result["amount"], float)
    assert isinstance(result["hour_of_day"], int)
    assert isinstance(result["merchant_category"], str)


def test_validate_transfer_success(
    validator: FeatureSpecValidator, valid_transfer_features: Dict[str, Any]
) -> None:
    result = validator.validate("transfer", valid_transfer_features, feature_spec_version="transfer-v2")
    assert len(result) == 20
    expected_order = [f["name"] for f in validator.get_expected_features("transfer")]
    assert list(result.keys()) == expected_order
    assert isinstance(result["amount"], float)
    assert isinstance(result["pair_transfer_count"], int)


def test_canonical_ordering_enforced_regardless_of_input_dict_order(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    """Verifies that reversed or scrambled input keys are reordered canonically."""
    scrambled = {k: valid_purchase_features[k] for k in reversed(list(valid_purchase_features.keys()))}
    assert list(scrambled.keys()) != [f["name"] for f in validator.get_expected_features("purchase")]

    result = validator.validate("purchase", scrambled)
    canonical_keys = [f["name"] for f in validator.get_expected_features("purchase")]
    assert list(result.keys()) == canonical_keys


# =====================================================================
# 3. Feature Spec Version Validation (HTTP 409)
# =====================================================================

def test_version_validation_matching(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    # Explicit matching version
    res = validator.validate("purchase", valid_purchase_features, feature_spec_version="purchase-v2")
    assert len(res) == 29

    # Default None version uses canonical
    res_none = validator.validate("purchase", valid_purchase_features, feature_spec_version=None)
    assert len(res_none) == 29


def test_version_mismatch_raises_409(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    with pytest.raises(FeatureSpecMismatchError) as exc:
        validator.validate("purchase", valid_purchase_features, feature_spec_version="purchase-v1")
    assert exc.value.status_code == 409
    assert exc.value.code == ERROR_CODE_FEATURE_SPEC_MISMATCH
    assert "purchase-v1" in exc.value.detail
    assert "purchase-v2" in exc.value.detail


def test_transfer_version_mismatch_raises_409(
    validator: FeatureSpecValidator, valid_transfer_features: Dict[str, Any]
) -> None:
    with pytest.raises(FeatureSpecMismatchError) as exc:
        validator.validate("transfer", valid_transfer_features, feature_spec_version="purchase-v2")
    assert exc.value.status_code == 409
    assert exc.value.code == ERROR_CODE_FEATURE_SPEC_MISMATCH


# =====================================================================
# 4. Family Validation (HTTP 422)
# =====================================================================

@pytest.mark.parametrize("invalid_family", ["crypto", "loan", "UNKNOWN", "", "PURCHASE"])
def test_unsupported_family_raises_422(
    validator: FeatureSpecValidator, invalid_family: str, valid_purchase_features: Dict[str, Any]
) -> None:
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate(invalid_family, valid_purchase_features)
    assert exc.value.status_code == 422
    assert exc.value.code == ERROR_CODE_FEATURE_VALIDATION
    assert "Unsupported model family" in exc.value.detail

    with pytest.raises(FeatureValidationError):
        validator.get_canonical_version(invalid_family)

    with pytest.raises(FeatureValidationError):
        validator.get_feature_count(invalid_family)

    with pytest.raises(FeatureValidationError):
        validator.get_expected_features(invalid_family)


# =====================================================================
# 5. Container & Structure Validation (HTTP 422)
# =====================================================================

@pytest.mark.parametrize("bad_payload", [None, "invalid string", [1, 2, 3], 42])
def test_non_dict_features_raises_422(validator: FeatureSpecValidator, bad_payload: Any) -> None:
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", bad_payload)
    assert exc.value.status_code == 422
    assert "must be a JSON object" in exc.value.detail


def test_missing_single_feature_raises_422(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    del valid_purchase_features["amount"]
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert exc.value.status_code == 422
    assert exc.value.errors is not None
    assert any(e.field == "features.amount" and "Missing required" in e.message for e in exc.value.errors)


def test_missing_multiple_features_reports_all(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    del valid_purchase_features["amount"]
    del valid_purchase_features["merchant_category"]
    del valid_purchase_features["hour_of_day"]
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert exc.value.status_code == 422
    fields = [e.field for e in exc.value.errors]
    assert "features.amount" in fields
    assert "features.merchant_category" in fields
    assert "features.hour_of_day" in fields


def test_empty_dict_reports_all_missing(validator: FeatureSpecValidator) -> None:
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", {})
    assert exc.value.status_code == 422
    assert len(exc.value.errors) == 29


def test_unexpected_feature_raises_422(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    valid_purchase_features["unexpected_extra"] = 123.45
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert exc.value.status_code == 422
    assert any(e.field == "features.unexpected_extra" for e in exc.value.errors)


def test_legacy_raw_fields_rejected_as_unexpected(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    """Verifies that legacy raw inputs (Contract 1 Sparkov) cannot be passed to V2 serving."""
    valid_purchase_features["trans_date_trans_time"] = "2020-06-21T02:37:00"
    valid_purchase_features["cc_num"] = 1234567890
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    fields = [e.field for e in exc.value.errors]
    assert "features.trans_date_trans_time" in fields
    assert "features.cc_num" in fields


# =====================================================================
# 6. Type Enforcement & Coercion Rules
# =====================================================================

def test_boolean_rejected_for_numeric_types(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    """In Python isinstance(True, int) is True; validator must strictly reject booleans."""
    # Boolean for float feature
    valid_purchase_features["amount"] = True
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("boolean" in e.message.lower() and e.field == "features.amount" for e in exc.value.errors)

    # Boolean for int feature
    valid_purchase_features["amount"] = 50.0
    valid_purchase_features["hour_of_day"] = False
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("boolean" in e.message.lower() and e.field == "features.hour_of_day" for e in exc.value.errors)

    # Boolean for binary int feature (is_weekend expects int 0 or 1, not bool)
    valid_purchase_features["hour_of_day"] = 12
    valid_purchase_features["is_weekend"] = True
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("boolean" in e.message.lower() and e.field == "features.is_weekend" for e in exc.value.errors)


def test_strings_not_silently_converted_to_numbers(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    """Validator must never perform string-to-number casting."""
    valid_purchase_features["amount"] = "73.31"
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("expects float/number" in e.message for e in exc.value.errors)

    valid_purchase_features["amount"] = 73.31
    valid_purchase_features["hour_of_day"] = "13"
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("expects integer" in e.message for e in exc.value.errors)


def test_null_value_rejected(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    valid_purchase_features["amount"] = None
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("cannot be null" in e.message for e in exc.value.errors)


def test_float_nan_and_inf_rejected(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    # NaN
    valid_purchase_features["amount"] = float("nan")
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("cannot be NaN or infinite" in e.message for e in exc.value.errors)

    # Positive Infinity
    valid_purchase_features["amount"] = float("inf")
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("cannot be NaN or infinite" in e.message for e in exc.value.errors)

    # Negative Infinity
    valid_purchase_features["amount"] = float("-inf")
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("cannot be NaN or infinite" in e.message for e in exc.value.errors)


def test_string_feature_validation(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    # Empty string
    valid_purchase_features["merchant_category"] = ""
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("string cannot be empty" in e.message for e in exc.value.errors)

    # Whitespace-only string
    valid_purchase_features["merchant_category"] = "   "
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("string cannot be empty" in e.message for e in exc.value.errors)

    # Integer for string field
    valid_purchase_features["merchant_category"] = 123
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("expects string" in e.message for e in exc.value.errors)


def test_float_rejected_for_integer_features(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    valid_purchase_features["hour_of_day"] = 13.5
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", valid_purchase_features)
    assert any("expects integer" in e.message for e in exc.value.errors)


def test_integer_accepted_and_cast_for_float_features(
    validator: FeatureSpecValidator, valid_purchase_features: Dict[str, Any]
) -> None:
    """In JSON, numbers like 100 or 0 deserialize as int; validator should cast to float."""
    valid_purchase_features["amount"] = 100
    valid_purchase_features["customer_age"] = 45
    result = validator.validate("purchase", valid_purchase_features)
    assert isinstance(result["amount"], float)
    assert result["amount"] == 100.0
    assert isinstance(result["customer_age"], float)
    assert result["customer_age"] == 45.0


# =====================================================================
# 7. Cold-Start Handling
# =====================================================================

def test_purchase_cold_start_vector_accepted_without_modification(
    validator: FeatureSpecValidator,
) -> None:
    """Backend sends cold-start values when customer history is absent. M2 must accept them."""
    cold_start_purchase = {
        feat["name"]: feat["cold_start"]
        for feat in validator.get_expected_features("purchase")
    }
    result = validator.validate("purchase", cold_start_purchase)
    assert len(result) == 29
    assert result["merchant_category"] == "unknown"
    assert result["is_new_merchant"] == 1
    assert result["is_new_category"] == 1
    assert result["time_since_last_txn_s"] == 2592000.0
    assert result["history_length"] == 0
    assert result["customer_avg_amount"] == 0.0


def test_transfer_cold_start_vector_accepted_without_modification(
    validator: FeatureSpecValidator,
) -> None:
    """Backend sends cold-start values for transfer. M2 must accept them without modification."""
    cold_start_transfer = {
        feat["name"]: feat["cold_start"]
        for feat in validator.get_expected_features("transfer")
    }
    result = validator.validate("transfer", cold_start_transfer)
    assert len(result) == 20
    assert result["is_new_recipient"] == 1
    assert result["days_since_last_pair_transfer"] == 30.0
    assert result["pair_transfer_count"] == 0
    assert result["history_length"] == 0
    assert result["sender_avg_amount"] == 0.0


# =====================================================================
# 8. RFC 7807 ProblemDetail Conversion
# =====================================================================

def test_feature_validation_error_to_problem_detail(
    validator: FeatureSpecValidator,
) -> None:
    with pytest.raises(FeatureValidationError) as exc:
        validator.validate("purchase", {"bad_feature": 1.0})

    err = exc.value
    problem = err.to_problem_detail(trace_id="test-trace-uuid")
    assert problem.status == 422
    assert problem.code == ERROR_CODE_FEATURE_VALIDATION
    assert problem.trace_id == "test-trace-uuid"
    assert problem.errors is not None
    assert len(problem.errors) > 0


def test_feature_spec_mismatch_error_to_problem_detail() -> None:
    err = FeatureSpecMismatchError(
        requested_version="purchase-v1",
        expected_version="purchase-v2",
        family="purchase",
    )
    problem = err.to_problem_detail(trace_id="trace-mismatch-123")
    assert problem.status == 409
    assert problem.code == ERROR_CODE_FEATURE_SPEC_MISMATCH
    assert problem.trace_id == "trace-mismatch-123"
    assert "purchase-v1" in problem.detail

