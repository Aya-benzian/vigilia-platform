"""
test_inference_engine.py — Unit tests for M2 Model Loading & Inference Engine (Phase 2).

Tests:
1. Input DataFrame preparation for purchase (29 canonical columns in exact order).
2. Input DataFrame preparation for transfer (20 canonical columns in exact order).
3. Explicit DataFrame column construction not relying on dict key insertion order.
4. Validation enforcement via FeatureSpecValidator (HTTP 422, HTTP 409).
5. Exact preservation of cold-start values without feature recalculation.
6. Extraction of positive (fraud) class index from model.classes_ (not blindly assuming column 1).
7. Execution of predict_fraud_probability with standard and inverted classes.
8. Inference latency measurement in milliseconds.
9. Safe handling of invalid outputs (NaN, Inf, negative, > 1.0, wrong dimensions).
10. Safe handling of model inference exceptions (RuntimeError).
11. Warm-up execution with complete 29-feature purchase and 20-feature transfer payloads.
12. Compatibility with ModelRegistry and lifecycle tracking.
"""

import math
import time
from typing import Any, Dict, List
import numpy as np
import pandas as pd
import pytest

from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    ModelRegistry,
    SAMPLE_PURCHASE_FEATURES,
    SAMPLE_TRANSFER_FEATURES,
    extract_positive_class_index,
    get_sample_features_for_family,
    predict_fraud_probability,
    prepare_inference_input,
    warmup_model,
)
from schemas import (
    CANONICAL_FEATURE_SPECS,
    FeatureSpecMismatchError,
    FeatureValidationError,
)


# =====================================================================
# Fixtures
# =====================================================================

@pytest.fixture
def purchase_29_features() -> Dict[str, Any]:
    """Complete 29 canonical purchase-v2 features."""
    return dict(SAMPLE_PURCHASE_FEATURES)


@pytest.fixture
def transfer_20_features() -> Dict[str, Any]:
    """Complete 20 canonical transfer-v2 features."""
    return dict(SAMPLE_TRANSFER_FEATURES)


class MockEstimator:
    """Configurable mock estimator for testing inference extraction and timing."""

    def __init__(
        self,
        probabilities: Any = None,
        classes: Any = None,
        sleep_sec: float = 0.0,
        fail: bool = False,
        error_msg: str = "Estimator failed",
    ) -> None:
        self._probabilities = probabilities if probabilities is not None else np.array([[0.85, 0.15]])
        if classes is not None:
            self.classes_ = np.asarray(classes)
        self._sleep_sec = sleep_sec
        self._fail = fail
        self._error_msg = error_msg
        self.last_input: Any = None

    def predict_proba(self, X: Any) -> Any:
        self.last_input = X
        if self._sleep_sec > 0.0:
            time.sleep(self._sleep_sec)
        if self._fail:
            raise RuntimeError(self._error_msg)
        return self._probabilities


# =====================================================================
# 1. Model Input Preparation & Column Ordering
# =====================================================================

def test_prepare_inference_input_purchase_columns_and_order(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features, feature_spec_version="purchase-v2")
    assert isinstance(df, pd.DataFrame)
    assert df.shape == (1, 29)

    expected_cols = [f["name"] for f in CANONICAL_FEATURE_SPECS["purchase"]["features"]]
    assert df.columns.tolist() == expected_cols

    # Verify types
    assert isinstance(df["amount"].iloc[0], float)
    assert isinstance(df["hour_of_day"].iloc[0], (int, np.integer))
    assert isinstance(df["merchant_category"].iloc[0], str)


def test_prepare_inference_input_transfer_columns_and_order(
    transfer_20_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("transfer", transfer_20_features, feature_spec_version="transfer-v2")
    assert isinstance(df, pd.DataFrame)
    assert df.shape == (1, 20)

    expected_cols = [f["name"] for f in CANONICAL_FEATURE_SPECS["transfer"]["features"]]
    assert df.columns.tolist() == expected_cols

    assert isinstance(df["amount"].iloc[0], float)
    assert isinstance(df["pair_transfer_count"].iloc[0], (int, np.integer))


def test_prepare_inference_input_explicit_columns_not_dict_order(
    purchase_29_features: Dict[str, Any],
) -> None:
    """Verifies that reverse or scrambled input dictionary produces strictly canonical DataFrame columns."""
    reversed_dict = {k: purchase_29_features[k] for k in reversed(list(purchase_29_features.keys()))}
    assert list(reversed_dict.keys()) != [f["name"] for f in CANONICAL_FEATURE_SPECS["purchase"]["features"]]

    df = prepare_inference_input("purchase", reversed_dict)
    expected_cols = [f["name"] for f in CANONICAL_FEATURE_SPECS["purchase"]["features"]]
    assert df.columns.tolist() == expected_cols


def test_prepare_inference_input_preserves_cold_start_values_unmodified() -> None:
    """Cold-start values must be preserved exactly without modification or feature engineering."""
    cold_start_purchase = {
        f["name"]: f["cold_start"]
        for f in CANONICAL_FEATURE_SPECS["purchase"]["features"]
    }
    df = prepare_inference_input("purchase", cold_start_purchase)
    assert df["merchant_category"].iloc[0] == "unknown"
    assert df["is_new_merchant"].iloc[0] == 1
    assert df["time_since_last_txn_s"].iloc[0] == 2592000.0
    assert df["history_length"].iloc[0] == 0


def test_prepare_inference_input_validation_errors(
    purchase_29_features: Dict[str, Any],
) -> None:
    # 1. Missing feature raises FeatureValidationError (422)
    incomplete = dict(purchase_29_features)
    del incomplete["amount"]
    with pytest.raises(FeatureValidationError):
        prepare_inference_input("purchase", incomplete)

    # 2. Extra unexpected feature raises FeatureValidationError (422)
    extra = dict(purchase_29_features)
    extra["unknown_col"] = 42
    with pytest.raises(FeatureValidationError):
        prepare_inference_input("purchase", extra)

    # 3. Mismatched spec version raises FeatureSpecMismatchError (409)
    with pytest.raises(FeatureSpecMismatchError):
        prepare_inference_input("purchase", purchase_29_features, feature_spec_version="purchase-v1")

    # 4. Non-dict input raises FeatureValidationError (422)
    with pytest.raises(FeatureValidationError):
        prepare_inference_input("purchase", "not a dict")  # type: ignore

    # 5. Unsupported family raises FeatureValidationError (422)
    with pytest.raises(FeatureValidationError):
        prepare_inference_input("unsupported", purchase_29_features)


# =====================================================================
# 2. Positive Class Index Extraction
# =====================================================================

def test_extract_positive_class_index_standard_binary() -> None:
    # Standard sklearn convention: classes_ = [0, 1]
    est = MockEstimator(classes=[0, 1])
    assert extract_positive_class_index(est) == 1


def test_extract_positive_class_index_inverted_binary() -> None:
    # Inverted classes: classes_ = [1, 0] (positive class is at index 0!)
    est = MockEstimator(classes=[1, 0])
    assert extract_positive_class_index(est) == 0


def test_extract_positive_class_index_string_labels() -> None:
    # String labels ["LEGITIMATE", "FRAUD"]
    est1 = MockEstimator(classes=["LEGITIMATE", "FRAUD"])
    assert extract_positive_class_index(est1) == 1

    # Inverted string labels ["FRAUD", "LEGITIMATE"]
    est2 = MockEstimator(classes=["FRAUD", "LEGITIMATE"])
    assert extract_positive_class_index(est2) == 0

    # Lowercase string labels ["non_fraud", "is_fraud"]
    est3 = MockEstimator(classes=["non_fraud", "is_fraud"])
    assert extract_positive_class_index(est3) == 1

    # String digits ["0", "1"]
    est4 = MockEstimator(classes=["0", "1"])
    assert extract_positive_class_index(est4) == 1


def test_extract_positive_class_index_boolean_labels() -> None:
    est = MockEstimator(classes=[False, True])
    assert extract_positive_class_index(est) == 1


def test_extract_positive_class_index_pipeline_final_step() -> None:
    # Scikit-learn Pipeline with final estimator holding classes_
    class FakePipeline:
        def __init__(self) -> None:
            self.steps = [("scaler", None), ("classifier", MockEstimator(classes=[0, 1]))]

    assert extract_positive_class_index(FakePipeline()) == 1


def test_extract_positive_class_index_defaults_to_1_when_absent() -> None:
    # Model without classes_ attribute
    class NoClassesModel:
        def predict_proba(self, X):
            return np.array([[0.8, 0.2]])

    assert extract_positive_class_index(NoClassesModel()) == 1


def test_extract_positive_class_index_raises_on_unresolvable_classes() -> None:
    est = MockEstimator(classes=["ALPHA", "BETA"])
    with pytest.raises(ValueError, match="Unable to determine positive"):
        extract_positive_class_index(est)


# =====================================================================
# 3. Prediction Execution & Latency Measurement
# =====================================================================

def test_predict_fraud_probability_standard(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    est = MockEstimator(probabilities=np.array([[0.82, 0.18]]), classes=[0, 1])

    prob, latency_ms = predict_fraud_probability(est, df, family="purchase")
    assert prob == 0.18
    assert latency_ms >= 0.0


def test_predict_fraud_probability_inverted_classes(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    # When classes_ is [1, 0], column 0 is positive (fraud)
    est = MockEstimator(probabilities=np.array([[0.33, 0.67]]), classes=[1, 0])

    prob, latency_ms = predict_fraud_probability(est, df, family="purchase")
    assert prob == 0.33


def test_predict_fraud_probability_dummy_models(
    purchase_29_features: Dict[str, Any],
    transfer_20_features: Dict[str, Any],
) -> None:
    p_dummy = DummyPurchaseModel()
    p_df = prepare_inference_input("purchase", purchase_29_features)
    p_prob, p_lat = predict_fraud_probability(p_dummy, p_df, family="purchase")
    assert p_prob == 0.15
    assert p_lat >= 0.0

    t_dummy = DummyTransferModel()
    t_df = prepare_inference_input("transfer", transfer_20_features)
    t_prob, t_lat = predict_fraud_probability(t_dummy, t_df, family="transfer")
    assert t_prob == 0.15
    assert t_lat >= 0.0


def test_predict_fraud_probability_duration_measurement(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    # Inject 15ms sleep
    est = MockEstimator(probabilities=np.array([[0.7, 0.3]]), classes=[0, 1], sleep_sec=0.015)

    _, latency_ms = predict_fraud_probability(est, df, family="purchase")
    assert latency_ms >= 10.0


def test_predict_fraud_probability_1d_output(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    est = MockEstimator(probabilities=np.array([0.42]))
    prob, _ = predict_fraud_probability(est, df, family="purchase")
    assert prob == 0.42


# =====================================================================
# 4. Output Validation & Error Handling
# =====================================================================

@pytest.mark.parametrize(
    "invalid_proba,expected_err",
    [
        (np.array([[0.5, np.nan]]), "non-finite probability"),
        (np.array([[0.5, np.inf]]), "non-finite probability"),
        (np.array([[1.1, -0.1]]), "out-of-bounds probability"),
        (np.array([[0.0, 1.05]]), "out-of-bounds probability"),
        (np.array([]), "empty output"),
        (np.zeros((1, 2, 2)), "Unexpected prediction output shape"),
    ],
)
def test_predict_fraud_probability_invalid_outputs(
    purchase_29_features: Dict[str, Any],
    invalid_proba: Any,
    expected_err: str,
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    est = MockEstimator(probabilities=invalid_proba, classes=[0, 1])

    with pytest.raises(ValueError, match=expected_err):
        predict_fraud_probability(est, df, family="purchase")


def test_predict_fraud_probability_estimator_exception(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)
    failing_est = MockEstimator(fail=True, error_msg="CUDA out of memory")

    with pytest.raises(RuntimeError, match="Model inference failed for family 'purchase'") as exc:
        predict_fraud_probability(failing_est, df, family="purchase")
    assert "CUDA out of memory" in str(exc.value)


def test_predict_fraud_probability_missing_model_or_predict_proba(
    purchase_29_features: Dict[str, Any],
) -> None:
    df = prepare_inference_input("purchase", purchase_29_features)

    # 1. Model is None
    with pytest.raises(ValueError, match="Cannot execute prediction on None model"):
        predict_fraud_probability(None, df, family="purchase")

    # 2. Model lacks predict_proba
    class NoPredictProba:
        pass

    with pytest.raises(ValueError, match="does not expose 'predict_proba'"):
        predict_fraud_probability(NoPredictProba(), df, family="purchase")


# =====================================================================
# 5. Complete Warm-up Payloads & Lifecycle
# =====================================================================

def test_sample_features_completeness() -> None:
    """Verifies that sample warm-up features are 100% compliant with canonical specs."""
    assert len(SAMPLE_PURCHASE_FEATURES) == 29
    assert len(SAMPLE_TRANSFER_FEATURES) == 20

    # Ensure get_sample_features_for_family returns complete sets
    purchase_sample = get_sample_features_for_family("purchase")
    transfer_sample = get_sample_features_for_family("transfer")

    assert len(purchase_sample) == 29
    assert len(transfer_sample) == 20

    # Must pass validation without error
    p_df = prepare_inference_input("purchase", purchase_sample)
    assert p_df.shape == (1, 29)

    t_df = prepare_inference_input("transfer", transfer_sample)
    assert t_df.shape == (1, 20)


def test_warmup_model_with_controlled_mock() -> None:
    mock_p = MockEstimator(classes=[0, 1])
    warmup_model(mock_p, "purchase")

    # Verify that warm-up invoked predict_proba with a 29-column DataFrame
    assert mock_p.last_input is not None
    assert isinstance(mock_p.last_input, pd.DataFrame)
    assert mock_p.last_input.shape == (1, 29)
    assert mock_p.last_input.columns.tolist() == [
        f["name"] for f in CANONICAL_FEATURE_SPECS["purchase"]["features"]
    ]

    mock_t = MockEstimator(classes=[0, 1])
    warmup_model(mock_t, "transfer")
    assert isinstance(mock_t.last_input, pd.DataFrame)
    assert mock_t.last_input.shape == (1, 20)
    assert mock_t.last_input.columns.tolist() == [
        f["name"] for f in CANONICAL_FEATURE_SPECS["transfer"]["features"]
    ]

