"""
test_conformal_config.py — Comprehensive unit tests for ConformalConfigValidator
and PredictionResponse conformal extensions (Design 2 / Final v4.2).
"""

from __future__ import annotations

import json
import math
from typing import Any, Dict
import pytest

from conformal_config import (
    ConformalCompatibilityError,
    ConformalConfig,
    ConformalConfigError,
    ConformalConfigValidator,
    ConformalValidationError,
)
from schemas import PredictionResponse


# =====================================================================
# Fixtures: Valid baseline configurations (Synthetic test fixtures only)
# =====================================================================

@pytest.fixture
def valid_purchase_dict() -> Dict[str, Any]:
    """
    Synthetic valid configuration for Purchase family.
    alpha=0.05, n=1000 => k = ceil(1001 * 0.95) = ceil(950.95) = 951.
    """
    return {
        "family": "purchase",
        "model_name": "purchase-fraud-model",
        "model_version": "1",
        "feature_spec_version": "purchase-v2",
        "method": "split_conformal",
        "alpha": 0.05,
        "quantile": 0.54,
        "n": 1000,
        "k": 951,
        "calibration_dataset_id": "synthetic_cal_purchase_v1",
        "metadata": {"source": "unit_test_fixture"},
    }


@pytest.fixture
def valid_transfer_dict() -> Dict[str, Any]:
    """
    Synthetic valid configuration for Transfer family.
    alpha=0.05, n=2000 => k = ceil(2001 * 0.95) = ceil(1900.95) = 1901.
    """
    return {
        "family": "transfer",
        "model_name": "transfer-fraud-model",
        "model_version": "1",
        "feature_spec_version": "transfer-v2",
        "method": "split_conformal",
        "alpha": 0.05,
        "quantile": 0.48,
        "n": 2000,
        "k": 1901,
        "calibration_dataset_id": "synthetic_cal_transfer_v1",
        "metadata": {"source": "unit_test_fixture"},
    }


# =====================================================================
# 1. Valid Configuration Tests
# =====================================================================

def test_valid_purchase_config(valid_purchase_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_purchase_dict, target_family="purchase")
    assert isinstance(config, ConformalConfig)
    assert config.family == "purchase"
    assert config.model_name == "purchase-fraud-model"
    assert config.model_version == "1"
    assert config.feature_spec_version == "purchase-v2"
    assert config.method == "split_conformal"
    assert config.alpha == 0.05
    assert config.quantile == 0.54
    assert config.n == 1000
    assert config.k == 951
    assert config.calibration_dataset_id == "synthetic_cal_purchase_v1"

    # Dict and JSON round-trip
    as_dict = config.to_dict()
    assert as_dict["family"] == "purchase"
    assert as_dict["quantile"] == 0.54

    json_str = json.dumps(as_dict)
    reloaded = ConformalConfigValidator.validate_json(json_str, target_family="purchase")
    assert reloaded == config


def test_valid_transfer_config(valid_transfer_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_transfer_dict, target_family="transfer")
    assert isinstance(config, ConformalConfig)
    assert config.family == "transfer"
    assert config.model_name == "transfer-fraud-model"
    assert config.model_version == "1"
    assert config.feature_spec_version == "transfer-v2"
    assert config.quantile == 0.48
    assert config.n == 2000
    assert config.k == 1901


# =====================================================================
# 2. Family and Cross-Family Isolation Tests
# =====================================================================

def test_cross_family_rejection_purchase_as_transfer(valid_purchase_dict: Dict[str, Any]) -> None:
    with pytest.raises(ConformalCompatibilityError, match="incompatible with target family 'transfer'"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict, target_family="transfer")


def test_cross_family_rejection_transfer_as_purchase(valid_transfer_dict: Dict[str, Any]) -> None:
    with pytest.raises(ConformalCompatibilityError, match="incompatible with target family 'purchase'"):
        ConformalConfigValidator.validate_dict(valid_transfer_dict, target_family="purchase")


def test_unsupported_family(valid_purchase_dict: Dict[str, Any]) -> None:
    valid_purchase_dict["family"] = "crypto_payment"
    with pytest.raises(ConformalValidationError, match="Unsupported family 'crypto_payment'"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 3. Compatibility Verification Tests
# =====================================================================

def test_validate_compatibility_success(valid_purchase_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_purchase_dict)
    # Matching coordinates
    ConformalConfigValidator.validate_compatibility(
        config=config,
        expected_family="purchase",
        expected_model_name="purchase-fraud-model",
        expected_model_version="1",
        expected_feature_spec_version="purchase-v2",
    )


def test_validate_compatibility_model_version_mismatch(valid_purchase_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_purchase_dict)
    with pytest.raises(ConformalCompatibilityError, match="Conformal model_version mismatch"):
        ConformalConfigValidator.validate_compatibility(
            config=config,
            expected_family="purchase",
            expected_model_version="2",
        )


def test_validate_compatibility_model_name_mismatch(valid_purchase_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_purchase_dict)
    with pytest.raises(ConformalCompatibilityError, match="Conformal model_name mismatch"):
        ConformalConfigValidator.validate_compatibility(
            config=config,
            expected_family="purchase",
            expected_model_name="other-model",
        )


def test_validate_compatibility_feature_spec_mismatch(valid_purchase_dict: Dict[str, Any]) -> None:
    config = ConformalConfigValidator.validate_dict(valid_purchase_dict)
    with pytest.raises(ConformalCompatibilityError, match="Conformal feature_spec_version mismatch"):
        ConformalConfigValidator.validate_compatibility(
            config=config,
            expected_family="purchase",
            expected_feature_spec_version="purchase-v3",
        )


def test_feature_spec_incompatible_with_family(valid_purchase_dict: Dict[str, Any]) -> None:
    valid_purchase_dict["feature_spec_version"] = "transfer-v2"
    with pytest.raises(ConformalValidationError, match="incompatible with family 'purchase'"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 4. Alpha Validation Tests
# =====================================================================

@pytest.mark.parametrize("invalid_alpha", [
    0.0,
    -0.05,
    -1.0,
    1.0,
    1.05,
    float("nan"),
    float("inf"),
    float("-inf"),
    True,
    False,
    "0.05",
    None,
])
def test_invalid_alpha(valid_purchase_dict: Dict[str, Any], invalid_alpha: Any) -> None:
    valid_purchase_dict["alpha"] = invalid_alpha
    with pytest.raises(ConformalValidationError):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 5. Quantile Validation & Infinity Support Tests
# =====================================================================

@pytest.mark.parametrize("invalid_quantile", [
    -0.0001,
    -1.0,
    1.0001,
    2.0,
    float("nan"),
    float("-inf"),
    True,
    False,
    "nan",
    "-inf",
    "-infinity",
    "invalid_str",
    None,
])
def test_invalid_quantile(valid_purchase_dict: Dict[str, Any], invalid_quantile: Any) -> None:
    valid_purchase_dict["quantile"] = invalid_quantile
    with pytest.raises(ConformalValidationError):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


@pytest.mark.parametrize("inf_representation", [
    float("inf"),
    "inf",
    "+inf",
    "infinity",
    "+infinity",
    "INFINITY",
])
def test_positive_infinity_support(valid_purchase_dict: Dict[str, Any], inf_representation: Any) -> None:
    """
    When n=10, alpha=0.05 => (10+1)*0.95 = 10.45 => k = ceil(10.45) = 11.
    k (11) > n (10) => quantile MUST be +infinity.
    """
    valid_purchase_dict["n"] = 10
    valid_purchase_dict["k"] = 11
    valid_purchase_dict["quantile"] = inf_representation

    config = ConformalConfigValidator.validate_dict(valid_purchase_dict)
    assert math.isinf(config.quantile)
    assert config.quantile > 0

    as_dict = config.to_dict()
    assert as_dict["quantile"] == "Infinity"

    json_str = json.dumps(as_dict)
    reloaded = ConformalConfigValidator.validate_json(json_str)
    assert math.isinf(reloaded.quantile)


def test_infinite_quantile_rejected_when_k_le_n(valid_purchase_dict: Dict[str, Any]) -> None:
    """When k <= n, quantile must be finite in [0.0, 1.0]; +inf must be rejected."""
    valid_purchase_dict["quantile"] = float("inf")
    with pytest.raises(ConformalValidationError, match="quantile must be finite"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


def test_finite_quantile_rejected_when_k_gt_n(valid_purchase_dict: Dict[str, Any]) -> None:
    """When k > n, sample size is insufficient so quantile must be +inf; finite q must be rejected."""
    valid_purchase_dict["n"] = 10
    valid_purchase_dict["k"] = 11
    valid_purchase_dict["quantile"] = 0.95
    with pytest.raises(ConformalValidationError, match="quantile must be \\+infinity"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 6. Rank k and Sample Size n Inconsistency Tests
# =====================================================================

@pytest.mark.parametrize("bad_n", [0, -1, -100, 100.5, True, False, "1000", None])
def test_invalid_n_sample_size(valid_purchase_dict: Dict[str, Any], bad_n: Any) -> None:
    valid_purchase_dict["n"] = bad_n
    with pytest.raises(ConformalValidationError):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


@pytest.mark.parametrize("bad_k", [0, -1, 100.5, True, False, "951", None])
def test_invalid_k_type(valid_purchase_dict: Dict[str, Any], bad_k: Any) -> None:
    valid_purchase_dict["k"] = bad_k
    with pytest.raises(ConformalValidationError):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


def test_mathematical_k_inconsistency(valid_purchase_dict: Dict[str, Any]) -> None:
    # For n=1000, alpha=0.05, expected k is 951. If 950 is passed, it must fail.
    valid_purchase_dict["k"] = 950
    with pytest.raises(ConformalValidationError, match="Inconsistent conformal rank k: got 950, expected ceil"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 7. Missing and Malformed Configuration Tests
# =====================================================================

@pytest.mark.parametrize("missing_field", [
    "family",
    "model_name",
    "model_version",
    "feature_spec_version",
    "method",
    "alpha",
    "quantile",
    "n",
    "k",
    "calibration_dataset_id",
])
def test_missing_required_fields(valid_purchase_dict: Dict[str, Any], missing_field: str) -> None:
    del valid_purchase_dict[missing_field]
    with pytest.raises(ConformalValidationError, match="Missing required conformal configuration fields"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


def test_non_dict_config_rejected() -> None:
    with pytest.raises(ConformalValidationError, match="Configuration must be a dictionary"):
        ConformalConfigValidator.validate_dict("not_a_dict")  # type: ignore


def test_malformed_json_rejected() -> None:
    with pytest.raises(ConformalValidationError, match="Malformed JSON configuration payload"):
        ConformalConfigValidator.validate_json("{invalid_json_string:")


def test_empty_string_identifiers_rejected(valid_purchase_dict: Dict[str, Any]) -> None:
    for field_name in ("model_name", "model_version", "feature_spec_version", "calibration_dataset_id"):
        corrupted = dict(valid_purchase_dict)
        corrupted[field_name] = "   "
        with pytest.raises(ConformalValidationError, match="cannot be empty"):
            ConformalConfigValidator.validate_dict(corrupted)


def test_unsupported_method_rejected(valid_purchase_dict: Dict[str, Any]) -> None:
    valid_purchase_dict["method"] = "inductive_conformal"
    with pytest.raises(ConformalValidationError, match="Unsupported conformal method"):
        ConformalConfigValidator.validate_dict(valid_purchase_dict)


# =====================================================================
# 8. PredictionResponse Conformal Fields & Backwards Compatibility
# =====================================================================

def test_prediction_response_with_conformal_fields() -> None:
    resp = PredictionResponse(
        request_id="e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
        label=None,
        fraud_probability=0.08,
        uncertainty=None,
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        inference_ms=12.5,
        prediction_set=["LEGITIMATE"],
        conformal_status="SINGLETON",
        is_ambiguous=False,
    )
    assert resp.prediction_set == ["LEGITIMATE"]
    assert resp.conformal_status == "SINGLETON"
    assert resp.is_ambiguous is False

    dumped = resp.model_dump()
    assert dumped["prediction_set"] == ["LEGITIMATE"]
    assert dumped["conformal_status"] == "SINGLETON"
    assert dumped["is_ambiguous"] is False

    json_str = resp.model_dump_json()
    reparsed = PredictionResponse.model_validate_json(json_str)
    assert reparsed == resp


def test_prediction_response_backwards_compatibility_without_conformal() -> None:
    """Verifies existing callers omitting conformal fields continue to parse cleanly."""
    legacy_payload = {
        "request_id": "e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
        "label": "FRAUD",
        "fraud_probability": 0.94,
        "uncertainty": 0.08,
        "model_name": "purchase-fraud-model",
        "model_version": "3",
        "feature_spec_version": "purchase-v2",
        "inference_ms": 18.0,
    }
    resp = PredictionResponse(**legacy_payload)
    assert resp.prediction_set is None
    assert resp.conformal_status is None
    assert resp.is_ambiguous is None
    assert resp.fraud_probability == 0.94
    assert resp.inference_ms == 18.0


def test_prediction_response_ambiguous_set() -> None:
    resp = PredictionResponse(
        request_id="e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
        fraud_probability=0.51,
        model_name="transfer-fraud-model",
        model_version="1",
        feature_spec_version="transfer-v2",
        inference_ms=15.0,
        prediction_set=["LEGITIMATE", "FRAUD"],
        conformal_status="AMBIGUOUS",
        is_ambiguous=True,
    )
    assert resp.prediction_set == ["LEGITIMATE", "FRAUD"]
    assert resp.conformal_status == "AMBIGUOUS"
    assert resp.is_ambiguous is True
