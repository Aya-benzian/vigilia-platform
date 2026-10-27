"""
test_health.py — Tests for Kubernetes liveness, readiness, and startup probes and Prometheus metrics.
Conforms to Vigilia Design 2, Sections 15.2 and 15.5.
"""

from fastapi.testclient import TestClient
import pytest

from model_loader import (
    DummyFraudModel,
    DummyPurchaseModel,
    DummyTransferModel,
    get_model_registry,
    reset_model_cache,
    set_cached_model,
    validate_model_signature,
    warmup_model,
)
from schemas import HealthResponse


def test_liveness_probe_returns_200_even_when_model_not_loaded(test_client: TestClient) -> None:
    reset_model_cache()
    response = test_client.get("/health/live")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["service"] == "ml-serving"
    assert data["model_loaded"] is False


def test_readiness_probe_returns_503_when_model_not_loaded(test_client: TestClient) -> None:
    reset_model_cache()
    response = test_client.get("/health/ready")
    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "DOWN"
    assert data["model_loaded"] is False
    assert "Download/background retry in progress" in data["detail"]


def test_readiness_probe_returns_200_when_model_is_loaded(test_client: TestClient) -> None:
    dummy = DummyFraudModel(version="1")
    set_cached_model(dummy, "1")

    response = test_client.get("/health/ready")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["model_loaded"] is True
    assert data["model_name"] == "fraud-model"
    assert data["model_version"] == "1"


def test_prometheus_metrics_endpoint_is_exposed(test_client: TestClient) -> None:
    response = test_client.get("/metrics")
    assert response.status_code == 200
    assert "fintech_ml_prediction_uncertainty" in response.text


# ---------------------------------------------------------------------------
# Focused Startup Probe Tests (Design 2, Sections 15.2 and 15.5)
# ---------------------------------------------------------------------------

def test_startup_probe_returns_503_when_neither_model_initialized(test_client: TestClient) -> None:
    """Test 1: Startup returns 503 when neither model has completed initialization."""
    reset_model_cache()
    response = test_client.get("/health/startup")
    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "DOWN"
    assert data["service"] == "ml-serving"
    assert data["model_loaded"] is False
    assert data["model_name"] is None
    assert data["model_version"] is None
    assert "Startup initialization in progress" in data["detail"]


def test_startup_probe_returns_503_when_only_purchase_initialized(test_client: TestClient) -> None:
    """Test 2: Startup returns 503 when only purchase model is initialized."""
    reset_model_cache()
    registry = get_model_registry()
    p_model = DummyPurchaseModel(version="p1")
    registry.set_model("purchase", p_model, "p1")
    registry.mark_startup_complete("purchase", True)
    # transfer family remains uninitialized

    response = test_client.get("/health/startup")
    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "DOWN"
    assert data["model_loaded"] is False
    assert "Startup initialization in progress" in data["detail"]


def test_startup_probe_returns_503_when_only_transfer_initialized(test_client: TestClient) -> None:
    """Test 3: Startup returns 503 when only transfer model is initialized."""
    reset_model_cache()
    registry = get_model_registry()
    t_model = DummyTransferModel(version="t1")
    registry.set_model("transfer", t_model, "t1")
    registry.mark_startup_complete("transfer", True)
    # purchase family remains uninitialized

    response = test_client.get("/health/startup")
    assert response.status_code == 503
    data = response.json()
    assert data["status"] == "DOWN"
    assert data["model_loaded"] is False
    assert "Startup initialization in progress" in data["detail"]


def test_startup_probe_returns_503_when_both_loaded_but_warmup_incomplete(test_client: TestClient) -> None:
    """
    Test 4: Startup returns 503 when both models are loaded in memory but warm-up has not
    completed for at least one model.
    Readiness probe must return 200 (models loaded in memory), while startup returns 503.
    """
    reset_model_cache()
    registry = get_model_registry()

    # Load both models into memory
    p_model = DummyPurchaseModel(version="p1")
    t_model = DummyTransferModel(version="t1")
    registry.set_model("purchase", p_model, "p1")
    registry.set_model("transfer", t_model, "t1")

    # Purchase completed signature and warm-up
    registry.mark_family_signature_validated("purchase", True)
    registry.mark_family_warmed_up("purchase", True)

    # Transfer completed signature validation but warm-up is still incomplete
    registry.mark_family_signature_validated("transfer", True)
    registry.mark_family_warmed_up("transfer", False)

    # 1. Readiness probe is UP (both models are loaded in memory)
    ready_resp = test_client.get("/health/ready")
    assert ready_resp.status_code == 200
    ready_data = ready_resp.json()
    assert ready_data["status"] == "UP"
    assert ready_data["model_loaded"] is True

    # 2. Startup probe is DOWN (warm-up incomplete for transfer)
    startup_resp = test_client.get("/health/startup")
    assert startup_resp.status_code == 503
    startup_data = startup_resp.json()
    assert startup_data["status"] == "DOWN"
    assert startup_data["model_loaded"] is True
    assert "Startup initialization in progress" in startup_data["detail"]


def test_startup_probe_returns_503_when_signature_or_warmup_fails(test_client: TestClient) -> None:
    """Test 5: Startup returns 503 if signature validation or warm-up fails for either model."""
    reset_model_cache()
    registry = get_model_registry()

    # Case A: Corrupted model missing predict/predict_proba fails signature validation
    class CorruptedModel:
        pass

    corrupted = CorruptedModel()
    with pytest.raises(ValueError, match="does not expose a valid prediction interface"):
        validate_model_signature(corrupted, "purchase")

    # Case B: Model failing during warm-up inference raises RuntimeError
    class BrokenWarmupModel:
        def predict_proba(self, X):
            raise RuntimeError("Kernel crashed during warm-up matrix multiplication")

    broken = BrokenWarmupModel()
    with pytest.raises(RuntimeError, match="Warm-up inference failed"):
        warmup_model(broken, "transfer")

    # Registry tracks failure -> startup returns 503
    registry.record_failure("purchase", "ValueError: signature invalid")
    response = test_client.get("/health/startup")
    assert response.status_code == 503
    assert response.json()["status"] == "DOWN"


def test_startup_probe_returns_200_when_both_models_fully_initialized(test_client: TestClient) -> None:
    """Test 6: Startup returns 200 only after both models have completed loading, signature validation, and warm-up."""
    reset_model_cache()
    registry = get_model_registry()

    p_model = DummyPurchaseModel(version="p1")
    t_model = DummyTransferModel(version="t1")

    # Full 4-step sequence complete for purchase
    validate_model_signature(p_model, "purchase")
    warmup_model(p_model, "purchase")
    registry.set_model("purchase", p_model, "p1")
    registry.mark_startup_complete("purchase", True)

    # Full 4-step sequence complete for transfer
    validate_model_signature(t_model, "transfer")
    warmup_model(t_model, "transfer")
    registry.set_model("transfer", t_model, "t1")
    registry.mark_startup_complete("transfer", True)

    response = test_client.get("/health/startup")
    assert response.status_code == 200
    data = response.json()
    assert data["status"] == "UP"
    assert data["service"] == "ml-serving"
    assert data["model_loaded"] is True
    assert data["model_name"] == "fraud-model"
    assert data["model_version"] == "p1"
    assert "Service startup complete" in data["detail"]


def test_startup_probe_response_structure_matches_health_schema(test_client: TestClient) -> None:
    """Test 7: Startup response structure strictly conforms to the HealthResponse contract."""
    # 1. Validate uninitialized (503 DOWN) response against schema
    reset_model_cache()
    resp_uninit = test_client.get("/health/startup")
    assert resp_uninit.status_code == 503
    parsed_uninit = HealthResponse.model_validate(resp_uninit.json())
    assert parsed_uninit.status == "DOWN"
    assert parsed_uninit.model_loaded is False

    # 2. Validate fully initialized (200 UP) response against schema
    dummy = DummyFraudModel(version="2")
    set_cached_model(dummy, "2")
    resp_init = test_client.get("/health/startup")
    assert resp_init.status_code == 200
    parsed_init = HealthResponse.model_validate(resp_init.json())
    assert parsed_init.status == "UP"
    assert parsed_init.model_loaded is True
    assert parsed_init.model_version == "2"
