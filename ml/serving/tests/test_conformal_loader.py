"""
test_conformal_loader.py — Automated Unit & Integration Tests for MLflow Conformal Loader.

Covers all requirements from Task M2 — MLflow CONFORMAL LOADER READINESS:
1. Valid configuration via artifact.
2. Valid configuration via tags fallback.
3. Valid configuration for Transfer family.
4. Valid configuration with infinite quantile (+inf).
5. Absent artifact and missing metadata.
6. Empty artifact / metadata missing required fields.
7. Incompatible model version.
8. Incompatible FeatureSpec version.
9. Incompatible model family.
10. Invalid quantiles (negative, NaN, boolean, rank mismatch).
11. MLflow download failure (network timeout / exception).
12. Non-blocking guarantee: Conformal failure never renders a valid champion model unavailable.
13. Independent reload: Purchase / Transfer conformal state remains strictly decoupled.
14. Production protection: No synthetic quantiles are fabricated or activated when unconfigured.
"""

from __future__ import annotations

import json
import math
import os
import tempfile
from typing import Any, Dict, Optional
from unittest.mock import MagicMock, patch
import uuid

import pytest
from fastapi.testclient import TestClient

from conformal_config import ConformalConfig, ConformalConfigValidator
from conformal_evaluator import ConformalEvaluator
from conformal_loader import (
    ConformalLoadResult,
    ConformalLoadStatus,
    MLflowClientProtocol,
    MLflowConformalAdapter,
)
from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    SAMPLE_PURCHASE_FEATURES,
    SAMPLE_TRANSFER_FEATURES,
    get_model_registry,
    load_family_model,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import PredictionResponse


# ---------------------------------------------------------------------
# Mock MLflow Classes
# ---------------------------------------------------------------------

class MockModelVersion:
    """Mock MLflow ModelVersion object."""

    def __init__(
        self,
        name: str = "purchase-fraud-model",
        version: str = "1",
        run_id: str = "mock-run-001",
        tags: Optional[Dict[str, str]] = None,
    ) -> None:
        self.name = name
        self.version = version
        self.run_id = run_id
        self.tags = tags or {}


class MockRunData:
    """Mock MLflow RunData."""

    def __init__(self, tags: Optional[Dict[str, str]] = None) -> None:
        self.tags = tags or {}


class MockRun:
    """Mock MLflow Run."""

    def __init__(self, run_id: str = "mock-run-001", tags: Optional[Dict[str, str]] = None) -> None:
        self.run_id = run_id
        self.data = MockRunData(tags=tags)


class MockMLflowClient:
    """
    In-memory mock MLflow tracking client implementing MLflowClientProtocol.
    Permits simulating artifact files, tags, network timeouts, and missing resources.
    """

    def __init__(self, temp_dir: str) -> None:
        self.temp_dir = temp_dir
        self.artifacts: Dict[tuple[str, str], str] = {}
        self.model_versions: Dict[tuple[str, str], MockModelVersion] = {}
        self.runs: Dict[str, MockRun] = {}
        self.download_exception: Optional[Exception] = None

    def add_artifact(self, run_id: str, path: str, content: str) -> str:
        """Writes content into a temporary file and registers it for the run."""
        dest_path = os.path.join(self.temp_dir, f"{run_id}_{path.replace('/', '_')}")
        with open(dest_path, "w", encoding="utf-8") as f:
            f.write(content)
        self.artifacts[(run_id, path)] = dest_path
        return dest_path

    def register_model_version(
        self,
        name: str,
        alias: str,
        version: str = "1",
        run_id: str = "mock-run-001",
        tags: Optional[Dict[str, str]] = None,
    ) -> MockModelVersion:
        mv = MockModelVersion(name=name, version=version, run_id=run_id, tags=tags)
        self.model_versions[(name, alias)] = mv
        self.runs[run_id] = MockRun(run_id=run_id, tags=tags)
        return mv

    def download_artifacts(self, run_id: str, path: str, dst_path: Optional[str] = None) -> str:
        if self.download_exception is not None:
            raise self.download_exception
        key = (run_id, path)
        if key in self.artifacts:
            return self.artifacts[key]
        raise FileNotFoundError(f"RESOURCE_DOES_NOT_EXIST: Artifact '{path}' not found for run '{run_id}'.")

    def get_model_version_by_alias(self, name: str, alias: str) -> MockModelVersion:
        key = (name, alias)
        if key in self.model_versions:
            return self.model_versions[key]
        raise ValueError(f"Model version not found for alias '{alias}' on model '{name}'.")

    def get_run(self, run_id: str) -> MockRun:
        if run_id in self.runs:
            return self.runs[run_id]
        return MockRun(run_id=run_id, tags={})


# ---------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------

@pytest.fixture
def temp_dir() -> str:
    with tempfile.TemporaryDirectory() as td:
        yield td


@pytest.fixture
def mock_client(temp_dir: str) -> MockMLflowClient:
    return MockMLflowClient(temp_dir=temp_dir)


@pytest.fixture
def adapter() -> MLflowConformalAdapter:
    return MLflowConformalAdapter()


@pytest.fixture
def valid_purchase_dict() -> Dict[str, Any]:
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
        "calibration_dataset_id": "ds_purchase_cal_2026_v1",
        "metadata": {"created_by": "M1"},
    }


@pytest.fixture
def valid_transfer_dict() -> Dict[str, Any]:
    return {
        "family": "transfer",
        "model_name": "transfer-fraud-model",
        "model_version": "1",
        "feature_spec_version": "transfer-v2",
        "method": "split_conformal",
        "alpha": 0.05,
        "quantile": 0.48,
        "n": 500,
        "k": 476,
        "calibration_dataset_id": "ds_transfer_cal_2026_v1",
        "metadata": {"created_by": "M1"},
    }


# =====================================================================
# Unit Tests: Adapter Isolation & Matrix of Cases
# =====================================================================

def test_load_conformal_valid_artifact(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """Case 1: Valid configuration loaded from MLflow JSON artifact."""
    run_id = "run-purchase-valid"
    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(valid_purchase_dict))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.LOADED
    assert res.is_success is True
    assert res.evaluator is not None
    assert res.config is not None
    assert res.config.quantile == 0.54
    assert res.evaluator.default_quantile == 0.54
    assert res.source == "artifact:conformal_config.json"


def test_load_conformal_valid_tags_fallback(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """Case 2: Fallback to JSON tag when artifact is absent."""
    run_id = "run-purchase-tags"
    tags = {"conformal_config": json.dumps(valid_purchase_dict)}
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id, tags=tags)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.LOADED
    assert res.is_success is True
    assert res.config is not None
    assert res.config.quantile == 0.54
    assert res.source == "tags"


def test_load_conformal_valid_individual_tags(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
) -> None:
    """Case 2b: Fallback to individual prefixed tags when artifact is absent."""
    run_id = "run-individual-tags"
    tags = {
        "conformal_family": "purchase",
        "conformal_model_name": "purchase-fraud-model",
        "conformal_model_version": "1",
        "conformal_feature_spec_version": "purchase-v2",
        "conformal_method": "split_conformal",
        "conformal_alpha": "0.05",
        "conformal_quantile": "0.54",
        "conformal_n": "1000",
        "conformal_k": "951",
        "conformal_calibration_dataset_id": "ds_cal_01",
    }
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id, tags=tags)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.LOADED
    assert res.is_success is True
    assert res.config is not None
    assert res.config.alpha == 0.05
    assert res.config.quantile == 0.54
    assert res.config.k == 951


def test_load_conformal_transfer_valid(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_transfer_dict: Dict[str, Any],
) -> None:
    """Case 3: Valid Transfer model configuration."""
    run_id = "run-transfer-valid"
    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(valid_transfer_dict))
    mv = mock_client.register_model_version("transfer-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="transfer",
        model_name="transfer-fraud-model",
        model_version="1",
        feature_spec_version="transfer-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.LOADED
    assert res.is_success is True
    assert res.config is not None
    assert res.config.family == "transfer"
    assert res.config.quantile == 0.48


def test_load_conformal_infinite_quantile_valid(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """Case 4: Valid infinite quantile (+infinity) when k > n."""
    run_id = "run-inf"
    cfg = dict(valid_purchase_dict)
    cfg["n"] = 10
    cfg["k"] = 11  # ceil(11 * 0.95) = 11 > 10 -> requires q = +inf
    cfg["quantile"] = "Infinity"

    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(cfg))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.LOADED
    assert res.config is not None
    assert math.isinf(res.config.quantile)
    assert res.evaluator is not None
    assert math.isinf(res.evaluator.default_quantile)


def test_load_conformal_artifact_absent(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
) -> None:
    """Case 5: Artifact absent and no metadata tags present."""
    run_id = "run-empty"
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id, tags={})

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status in (ConformalLoadStatus.ARTIFACT_NOT_FOUND, ConformalLoadStatus.NOT_CONFIGURED)
    assert res.evaluator is None
    assert res.config is None
    assert res.error_message is not None


def test_load_conformal_metadata_missing(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
) -> None:
    """Case 6: Artifact exists but is empty or missing required attributes."""
    run_id = "run-missing-fields"
    # Artifact with missing alpha, n, k, quantile
    partial_config = {"family": "purchase", "model_name": "purchase-fraud-model"}
    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(partial_config))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.METADATA_MISSING
    assert res.evaluator is None
    assert "Missing required" in (res.error_message or "")


def test_load_conformal_incompatible_model_version(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """Case 7: Configuration model_version does not match active champion model version."""
    run_id = "run-version-mismatch"
    cfg = dict(valid_purchase_dict)
    cfg["model_version"] = "99"  # Active model is version "1"

    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(cfg))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.INCOMPATIBLE_VERSION
    assert res.evaluator is None
    assert "model_version" in (res.error_message or "")


def test_load_conformal_incompatible_feature_spec(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """Case 8: Configuration feature_spec_version does not match expected active spec."""
    run_id = "run-spec-mismatch"
    cfg = dict(valid_purchase_dict)
    cfg["feature_spec_version"] = "purchase-v1"  # Active is purchase-v2

    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(cfg))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.INCOMPATIBLE_SPEC
    assert res.evaluator is None
    assert "feature_spec_version" in (res.error_message or "")


def test_load_conformal_incompatible_family(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_transfer_dict: Dict[str, Any],
) -> None:
    """Case 9: Transfer configuration attempted on Purchase family."""
    run_id = "run-family-mismatch"
    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(valid_transfer_dict))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.INCOMPATIBLE_FAMILY
    assert res.evaluator is None


@pytest.mark.parametrize(
    "invalid_quantile",
    [-0.1, -1.0, float("nan"), True, False, "invalid_str", 1.5],
)
def test_load_conformal_invalid_quantile(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
    invalid_quantile: Any,
) -> None:
    """Case 10: Reject invalid quantiles (negative, NaN, boolean, out-of-bounds)."""
    run_id = f"run-bad-q-{hash(str(invalid_quantile))}"
    cfg = dict(valid_purchase_dict)
    cfg["quantile"] = invalid_quantile

    mock_client.add_artifact(run_id, "conformal_config.json", json.dumps(cfg))
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status in (ConformalLoadStatus.INVALID_QUANTILE, ConformalLoadStatus.INVALID_CONFIG)
    assert res.evaluator is None


def test_load_conformal_download_failure(
    adapter: MLflowConformalAdapter,
    mock_client: MockMLflowClient,
) -> None:
    """Case 11: Network timeout / connection error during MLflow download."""
    run_id = "run-network-fail"
    mock_client.download_exception = RuntimeError("Connection timed out after 3000ms")
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    res = adapter.load_conformal(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v2",
        client=mock_client,
        model_version_meta=mv,
    )

    assert res.status == ConformalLoadStatus.DOWNLOAD_FAILED
    assert res.evaluator is None
    assert "timed out" in (res.error_message or "")


# =====================================================================
# Integration Tests: Non-Blocking, Independent Reload & Production Rules
# =====================================================================

def test_conformal_failure_does_not_block_model_loading(
    mock_client: MockMLflowClient,
    temp_dir: str,
) -> None:
    """
    Requirement 6: A Conformal failure (e.g. corrupt config or download timeout)
    NEVER prevents the champion prediction model from being registered and ready.
    """
    stop_background_model_loader()
    reset_model_cache()

    run_id = "run-corrupt-conf"
    # Corrupt JSON artifact
    mock_client.add_artifact(run_id, "conformal_config.json", "{corrupt json")
    mv = mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    registry = get_model_registry()

    # Simulate MLflow model load flow with our mock client
    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", return_value=DummyPurchaseModel(version="1")):
        model, version = load_family_model("purchase")

    assert model is not None
    assert version == "1"
    assert registry.is_family_loaded("purchase") is True

    entry = registry.get_entry("purchase")
    assert entry is not None
    assert entry.model is not None
    assert entry.is_conformal_available is False
    assert entry.conformal_evaluator is None
    assert entry.conformal_error is not None
    assert "malformed JSON" in entry.conformal_error


def test_independent_purchase_transfer_conformal_loading(
    mock_client: MockMLflowClient,
    valid_purchase_dict: Dict[str, Any],
) -> None:
    """
    Requirement 7: Purchase and Transfer remain strictly decoupled.
    Purchase loads valid conformal, Transfer fails or has no conformal.
    Purchase is conformal-ready; Transfer serves standard predictions without conformal.
    """
    stop_background_model_loader()
    reset_model_cache()

    # Purchase: valid artifact
    p_run = "run-purchase-ok"
    mock_client.add_artifact(p_run, "conformal_config.json", json.dumps(valid_purchase_dict))
    mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=p_run)

    # Transfer: no artifact, no tags
    t_run = "run-transfer-empty"
    mock_client.register_model_version("transfer-fraud-model", "champion", version="1", run_id=t_run)

    registry = get_model_registry()

    def mock_load(uri: str) -> Any:
        if "purchase" in uri:
            return DummyPurchaseModel(version="1")
        return DummyTransferModel(version="1")

    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", side_effect=mock_load):
        p_model, _ = load_family_model("purchase")
        t_model, _ = load_family_model("transfer")

    p_entry = registry.get_entry("purchase")
    t_entry = registry.get_entry("transfer")

    assert p_entry is not None and p_entry.is_conformal_available is True
    assert p_entry.conformal_evaluator is not None
    assert p_entry.conformal_evaluator.default_quantile == 0.54

    assert t_entry is not None and t_entry.is_conformal_available is False
    assert t_entry.conformal_evaluator is None

    # Reload Transfer: Purchase conformal must remain intact
    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", side_effect=mock_load):
        load_family_model("transfer")

    p_entry_after = registry.get_entry("purchase")
    assert p_entry_after.is_conformal_available is True
    assert p_entry_after.conformal_evaluator.default_quantile == 0.54


def test_prediction_endpoint_with_conformal_adapter_failure(
    test_client: TestClient,
    mock_client: MockMLflowClient,
) -> None:
    """
    Requirement 6 (HTTP Contract): When Conformal fails during model load,
    the /predict endpoint returns HTTP 200 with prediction_set=None, conformal_status=None,
    and is_ambiguous=None. No 500 error is ever returned to the caller.
    """
    stop_background_model_loader()
    reset_model_cache()

    run_id = "run-conformal-network-error"
    mock_client.download_exception = ConnectionResetError("Connection reset by tracking server")
    mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", return_value=DummyPurchaseModel(version="1")):
        load_family_model("purchase")

    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v2",
        "features": dict(SAMPLE_PURCHASE_FEATURES),
    }

    response = test_client.post("/predict/purchase", json=payload)
    assert response.status_code == 200

    data = response.json()
    parsed = PredictionResponse(**data)
    assert parsed.fraud_probability == 0.15
    assert parsed.prediction_set is None
    assert parsed.conformal_status is None
    assert parsed.is_ambiguous is None


def test_no_synthetic_quantile_in_production(
    mock_client: MockMLflowClient,
) -> None:
    """
    Requirement 8: Ensure that when no conformal configuration is delivered by M1,
    the system does NOT synthesize or invent default quantiles.
    """
    stop_background_model_loader()
    reset_model_cache()

    run_id = "run-prod-unconfigured"
    mock_client.register_model_version("purchase-fraud-model", "champion", version="1", run_id=run_id)

    registry = get_model_registry()

    with patch("model_loader.MlflowClient", return_value=mock_client), \
         patch("mlflow.sklearn.load_model", return_value=DummyPurchaseModel(version="1")):
        load_family_model("purchase")

    entry = registry.get_entry("purchase")
    assert entry is not None
    # Strictly None — no synthetic 0.5 or fallback quantile
    assert entry.conformal_evaluator is None
    assert entry.conformal_config is None
    assert entry.is_conformal_available is False
