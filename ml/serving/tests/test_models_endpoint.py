"""
test_models_endpoint.py — Focused tests for GET /models endpoint (Design 2 Section 15.2).

Tests cover:
1. Response when neither model is loaded (all_loaded=False, UNLOADED status, None versions/timestamps).
2. Response when only purchase model is loaded (independent representation, all_loaded=False).
3. Response when both models are loaded (all_loaded=True, READY status, distinct metadata).
4. Response when a family fails to load (FAILED status, active models preserved, no secret leak).
5. Strict schema conformance with ModelsListResponse.
"""

from fastapi.testclient import TestClient

from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    get_model_registry,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import ModelsListResponse


def test_get_models_when_neither_loaded(test_client: TestClient) -> None:
    stop_background_model_loader()
    reset_model_cache()

    response = test_client.get("/models")
    assert response.status_code == 200
    data = response.json()

    # Validate against Pydantic schema
    parsed = ModelsListResponse(**data)
    assert parsed.all_loaded is False

    # Purchase family metadata
    purchase_meta = parsed.models["purchase"]
    assert purchase_meta.family == "purchase"
    assert purchase_meta.model_name == "purchase-fraud-model"
    assert purchase_meta.model_version is None
    assert purchase_meta.alias == "champion"
    assert purchase_meta.feature_spec_version is None
    assert purchase_meta.status == "UNLOADED"
    assert purchase_meta.loaded_at is None

    # Transfer family metadata
    transfer_meta = parsed.models["transfer"]
    assert transfer_meta.family == "transfer"
    assert transfer_meta.model_name == "transfer-fraud-model"
    assert transfer_meta.model_version is None
    assert transfer_meta.alias == "champion"
    assert transfer_meta.feature_spec_version is None
    assert transfer_meta.status == "UNLOADED"
    assert transfer_meta.loaded_at is None


def test_get_models_when_purchase_only_loaded(test_client: TestClient) -> None:
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    p_dummy = DummyPurchaseModel(version="purchase-v1.0")
    registry.set_model(
        family="purchase",
        model=p_dummy,
        version="purchase-v1.0",
        feature_spec_version="purchase-spec-v1",
    )

    response = test_client.get("/models")
    assert response.status_code == 200
    data = response.json()
    parsed = ModelsListResponse(**data)

    assert parsed.all_loaded is False

    # Purchase is ready
    assert parsed.models["purchase"].status == "READY"
    assert parsed.models["purchase"].model_version == "purchase-v1.0"
    assert parsed.models["purchase"].feature_spec_version == "purchase-spec-v1"
    assert parsed.models["purchase"].loaded_at is not None

    # Transfer remains unloaded
    assert parsed.models["transfer"].status == "UNLOADED"
    assert parsed.models["transfer"].model_version is None
    assert parsed.models["transfer"].loaded_at is None


def test_get_models_when_both_loaded(test_client: TestClient) -> None:
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    p_dummy = DummyPurchaseModel(version="p-v2")
    t_dummy = DummyTransferModel(version="t-v1")

    registry.set_model(
        family="purchase",
        model=p_dummy,
        version="p-v2",
        feature_spec_version="purchase-spec-v2",
    )
    registry.set_model(
        family="transfer",
        model=t_dummy,
        version="t-v1",
        feature_spec_version="transfer-spec-v1",
    )

    response = test_client.get("/models")
    assert response.status_code == 200
    data = response.json()
    parsed = ModelsListResponse(**data)

    assert parsed.all_loaded is True
    assert parsed.models["purchase"].status == "READY"
    assert parsed.models["purchase"].model_version == "p-v2"
    assert parsed.models["transfer"].status == "READY"
    assert parsed.models["transfer"].model_version == "t-v1"


def test_get_models_reflects_failed_status_without_leaking_internals(test_client: TestClient) -> None:
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    # Purchase loaded
    registry.set_model("purchase", DummyPurchaseModel(version="p-ok"), version="p-ok")
    # Transfer fails
    registry.record_failure("transfer", "ConnectionRefused: MLflow server unreachable at http://internal:5000")

    response = test_client.get("/models")
    assert response.status_code == 200
    data = response.json()
    parsed = ModelsListResponse(**data)

    assert parsed.all_loaded is False
    assert parsed.models["purchase"].status == "READY"
    assert parsed.models["transfer"].status == "FAILED"
    assert parsed.models["transfer"].model_version is None

    # Ensure sensitive error strings and URLs are not exposed in the client-facing GET /models response
    response_text = response.text
    assert "ConnectionRefused" not in response_text
    assert "http://internal:5000" not in response_text
