"""
test_config.py — Unit tests for configuration and dummy-model security protection.
"""

import os
import pytest
from config import Settings, get_settings


def test_default_configuration_settings(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("APP_ENV", raising=False)
    monkeypatch.delenv("USE_DUMMY_MODEL", raising=False)
    monkeypatch.delenv("ML_MODEL_NAME", raising=False)
    monkeypatch.delenv("ML_DECISION_THRESHOLD", raising=False)
    monkeypatch.delenv("ML_PURCHASE_MODEL_NAME", raising=False)
    monkeypatch.delenv("ML_TRANSFER_MODEL_NAME", raising=False)

    settings = Settings()
    assert settings.APP_NAME == "ml-serving"
    assert settings.APP_ENV == "local"
    # Design 2 Dual Model Defaults
    assert settings.ML_PURCHASE_MODEL_NAME == "purchase-fraud-model"
    assert settings.ML_PURCHASE_MODEL_ALIAS == "champion"
    assert settings.ML_TRANSFER_MODEL_NAME == "transfer-fraud-model"
    assert settings.ML_TRANSFER_MODEL_ALIAS == "champion"
    assert settings.get_model_name_for_family("purchase") == "purchase-fraud-model"
    assert settings.get_model_name_for_family("transfer") == "transfer-fraud-model"
    assert settings.get_model_alias_for_family("purchase") == "champion"
    assert settings.get_model_alias_for_family("transfer") == "champion"
    # Legacy fallback
    assert settings.ML_MODEL_NAME == "fraud-model"
    assert settings.ML_MODEL_ALIAS == "champion"
    assert settings.ML_DECISION_THRESHOLD == 0.1948
    assert settings.USE_DUMMY_MODEL is False


def test_custom_family_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ML_PURCHASE_MODEL_NAME", "custom-purchase-model")
    monkeypatch.setenv("ML_PURCHASE_MODEL_ALIAS", "candidate")
    monkeypatch.setenv("ML_TRANSFER_MODEL_NAME", "custom-transfer-model")
    monkeypatch.setenv("ML_TRANSFER_MODEL_ALIAS", "v2")

    settings = Settings()
    assert settings.get_model_name_for_family("purchase") == "custom-purchase-model"
    assert settings.get_model_alias_for_family("purchase") == "candidate"
    assert settings.get_model_name_for_family("transfer") == "custom-transfer-model"
    assert settings.get_model_alias_for_family("transfer") == "v2"


def test_unknown_family_raises_value_error() -> None:
    settings = Settings()
    with pytest.raises(ValueError, match="Unknown model family 'unknown'"):
        settings.get_model_name_for_family("unknown")
    with pytest.raises(ValueError, match="Unknown model family 'unknown'"):
        settings.get_model_alias_for_family("unknown")


def test_dummy_model_allowed_in_local_and_test(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    # Allowed in 'test'
    monkeypatch.setenv("APP_ENV", "test")
    settings_test = Settings()
    assert settings_test.USE_DUMMY_MODEL is True

    # Allowed in 'local'
    monkeypatch.setenv("APP_ENV", "local")
    settings_local = Settings()
    assert settings_local.USE_DUMMY_MODEL is True


def test_dummy_model_strictly_forbidden_in_production(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")
    monkeypatch.setenv("APP_ENV", "production")

    with pytest.raises(ValueError, match="Security violation: USE_DUMMY_MODEL cannot be enabled"):
        Settings()


def test_dummy_model_strictly_forbidden_in_staging(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")
    monkeypatch.setenv("APP_ENV", "staging")

    with pytest.raises(ValueError, match="Security violation: USE_DUMMY_MODEL cannot be enabled"):
        Settings()


def test_fast_fail_settings_propagation(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("MLFLOW_HTTP_REQUEST_MAX_RETRIES", "2")
    monkeypatch.setenv("MLFLOW_HTTP_REQUEST_TIMEOUT", "5")

    settings = Settings()
    assert settings.MLFLOW_HTTP_REQUEST_MAX_RETRIES == 2
    assert settings.MLFLOW_HTTP_REQUEST_TIMEOUT == 5
    assert os.environ["MLFLOW_HTTP_REQUEST_MAX_RETRIES"] == "2"
    assert os.environ["MLFLOW_HTTP_REQUEST_TIMEOUT"] == "5"


def test_admin_api_key_configuration(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("ADMIN_API_KEY", "custom-secret-key")
    settings = Settings()
    assert settings.ADMIN_API_KEY == "custom-secret-key"

    # Fallback to ML_ADMIN_API_KEY
    monkeypatch.delenv("ADMIN_API_KEY", raising=False)
    monkeypatch.setenv("ML_ADMIN_API_KEY", "fallback-secret-key")
    settings_fallback = Settings()
    assert settings_fallback.ADMIN_API_KEY == "fallback-secret-key"
