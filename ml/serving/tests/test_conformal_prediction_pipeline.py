"""
test_conformal_prediction_pipeline.py — Integration tests verifying Split Conformal
prediction integration into the M2 prediction pipeline (/predict/purchase, /predict/transfer).

Validates:
1. Purchase with valid synthetic configuration.
2. Transfer with valid synthetic configuration.
3. Prediction Set singleton (LEGITIMATE and FRAUD).
4. Prediction Set ambiguous (LEGITIMATE and FRAUD).
5. Prediction Set empty (no class supported by threshold).
6. Missing conformal configuration (graceful degradation: fields are None, HTTP 200).
7. Conformal evaluation runtime failure (graceful degradation: fields are None, HTTP 200).
8. Purchase / Transfer configuration independence.
9. SHAP endpoints non-regression (/explain/purchase, /explain/transfer).
10. Existing PredictionResponse contract non-regression.
"""

from __future__ import annotations

import uuid
from typing import Any, Dict
from unittest.mock import MagicMock
import numpy as np
import pytest
from fastapi.testclient import TestClient

from conformal_evaluator import ConformalEvaluator
from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    SAMPLE_PURCHASE_FEATURES,
    SAMPLE_TRANSFER_FEATURES,
    attach_test_conformal_evaluator,
    create_model_explainer,
    get_model_registry,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import ExplanationResponse, PredictionResponse
from tests.test_explanation_engine import (
    create_synthetic_purchase_pipeline,
    create_synthetic_transfer_pipeline,
)


# ---------------------------------------------------------------------
# Controlled Estimator for exact probability branches
# ---------------------------------------------------------------------

class ControlledEstimator:
    """Mock model to control prediction probabilities."""

    def __init__(self, fraud_prob: float, family: str = "purchase", version: str = "ctrl-v1") -> None:
        self.fraud_prob = fraud_prob
        self.family = family
        self.version = version
        self.classes_ = np.array([0, 1])

    def predict_proba(self, X: Any) -> Any:
        n_samples = len(X) if hasattr(X, "__len__") else 1
        p_legit = 1.0 - self.fraud_prob
        p_fraud = self.fraud_prob
        return np.tile([p_legit, p_fraud], (n_samples, 1))

    def predict(self, X: Any) -> Any:
        n_samples = len(X) if hasattr(X, "__len__") else 1
        label = 1 if self.fraud_prob >= 0.5 else 0
        return np.full(n_samples, label, dtype=int)


# ---------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------

@pytest.fixture
def purchase_payload() -> Dict[str, Any]:
    """Valid PredictionRequest payload for purchase-v2 using canonical test features."""
    return {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": dict(SAMPLE_PURCHASE_FEATURES),
    }


@pytest.fixture
def transfer_payload() -> Dict[str, Any]:
    """Valid PredictionRequest payload for transfer-v2 using canonical test features."""
    return {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "transfer-v2",
        "features": dict(SAMPLE_TRANSFER_FEATURES),
    }


# =====================================================================
# Tests: Conformal Pipeline Integration
# =====================================================================

def test_purchase_with_valid_conformal_config(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 1: Purchase prediction with valid synthetic conformal configuration."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")

    # Synthetic config: q=0.54, DummyPurchaseModel produces p=0.15
    # s(LEGIT)=0.15 <= 0.54 -> in set; s(FRAUD)=0.85 > 0.54 -> not in set
    evaluator = ConformalEvaluator(default_quantile=0.54)
    attach_test_conformal_evaluator("purchase", evaluator=evaluator)

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    parsed = PredictionResponse(**data)
    assert parsed.fraud_probability == 0.15
    assert parsed.prediction_set == ["LEGITIMATE"]
    assert parsed.conformal_status == "SINGLETON"
    assert parsed.is_ambiguous is False


def test_transfer_with_valid_conformal_config(
    test_client: TestClient, transfer_payload: Dict[str, Any]
) -> None:
    """Case 2: Transfer prediction with valid synthetic conformal configuration."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("transfer", DummyTransferModel(version="t-v2"), "t-v2")

    evaluator = ConformalEvaluator(default_quantile=0.48)
    attach_test_conformal_evaluator("transfer", evaluator=evaluator)

    response = test_client.post("/predict/transfer", json=transfer_payload)
    assert response.status_code == 200

    data = response.json()
    parsed = PredictionResponse(**data)
    assert parsed.fraud_probability == 0.15
    assert parsed.prediction_set == ["LEGITIMATE"]
    assert parsed.conformal_status == "SINGLETON"
    assert parsed.is_ambiguous is False


def test_prediction_set_singleton_fraud(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 3: Singleton set containing only FRAUD."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    # Model predicting p = 0.90
    # s(LEGIT)=0.90 > 0.54; s(FRAUD)=0.10 <= 0.54 -> ['FRAUD']
    registry.set_model("purchase", ControlledEstimator(fraud_prob=0.90, family="purchase"), "fixed-v1")
    attach_test_conformal_evaluator("purchase", evaluator=ConformalEvaluator(default_quantile=0.54))

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    assert data["fraud_probability"] == 0.90
    assert data["prediction_set"] == ["FRAUD"]
    assert data["conformal_status"] == "SINGLETON"
    assert data["is_ambiguous"] is False


def test_prediction_set_ambiguous(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 4: Ambiguous prediction set containing both ['LEGITIMATE', 'FRAUD']."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    # Model predicting p = 0.50 with q = 0.54
    # s(LEGIT)=0.50 <= 0.54; s(FRAUD)=0.50 <= 0.54 -> ['LEGITIMATE', 'FRAUD']
    registry.set_model("purchase", ControlledEstimator(fraud_prob=0.50, family="purchase"), "fixed-v1")
    attach_test_conformal_evaluator("purchase", evaluator=ConformalEvaluator(default_quantile=0.54))

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    assert data["fraud_probability"] == 0.50
    assert data["prediction_set"] == ["LEGITIMATE", "FRAUD"]
    assert data["conformal_status"] == "AMBIGUOUS"
    assert data["is_ambiguous"] is True


def test_prediction_set_empty(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """
    Case 5: Empty prediction set [].
    SEMANTICS: An empty set means neither class achieves the required nonconformity threshold.
    By definition, the prediction is not a singleton decision, so is_ambiguous MUST be True.
    """
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    # Model predicting p = 0.50 with q = 0.40
    # s(LEGIT)=0.50 > 0.40; s(FRAUD)=0.50 > 0.40 -> []
    registry.set_model("purchase", ControlledEstimator(fraud_prob=0.50, family="purchase"), "fixed-v1")
    attach_test_conformal_evaluator("purchase", evaluator=ConformalEvaluator(default_quantile=0.40))

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    assert data["fraud_probability"] == 0.50
    assert data["prediction_set"] == []
    assert data["conformal_status"] == "EMPTY"
    assert data["is_ambiguous"] is True


def test_conformal_config_absent_fallback(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 6: Missing conformal configuration leaves prediction operational with None fields."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")
    # Explicitly ensure no conformal evaluator is attached
    entry = registry.get_entry("purchase")
    assert entry is not None
    assert entry.conformal_evaluator is None

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    assert data["fraud_probability"] == 0.15
    assert data["prediction_set"] is None
    assert data["conformal_status"] is None
    assert data["is_ambiguous"] is None


def test_conformal_evaluation_error_graceful_degradation(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 7: A conformal evaluation error must NEVER turn a valid prediction into an HTTP 500."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")

    mock_evaluator = MagicMock()
    mock_evaluator.evaluate.side_effect = RuntimeError("Unexpected internal conformal calculation fault")
    attach_test_conformal_evaluator("purchase", evaluator=mock_evaluator)

    response = test_client.post("/predict/purchase", json=purchase_payload)
    # HTTP 200 must be preserved
    assert response.status_code == 200

    data = response.json()
    assert data["fraud_probability"] == 0.15
    assert data["prediction_set"] is None
    assert data["conformal_status"] is None
    assert data["is_ambiguous"] is None


def test_purchase_transfer_conformal_independence(
    test_client: TestClient,
    purchase_payload: Dict[str, Any],
    transfer_payload: Dict[str, Any],
) -> None:
    """Case 8: Purchase and Transfer conformal configurations remain strictly isolated."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")
    registry.set_model("transfer", ControlledEstimator(fraud_prob=0.50, family="transfer"), "t-v2")

    # Purchase has q=0.54 => Singleton ['LEGITIMATE']
    attach_test_conformal_evaluator("purchase", evaluator=ConformalEvaluator(default_quantile=0.54))

    # Transfer has q=0.40 => Empty []
    attach_test_conformal_evaluator("transfer", evaluator=ConformalEvaluator(default_quantile=0.40))

    # Verify Purchase
    resp_p = test_client.post("/predict/purchase", json=purchase_payload)
    assert resp_p.status_code == 200
    data_p = resp_p.json()
    assert data_p["prediction_set"] == ["LEGITIMATE"]
    assert data_p["conformal_status"] == "SINGLETON"
    assert data_p["is_ambiguous"] is False

    # Verify Transfer
    resp_t = test_client.post("/predict/transfer", json=transfer_payload)
    assert resp_t.status_code == 200
    data_t = resp_t.json()
    assert data_t["prediction_set"] == []
    assert data_t["conformal_status"] == "EMPTY"
    assert data_t["is_ambiguous"] is True


def test_shap_endpoints_non_regression(
    test_client: TestClient,
    purchase_payload: Dict[str, Any],
    transfer_payload: Dict[str, Any],
) -> None:
    """Case 9: SHAP explainability endpoints continue to work without regression."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    p_pipe = create_synthetic_purchase_pipeline()
    p_exp = create_model_explainer("purchase", p_pipe)
    registry.set_model("purchase", p_pipe, "syn-p1", explainer=p_exp)

    t_pipe = create_synthetic_transfer_pipeline()
    t_exp = create_model_explainer("transfer", t_pipe)
    registry.set_model("transfer", t_pipe, "syn-t1", explainer=t_exp)

    # Test /explain/purchase
    resp_p = test_client.post("/explain/purchase", json=purchase_payload)
    assert resp_p.status_code == 200
    parsed_p = ExplanationResponse(**resp_p.json())
    assert parsed_p.model_name == "purchase-fraud-model"
    assert len(parsed_p.contributions) > 0

    # Test /explain/transfer
    resp_t = test_client.post("/explain/transfer", json=transfer_payload)
    assert resp_t.status_code == 200
    parsed_t = ExplanationResponse(**resp_t.json())
    assert parsed_t.model_name == "transfer-fraud-model"
    assert len(parsed_t.contributions) > 0


def test_existing_prediction_response_contract_non_regression(
    test_client: TestClient, purchase_payload: Dict[str, Any]
) -> None:
    """Case 10: All standard fields of PredictionResponse are preserved."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")

    response = test_client.post("/predict/purchase", json=purchase_payload)
    assert response.status_code == 200

    data = response.json()
    # Structural verification
    assert data["request_id"] == purchase_payload["request_id"]
    assert "fraud_probability" in data
    assert "inference_ms" in data
    assert data["model_name"] == "purchase-fraud-model"
    assert data["model_version"] == "p-v2"
    assert data["feature_spec_version"] == "purchase-v2"
    # Business rule: M2 never makes decision
    assert data["label"] is None
    assert data["uncertainty"] is None
