"""
test_explanation_endpoints.py — API integration tests for M2 SHAP Explainability Endpoints.

Endpoints covered:
- POST /explain/purchase
- POST /explain/transfer

Status codes & error handling:
- HTTP 200: Successful explanation (base_value, 29/20 contributions, sorted by absolute SHAP)
- HTTP 409: FEATURE_SPEC_MISMATCH
- HTTP 422: FEATURE_VALIDATION_ERROR
- HTTP 503: MODEL_NOT_READY (unloaded or unexplainable model)
- HTTP 500: INFERENCE_ERROR (unexpected SHAP runtime error)
- Regression: POST /predict/purchase & POST /predict/transfer remain operational.
"""

from typing import Any, Dict
import math
import uuid
import pytest
from fastapi.testclient import TestClient

from model_loader import (
    create_model_explainer,
    get_model_registry,
    get_sample_features_for_family,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import (
    ERROR_CODE_FEATURE_SPEC_MISMATCH,
    ERROR_CODE_FEATURE_VALIDATION,
    ERROR_CODE_INFERENCE_ERROR,
    ERROR_CODE_MODEL_NOT_READY,
)
from tests.test_explanation_engine import (
    create_synthetic_purchase_pipeline,
    create_synthetic_transfer_pipeline,
)


@pytest.fixture
def setup_explainable_models() -> None:
    """Sets up realistic synthetic explainable models in ModelRegistry for both families."""
    stop_background_model_loader()
    reset_model_cache()
    registry = get_model_registry()

    p_pipe = create_synthetic_purchase_pipeline()
    p_explainer = create_model_explainer("purchase", p_pipe)
    registry.set_model(
        family="purchase",
        model=p_pipe,
        version="synthetic-purchase-v1",
        model_name="purchase-fraud-model",
        feature_spec_version="purchase-v2",
        explainer=p_explainer,
    )
    registry.mark_startup_complete("purchase", True)

    t_pipe = create_synthetic_transfer_pipeline()
    t_explainer = create_model_explainer("transfer", t_pipe)
    registry.set_model(
        family="transfer",
        model=t_pipe,
        version="synthetic-transfer-v1",
        model_name="transfer-fraud-model",
        feature_spec_version="transfer-v2",
        explainer=t_explainer,
    )
    registry.mark_startup_complete("transfer", True)


# =====================================================================
# Successful Explanation Tests (HTTP 200)
# =====================================================================

def test_explain_purchase_success_200(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies successful POST /explain/purchase with 29 contributions and sorted order."""
    req_id = str(uuid.uuid4())
    features = get_sample_features_for_family("purchase")

    payload = {
        "request_id": req_id,
        "feature_spec_version": "purchase-v2",
        "features": features,
    }

    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 200, response.text
    assert response.headers.get("X-Request-Id") == req_id

    data = response.json()
    assert data["request_id"] == req_id
    assert data["model_name"] == "purchase-fraud-model"
    assert data["model_version"] == "synthetic-purchase-v1"
    assert "base_value" in data
    assert not math.isnan(data["base_value"])
    assert not math.isinf(data["base_value"])

    contributions = data["contributions"]
    assert len(contributions) == 29

    # Verify merchant_category contribution exists with original value
    cat_contrib = next(c for c in contributions if c["feature"] == "merchant_category")
    assert cat_contrib["value"] == "grocery_pos"
    assert isinstance(cat_contrib["shap"], float)

    # Verify descending absolute SHAP order
    for i in range(len(contributions) - 1):
        assert abs(contributions[i]["shap"]) >= abs(contributions[i + 1]["shap"]) - 1e-9


def test_explain_transfer_success_200(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies successful POST /explain/transfer with 20 contributions and sorted order."""
    req_id = str(uuid.uuid4())
    features = get_sample_features_for_family("transfer")

    payload = {
        "request_id": req_id,
        "feature_spec_version": "transfer-v2",
        "features": features,
    }

    response = test_client.post("/explain/transfer", json=payload)
    assert response.status_code == 200, response.text
    assert response.headers.get("X-Request-Id") == req_id

    data = response.json()
    assert data["request_id"] == req_id
    assert data["model_name"] == "transfer-fraud-model"
    assert data["model_version"] == "synthetic-transfer-v1"
    assert "base_value" in data
    assert not math.isnan(data["base_value"])

    contributions = data["contributions"]
    assert len(contributions) == 20

    # Verify descending absolute SHAP order
    for i in range(len(contributions) - 1):
        assert abs(contributions[i]["shap"]) >= abs(contributions[i + 1]["shap"]) - 1e-9


# =====================================================================
# Error Handling: 409 Feature Spec Mismatch
# =====================================================================

def test_explain_purchase_spec_mismatch_409(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies HTTP 409 FEATURE_SPEC_MISMATCH when wrong spec version is requested."""
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v1",  # Invalid version
        "features": get_sample_features_for_family("purchase"),
    }
    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 409
    body = response.json()
    assert body["code"] == ERROR_CODE_FEATURE_SPEC_MISMATCH
    assert "purchase-v2" in body["detail"]


def test_explain_transfer_spec_mismatch_409(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies HTTP 409 FEATURE_SPEC_MISMATCH for transfer domain."""
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "transfer-v1",  # Invalid version
        "features": get_sample_features_for_family("transfer"),
    }
    response = test_client.post("/explain/transfer", json=payload)
    assert response.status_code == 409
    body = response.json()
    assert body["code"] == ERROR_CODE_FEATURE_SPEC_MISMATCH


# =====================================================================
# Error Handling: 422 Feature Validation Error
# =====================================================================

def test_explain_missing_features_422(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies HTTP 422 when features are missing."""
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": {"amount": 50.0},  # Missing 28 features
    }
    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == ERROR_CODE_FEATURE_VALIDATION


def test_explain_unexpected_extra_features_422(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies HTTP 422 when unexpected extra features are provided."""
    features = get_sample_features_for_family("purchase")
    features["malicious_extra_feature"] = 999.9

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": features,
    }
    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == ERROR_CODE_FEATURE_VALIDATION


def test_explain_invalid_feature_type_422(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies HTTP 422 when a boolean is provided for a numeric feature."""
    features = get_sample_features_for_family("transfer")
    features["amount"] = True  # bool instead of float

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "transfer-v2",
        "features": features,
    }
    response = test_client.post("/explain/transfer", json=payload)
    assert response.status_code == 422
    body = response.json()
    assert body["code"] == ERROR_CODE_FEATURE_VALIDATION


# =====================================================================
# Error Handling: 503 Model Not Ready / Unexplainable
# =====================================================================

def test_explain_model_unloaded_503(test_client: TestClient) -> None:
    """Verifies HTTP 503 MODEL_NOT_READY when model family is unloaded."""
    stop_background_model_loader()
    reset_model_cache()
    registry = get_model_registry()
    registry.reset("purchase")

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": get_sample_features_for_family("purchase"),
    }
    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == ERROR_CODE_MODEL_NOT_READY


def test_explain_model_not_explainable_503(test_client: TestClient) -> None:
    """Verifies HTTP 503 MODEL_NOT_READY when model is loaded but explainer is uninitialized."""
    stop_background_model_loader()
    reset_model_cache()
    registry = get_model_registry()
    registry.set_model(
        family="transfer",
        model={"dummy": True},
        version="stub-transfer",
        explainer=None,
        explainer_error="Pipeline missing required 'preprocessor' step",
    )

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "transfer-v2",
        "features": get_sample_features_for_family("transfer"),
    }
    response = test_client.post("/explain/transfer", json=payload)
    assert response.status_code == 503
    body = response.json()
    assert body["code"] == ERROR_CODE_MODEL_NOT_READY
    assert "does not support SHAP explanations" in body["detail"]


# =====================================================================
# Error Handling: 500 Inference Error on Explainer Crash
# =====================================================================

def test_explain_runtime_failure_500(test_client: TestClient, monkeypatch: pytest.MonkeyPatch, setup_explainable_models: None) -> None:
    """Verifies HTTP 500 INFERENCE_ERROR when TreeExplainer raises an unexpected runtime exception."""
    registry = get_model_registry()
    entry = registry.get_entry("purchase")
    assert entry is not None and entry.explainer is not None

    def _broken_explain(*args: Any, **kwargs: Any) -> Any:
        raise RuntimeError("Low-level TreeExplainer C-extension segfault simulation")

    monkeypatch.setattr(entry.explainer, "explain", _broken_explain)

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": get_sample_features_for_family("purchase"),
    }
    response = test_client.post("/explain/purchase", json=payload)
    assert response.status_code == 500
    body = response.json()
    assert body["code"] == ERROR_CODE_INFERENCE_ERROR
    # Verify internal details/stack traces are not leaked
    assert "segfault" not in body["detail"]


# =====================================================================
# Regression Tests: Prediction Endpoints Still Operational
# =====================================================================

def test_prediction_endpoints_regression(test_client: TestClient, setup_explainable_models: None) -> None:
    """Verifies that existing POST /predict/purchase and /predict/transfer are unaffected."""
    # Test purchase prediction
    p_resp = test_client.post(
        "/predict/purchase",
        json={
            "request_id": str(uuid.uuid4()),
            "feature_spec_version": "purchase-v2",
            "features": get_sample_features_for_family("purchase"),
        },
    )
    assert p_resp.status_code == 200
    p_data = p_resp.json()
    assert 0.0 <= p_data["fraud_probability"] <= 1.0
    assert p_data["model_name"] == "purchase-fraud-model"

    # Test transfer prediction
    t_resp = test_client.post(
        "/predict/transfer",
        json={
            "request_id": str(uuid.uuid4()),
            "feature_spec_version": "transfer-v2",
            "features": get_sample_features_for_family("transfer"),
        },
    )
    assert t_resp.status_code == 200
    t_data = t_resp.json()
    assert 0.0 <= t_data["fraud_probability"] <= 1.0
    assert t_data["model_name"] == "transfer-fraud-model"
