"""
test_prediction_endpoints.py — Comprehensive tests for M2 Prediction Endpoints (Phase 3).

Endpoints tested:
- POST /predict/purchase
- POST /predict/transfer

Tests cover:
1. Successful purchase prediction (200 OK) with 29 features (purchase-v2).
2. Successful transfer prediction (200 OK) with 20 features (transfer-v2).
3. Correct probability and inference duration measurement in response.
4. Unresolved label and uncertainty explicitly set to None (no fabrication).
5. Independence between Purchase and Transfer models (one loaded does not affect the other).
6. Model unavailable returns structured RFC 7807 HTTP 503 MODEL_NOT_READY.
7. Missing features return RFC 7807 HTTP 422 FEATURE_VALIDATION_ERROR.
8. Extra unexpected features return RFC 7807 HTTP 422 FEATURE_VALIDATION_ERROR.
9. Invalid feature types (e.g. boolean for numeric) return HTTP 422.
10. Feature specification version mismatch returns RFC 7807 HTTP 409 FEATURE_SPEC_MISMATCH.
11. Inference execution failure returns RFC 7807 HTTP 500 INFERENCE_ERROR without leaking internals.
12. Invalid model probability output returns safe HTTP 500 INFERENCE_ERROR.
13. Request correlation ID preservation via X-Request-Id header.
14. Malformed top-level payload rejection (RFC 4122 UUID, extra forbidden fields).
"""

import uuid
from typing import Any, Dict
import numpy as np
import pytest
from fastapi.testclient import TestClient

from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    SAMPLE_PURCHASE_FEATURES,
    SAMPLE_TRANSFER_FEATURES,
    get_model_registry,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import (
    ERROR_CODE_FEATURE_SPEC_MISMATCH,
    ERROR_CODE_FEATURE_VALIDATION,
    ERROR_CODE_INFERENCE_ERROR,
    ERROR_CODE_MODEL_NOT_READY,
    ERROR_CODE_VALIDATION_ERROR,
    PredictionResponse,
)


@pytest.fixture
def purchase_request_payload() -> Dict[str, Any]:
    """Valid PredictionRequest payload for purchase-v2."""
    return {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": dict(SAMPLE_PURCHASE_FEATURES),
    }


@pytest.fixture
def transfer_request_payload() -> Dict[str, Any]:
    """Valid PredictionRequest payload for transfer-v2."""
    return {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "transfer-v2",
        "features": dict(SAMPLE_TRANSFER_FEATURES),
    }


class ControlledEstimator:
    """Mock model to control prediction probabilities, classes, and exceptions."""

    def __init__(
        self,
        probabilities: Any = None,
        classes: Any = None,
        fail: bool = False,
        error_msg: str = "Inference engine failure",
    ) -> None:
        self.probabilities = probabilities if probabilities is not None else np.array([[0.80, 0.20]])
        if classes is not None:
            self.classes_ = np.asarray(classes)
        else:
            self.classes_ = np.array([0, 1])
        self.fail = fail
        self.error_msg = error_msg

    def predict_proba(self, X: Any) -> Any:
        if self.fail:
            raise RuntimeError(self.error_msg)
        return self.probabilities


# =====================================================================
# 1. Successful Inferences & Response Contracts
# =====================================================================

def test_predict_purchase_success(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    # Load purchase dummy model
    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(version="p-v2"), "p-v2")

    response = test_client.post(
        "/predict/purchase",
        json=purchase_request_payload,
        headers={"X-Request-Id": purchase_request_payload["request_id"]},
    )
    assert response.status_code == 200
    assert response.headers.get("x-request-id") == purchase_request_payload["request_id"]

    data = response.json()
    parsed = PredictionResponse(**data)
    assert parsed.request_id == purchase_request_payload["request_id"]
    assert parsed.fraud_probability == 0.15
    assert parsed.inference_ms >= 0.0
    assert parsed.model_name == "purchase-fraud-model"
    assert parsed.model_version == "p-v2"
    assert parsed.feature_spec_version == "purchase-v2"
    # Unresolved values must be None (no fabrication)
    assert parsed.label is None
    assert parsed.uncertainty is None


def test_predict_transfer_success(
    test_client: TestClient, transfer_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    # Load transfer model with controlled estimator
    registry = get_model_registry()
    est = ControlledEstimator(probabilities=np.array([[0.65, 0.35]]), classes=[0, 1])
    registry.set_model("transfer", est, "transfer-v2.1")

    response = test_client.post("/predict/transfer", json=transfer_request_payload)
    assert response.status_code == 200

    data = response.json()
    parsed = PredictionResponse(**data)
    assert parsed.request_id == transfer_request_payload["request_id"]
    assert parsed.fraud_probability == 0.35
    assert parsed.inference_ms >= 0.0
    assert parsed.model_name == "transfer-fraud-model"
    assert parsed.model_version == "transfer-v2.1"
    assert parsed.feature_spec_version == "transfer-v2"
    assert parsed.label is None
    assert parsed.uncertainty is None


# =====================================================================
# 2. Independence Between Families
# =====================================================================

def test_purchase_loaded_transfer_unloaded(
    test_client: TestClient,
    purchase_request_payload: Dict[str, Any],
    transfer_request_payload: Dict[str, Any],
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    # Only load purchase
    registry = get_model_registry()
    registry.set_model("purchase", DummyPurchaseModel(), "1")

    # Purchase succeeds
    r_purchase = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert r_purchase.status_code == 200

    # Transfer fails with 503 MODEL_NOT_READY
    r_transfer = test_client.post("/predict/transfer", json=transfer_request_payload)
    assert r_transfer.status_code == 503
    assert r_transfer.headers["content-type"] == "application/problem+json"
    data = r_transfer.json()
    assert data["code"] == ERROR_CODE_MODEL_NOT_READY
    assert data["status"] == 503
    assert "transfer" in data["detail"].lower()


def test_transfer_loaded_purchase_unloaded(
    test_client: TestClient,
    purchase_request_payload: Dict[str, Any],
    transfer_request_payload: Dict[str, Any],
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    # Only load transfer
    registry = get_model_registry()
    registry.set_model("transfer", DummyTransferModel(), "1")

    r_transfer = test_client.post("/predict/transfer", json=transfer_request_payload)
    assert r_transfer.status_code == 200

    r_purchase = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert r_purchase.status_code == 503
    data = r_purchase.json()
    assert data["code"] == ERROR_CODE_MODEL_NOT_READY
    assert "purchase" in data["detail"].lower()


# =====================================================================
# 3. Model Unavailable (HTTP 503)
# =====================================================================

def test_model_not_ready_returns_rfc7807_503(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    response = test_client.post(
        "/predict/purchase",
        json=purchase_request_payload,
        headers={"X-Trace-Id": "trace-503-check"},
    )
    assert response.status_code == 503
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["code"] == ERROR_CODE_MODEL_NOT_READY
    assert data["status"] == 503
    assert data["trace_id"] == "trace-503-check"
    assert "not loaded" in data["detail"]


# =====================================================================
# 4. Feature Specification Version Mismatch (HTTP 409)
# =====================================================================

def test_feature_spec_version_mismatch_returns_409(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    # Incompatible version 'purchase-v1'
    purchase_request_payload["feature_spec_version"] = "purchase-v1"
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 409
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["code"] == ERROR_CODE_FEATURE_SPEC_MISMATCH
    assert data["status"] == 409
    assert "purchase-v1" in data["detail"]
    assert "purchase-v2" in data["detail"]


def test_transfer_with_purchase_version_returns_409(
    test_client: TestClient, transfer_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("transfer", DummyTransferModel(), "1")

    transfer_request_payload["feature_spec_version"] = "purchase-v2"
    response = test_client.post("/predict/transfer", json=transfer_request_payload)
    assert response.status_code == 409
    data = response.json()
    assert data["code"] == ERROR_CODE_FEATURE_SPEC_MISMATCH


# =====================================================================
# 5. Feature Validation Errors (HTTP 422)
# =====================================================================

def test_missing_feature_returns_422(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    del purchase_request_payload["features"]["amount"]
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["code"] == ERROR_CODE_FEATURE_VALIDATION
    assert data["status"] == 422
    assert any(err["field"] == "features.amount" for err in data["errors"])


def test_extra_unexpected_feature_returns_422(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    purchase_request_payload["features"]["forbidden_extra_feature"] = 999.0
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 422
    data = response.json()
    assert data["code"] == ERROR_CODE_FEATURE_VALIDATION
    assert any(err["field"] == "features.forbidden_extra_feature" for err in data["errors"])


def test_invalid_feature_type_returns_422(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    # Pass boolean for numeric field
    purchase_request_payload["features"]["amount"] = True
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 422
    data = response.json()
    assert data["code"] == ERROR_CODE_FEATURE_VALIDATION
    assert any(err["field"] == "features.amount" for err in data["errors"])


# =====================================================================
# 6. Inference Exceptions & Invalid Model Outputs (HTTP 500)
# =====================================================================

def test_inference_runtime_error_returns_safe_500(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    failing_model = ControlledEstimator(fail=True, error_msg="/etc/secrets/internal_key.pem not found")
    get_model_registry().set_model("purchase", failing_model, "1")

    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 500
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()
    assert data["code"] == ERROR_CODE_INFERENCE_ERROR
    assert data["status"] == 500
    # Sensitive details and paths must never leak into client response
    assert "internal_key" not in response.text
    assert "secrets" not in response.text


def test_invalid_model_probability_returns_500(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()

    # Model returns NaN probability
    nan_model = ControlledEstimator(probabilities=np.array([[0.5, np.nan]]))
    get_model_registry().set_model("purchase", nan_model, "1")

    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 500
    data = response.json()
    assert data["code"] == ERROR_CODE_INFERENCE_ERROR


# =====================================================================
# 7. Request Payload Validation (HTTP 422 VALIDATION_ERROR)
# =====================================================================

def test_invalid_request_id_non_uuid_returns_422(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    purchase_request_payload["request_id"] = "not-a-valid-uuid"
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 422
    data = response.json()
    assert data["code"] == ERROR_CODE_VALIDATION_ERROR
    assert any("request_id" in err["field"] for err in data["errors"])


def test_top_level_extra_field_forbidden(
    test_client: TestClient, purchase_request_payload: Dict[str, Any]
) -> None:
    stop_background_model_loader()
    reset_model_cache()
    get_model_registry().set_model("purchase", DummyPurchaseModel(), "1")

    purchase_request_payload["illegal_root_field"] = "bad"
    response = test_client.post("/predict/purchase", json=purchase_request_payload)
    assert response.status_code == 422
    data = response.json()
    assert data["code"] == ERROR_CODE_VALIDATION_ERROR
    assert any("illegal_root_field" in err["field"] for err in data["errors"])

