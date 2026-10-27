"""
test_model_loader.py — Tests for multi-model registry, background loading, and exponential backoff.
"""

import time
import pytest
from unittest.mock import patch

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
    is_model_loaded,
    load_all_models,
    load_champion_model,
    load_family_model,
    reset_model_cache,
    set_cached_model,
    start_background_model_loader,
    stop_background_model_loader,
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
