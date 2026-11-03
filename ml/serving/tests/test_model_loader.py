"""
test_model_loader.py — Tests for multi-model registry, background loading, and exponential backoff.
"""

import time
import pytest
from unittest.mock import MagicMock, patch

from model_loader import (
    DummyFraudModel,
    DummyPurchaseModel,
    DummyTransferModel,
    ModelEntry,
    ModelRegistry,
    create_dummy_model,
    get_loaded_model,
    get_loaded_model_version,
    get_model_registry,
    get_sample_features_for_family,
    get_tracking_uri,
    is_model_loaded,
    load_all_models,
    load_champion_model,
    load_family_model,
    reset_model_cache,
    set_cached_model,
    start_background_model_loader,
    stop_background_model_loader,
    validate_model_signature,
    warmup_model,
)


def test_dummy_models_functionality() -> None:
    purchase_dummy = DummyPurchaseModel(version="p-v1")
    transfer_dummy = DummyTransferModel(version="t-v1")

    assert purchase_dummy.family == "purchase"
    assert purchase_dummy.version == "p-v1"
    assert transfer_dummy.family == "transfer"
    assert transfer_dummy.version == "t-v1"

    # Multi-sample predict_proba
    probas = purchase_dummy.predict_proba([[1, 2, 3], [4, 5, 6]])
    assert probas.shape == (2, 2)
    assert probas[0][0] == 0.85
    assert probas[0][1] == 0.15

    preds = transfer_dummy.predict([[1, 2, 3], [4, 5, 6]])
    assert list(preds) == [0, 0]

    # Factory
    created_p = create_dummy_model("purchase")
    assert isinstance(created_p, DummyPurchaseModel)
    created_t = create_dummy_model("transfer")
    assert isinstance(created_t, DummyTransferModel)


def test_registry_independent_family_tracking() -> None:
    registry = ModelRegistry()

    # Initial state
    assert registry.is_family_loaded("purchase") is False
    assert registry.is_family_loaded("transfer") is False
    assert registry.are_all_models_loaded() is False

    # Load purchase only
    p_dummy = DummyPurchaseModel()
    registry.set_model("purchase", p_dummy, p_dummy.version)

    assert registry.is_family_loaded("purchase") is True
    assert registry.is_family_loaded("transfer") is False
    assert registry.are_all_models_loaded() is False
    assert registry.get_model("purchase") is p_dummy
    assert registry.get_version("purchase") == p_dummy.version
    assert registry.get_model("transfer") is None

    # Load transfer
    t_dummy = DummyTransferModel()
    registry.set_model("transfer", t_dummy, t_dummy.version)

    assert registry.is_family_loaded("purchase") is True
    assert registry.is_family_loaded("transfer") is True
    assert registry.are_all_models_loaded() is True
    assert registry.get_model("transfer") is t_dummy
    assert registry.get_version("transfer") == t_dummy.version


def test_registry_unknown_family_raises_error() -> None:
    registry = ModelRegistry()
    with pytest.raises(ValueError, match="Unknown model family 'invalid'"):
        registry.get_entry("invalid")
    with pytest.raises(ValueError, match="Unknown model family 'invalid'"):
        registry.is_family_loaded("invalid")
    with pytest.raises(ValueError, match="Unknown model family 'invalid'"):
        registry.set_model("invalid", DummyFraudModel(), "1")


def test_registry_active_model_preserved_on_failure() -> None:
    registry = ModelRegistry()
    original_dummy = DummyPurchaseModel(version="original-v1")
    registry.set_model("purchase", original_dummy, "original-v1")

    assert registry.get_version("purchase") == "original-v1"
    assert registry.is_family_loaded("purchase") is True

    # Simulate a replacement load failure
    registry.record_failure("purchase", "ConnectionError: MLflow server down")

    # Active model MUST be preserved and not unloaded
    assert registry.is_family_loaded("purchase") is True
    assert registry.get_model("purchase") is original_dummy
    assert registry.get_version("purchase") == "original-v1"
    assert registry.get_load_attempts("purchase") == 1
    assert "ConnectionError" in registry.get_last_error("purchase")


def test_registry_isolated_failures_between_families() -> None:
    registry = ModelRegistry()
    p_dummy = DummyPurchaseModel(version="p-v1")
    registry.set_model("purchase", p_dummy, "p-v1")

    # Record failure on transfer
    registry.record_failure("transfer", "RestException: 404 model not found")

    # Purchase state must remain completely unaffected
    assert registry.is_family_loaded("purchase") is True
    assert registry.get_model("purchase") is p_dummy
    assert registry.get_load_attempts("purchase") == 0

    # Transfer tracks its own failure
    assert registry.is_family_loaded("transfer") is False
    assert registry.get_load_attempts("transfer") == 1
    assert registry.get_loading_status("transfer") == "FAILED"


def test_registry_selective_reset() -> None:
    registry = ModelRegistry()
    registry.set_model("purchase", DummyPurchaseModel(), "p1")
    registry.set_model("transfer", DummyTransferModel(), "t1")
    assert registry.are_all_models_loaded() is True

    # Reset purchase only
    registry.reset("purchase")
    assert registry.is_family_loaded("purchase") is False
    assert registry.is_family_loaded("transfer") is True

    # Reset all
    registry.reset()
    assert registry.is_family_loaded("transfer") is False
    assert registry.are_all_models_loaded() is False


def test_load_family_model_with_dummy_flag(monkeypatch: pytest.MonkeyPatch) -> None:
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    p_model, p_version = load_family_model("purchase")
    assert isinstance(p_model, DummyPurchaseModel)
    assert p_version == "dummy-purchase-v1"
    assert is_model_loaded("purchase") is True
    assert is_model_loaded("transfer") is False
    assert is_model_loaded() is False  # are_all_models_loaded() is False

    t_model, t_version = load_family_model("transfer")
    assert isinstance(t_model, DummyTransferModel)
    assert t_version == "dummy-transfer-v1"
    assert is_model_loaded("transfer") is True
    assert is_model_loaded() is True  # are_all_models_loaded() is now True


def test_background_loader_with_dummy_models(monkeypatch: pytest.MonkeyPatch) -> None:
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    start_background_model_loader()

    # Wait up to 1 second for thread to execute
    for _ in range(20):
        if is_model_loaded():
            break
        time.sleep(0.05)

    assert is_model_loaded() is True
    assert is_model_loaded("purchase") is True
    assert is_model_loaded("transfer") is True
    stop_background_model_loader()


def test_background_loader_exponential_backoff_on_failure(monkeypatch: pytest.MonkeyPatch) -> None:
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "false")
    monkeypatch.setenv("MODEL_LOAD_INITIAL_BACKOFF_SEC", "0.02")
    monkeypatch.setenv("MODEL_LOAD_MAX_BACKOFF_SEC", "0.05")

    # Mock load_family_model: purchase succeeds immediately, transfer fails twice then recovers
    transfer_call_count = 0

    def mock_load(family: str, tracking_uri=None) -> tuple:
        nonlocal transfer_call_count
        if family == "purchase":
            dummy = DummyPurchaseModel(version="p-recovered-v1")
            get_model_registry().set_model("purchase", dummy, "p-recovered-v1")
            return dummy, "p-recovered-v1"
        elif family == "transfer":
            transfer_call_count += 1
            if transfer_call_count < 3:
                raise ConnectionError("Mock MLflow unreachable for transfer")
            dummy = DummyTransferModel(version="t-recovered-v1")
            get_model_registry().set_model("transfer", dummy, "t-recovered-v1")
            return dummy, "t-recovered-v1"
        raise ValueError(f"Unknown family: {family}")

    with patch("model_loader.load_family_model", side_effect=mock_load):
        start_background_model_loader()

        for _ in range(30):
            if is_model_loaded():
                break
            time.sleep(0.05)

        assert is_model_loaded() is True
        assert is_model_loaded("purchase") is True
        assert is_model_loaded("transfer") is True
        assert transfer_call_count >= 3
        stop_background_model_loader()


def test_validate_model_signature_edge_cases() -> None:
    # 1. Model is None
    with pytest.raises(ValueError, match="Model artifact for family 'purchase' is None."):
        validate_model_signature(None, "purchase")

    # 2. Model lacks predict and predict_proba
    class InvalidModel:
        pass

    with pytest.raises(ValueError, match="does not expose a valid prediction interface"):
        validate_model_signature(InvalidModel(), "purchase")

    # 3. Model has MLflow signature with empty inputs list
    class ModelWithEmptySignature:
        def __init__(self) -> None:
            class Sig:
                inputs: list = []

            class Meta:
                signature = Sig()

            self.metadata = Meta()

        def predict_proba(self, X):
            return [[0.5, 0.5]]

    with pytest.raises(ValueError, match="contains empty input schema"):
        validate_model_signature(ModelWithEmptySignature(), "purchase")


def test_warmup_model_edge_cases() -> None:
    # 1. Model is None
    with pytest.raises(ValueError, match="Cannot warm up None model for family 'purchase'."):
        warmup_model(None, "purchase")

    # 2. Model only exposes predict() (no predict_proba)
    class PredictOnlyModel:
        def predict(self, X):
            import numpy as np
            return np.zeros(len(X), dtype=int)

    # Should warm up successfully without exception
    warmup_model(PredictOnlyModel(), "purchase")

    # 3. Model whose inference raises exception during warm-up
    class FailingModel:
        def predict_proba(self, X):
            raise RuntimeError("Inference graph crash")

        def predict(self, X):
            raise RuntimeError("Inference graph crash")

    with pytest.raises(RuntimeError, match="Warm-up inference failed for model family 'purchase'"):
        warmup_model(FailingModel(), "purchase")


def test_mlflow_model_loading_success_and_fallbacks(monkeypatch: pytest.MonkeyPatch) -> None:
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "false")

    mock_model = DummyPurchaseModel(version="mlflow-v42")
    mock_version_obj = MagicMock()
    mock_version_obj.version = "42"

    mock_client = MagicMock()
    mock_client.get_model_version_by_alias.return_value = mock_version_obj

    # 1. Successful sklearn load
    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", return_value=mock_model) as mock_sk_load:
        loaded, ver = load_family_model("purchase")
        assert loaded is mock_model
        assert ver == "42"
        assert is_model_loaded("purchase") is True
        mock_sk_load.assert_called_once_with("models:/purchase-fraud-model@champion")

    # 2. Pyfunc fallback when sklearn loader fails
    reset_model_cache()
    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", side_effect=Exception("sklearn loader incompatibility")), \
         patch("mlflow.pyfunc.load_model", return_value=mock_model) as mock_pyfunc_load:
        loaded, ver = load_family_model("purchase")
        assert loaded is mock_model
        assert ver == "42"
        mock_pyfunc_load.assert_called_once_with("models:/purchase-fraud-model@champion")

    # 3. Downloaded model is None
    reset_model_cache()
    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", return_value=None), \
         patch("mlflow.pyfunc.load_model", return_value=None):
        with pytest.raises(ValueError, match="Model artifact downloaded for family 'purchase' is None."):
            load_family_model("purchase")

    # 4. Resolved model version is empty
    reset_model_cache()
    empty_version_obj = MagicMock()
    empty_version_obj.version = ""
    mock_client_empty = MagicMock()
    mock_client_empty.get_model_version_by_alias.return_value = empty_version_obj
    with patch("model_loader.MlflowClient", return_value=mock_client_empty), \
         patch("mlflow.sklearn.load_model", return_value=mock_model):
        with pytest.raises(ValueError, match="Resolved model version for family 'purchase' is empty."):
            load_family_model("purchase")


def test_load_all_models_helper(monkeypatch: pytest.MonkeyPatch) -> None:
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    results = load_all_models()
    assert "purchase" in results
    assert "transfer" in results
    assert results["purchase"][1] == "dummy-purchase-v1"
    assert results["transfer"][1] == "dummy-transfer-v1"


def test_model_registry_and_helpers_coverage_branches() -> None:
    registry = ModelRegistry()

    # Generic dummy factory fallback
    generic_dummy = create_dummy_model("generic")
    assert generic_dummy.family == "generic"

    # Sample features fallback for unknown family
    sample = get_sample_features_for_family("custom")
    assert sample == {"amount": 100.0, "hour_of_day": 12}

    # Tracking URI helper
    uri = get_tracking_uri()
    assert isinstance(uri, str)

    # set_entry family mismatch
    entry = ModelEntry(
        family="transfer",
        model_name="t",
        model_version="1",
        model_alias="champion",
        model=DummyTransferModel(),
    )
    with pytest.raises(ValueError, match="Entry family 'transfer' does not match target family 'purchase'."):
        registry.set_entry("purchase", entry)

    # get_status_summary
    summary = registry.get_status_summary()
    assert "purchase" in summary
    assert "transfer" in summary

    # Legacy helper wrappers
    reset_model_cache()
    p_dummy = DummyPurchaseModel(version="leg-1")
    set_cached_model(p_dummy, "leg-1")
    assert get_loaded_model_version() == "leg-1"
    assert get_loaded_model() is p_dummy

