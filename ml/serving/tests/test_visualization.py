"""
test_visualization.py — Comprehensive unit and integration test suite for Data Visualization.

Design 2 / Final v4.2:
- Exclusively uses clearly identified synthetic test fixtures.
- Validates Pydantic v2 schemas, NaN/Inf rejection, and consistency rules.
- Validates strict domain isolation (Purchase vs Transfer).
- Validates confusion matrix error_type filtering and Split Conformal ambiguity.
- Validates in-memory pagination and page size limits.
- Validates thread-safe caching, lazy loading, and RFC 7807 problem details.
- Validates non-regression of existing prediction and health endpoints.
"""

import json
from pathlib import Path
import threading
from typing import Any, Dict, List
import uuid

import pytest
from fastapi.testclient import TestClient

from main import app
from visualization_loader import (
    VisualizationManager,
    get_visualization_manager,
    reset_visualization_cache,
)
from visualization_schemas import (
    ErrorType,
    VisualizationArtifact,
    VisualizationMetadata,
    VisualizationMethod,
    VisualizationPoint,
    VisualizationResponse,
)


# ==============================================================================
# Synthetic Test Fixtures (Design 2 / Final v4.2)
# ==============================================================================

def make_synthetic_point(
    idx: int,
    true_label: str = "legitimate",
    predicted_label: str = "legitimate",
    fraud_prob: float = 0.05,
    conformal_status: str = "LOADED",
    is_ambiguous: bool = False,
    is_outlier: bool = False,
    cluster_id: int = 1,
) -> Dict[str, Any]:
    """Helper creating a valid synthetic point matching consistency rules."""
    error_type = None
    if true_label == "fraud" and predicted_label == "fraud":
        error_type = "TP"
    elif true_label == "legitimate" and predicted_label == "legitimate":
        error_type = "TN"
    elif true_label == "legitimate" and predicted_label == "fraud":
        error_type = "FP"
    elif true_label == "fraud" and predicted_label == "legitimate":
        error_type = "FN"

    prediction_set = [predicted_label]
    if is_ambiguous:
        prediction_set = ["legitimate", "fraud"]

    return {
        "transaction_id": f"syn-tx-{idx:04d}",
        "x": float(idx * 0.1),
        "y": float(idx * -0.05),
        "true_label": true_label,
        "fraud_probability": fraud_prob,
        "predicted_label": predicted_label,
        "error_type": error_type,
        "prediction_set": prediction_set,
        "is_ambiguous": is_ambiguous,
        "conformal_status": conformal_status,
        "cluster_id": cluster_id,
        "is_outlier": is_outlier,
        "anomaly_score": -0.5 if is_outlier else 0.2,
    }


def make_synthetic_artifact(
    family: str = "purchase",
    method: str = "pca",
    n_points: int = 40,
) -> Dict[str, Any]:
    """Creates a complete synthetic artifact payload for tests."""
    points: List[Dict[str, Any]] = []
    for i in range(n_points):
        # 70% TN, 10% FP, 15% TP, 5% FN
        if i < 28:
            p = make_synthetic_point(i, "legitimate", "legitimate", fraud_prob=0.02, is_ambiguous=False, cluster_id=1)
        elif i < 32:
            p = make_synthetic_point(i, "legitimate", "fraud", fraud_prob=0.65, is_ambiguous=True, is_outlier=True, cluster_id=-1)
        elif i < 38:
            p = make_synthetic_point(i, "fraud", "fraud", fraud_prob=0.92, is_ambiguous=False, cluster_id=2)
        else:
            p = make_synthetic_point(i, "fraud", "legitimate", fraud_prob=0.08, is_ambiguous=True, is_outlier=True, cluster_id=-1)
        points.append(p)

    feature_spec = "purchase-v2" if family == "purchase" else "transfer-v2"
    model_name = f"{family}-fraud-model"
    threshold = 0.12 if family == "purchase" else 0.33

    return {
        "schema_version": "1.0",
        "family": family,
        "method": method,
        "dataset_name": "SyntheticTestSparkov" if family == "purchase" else "SyntheticTestPaySim",
        "dataset_split": "test",
        "sampling_strategy": "stratified_synthetic_fixture",
        "model_name": model_name,
        "model_version": "2",
        "feature_spec_version": feature_spec,
        "preprocessing_version": "v2",
        "evaluation_threshold": threshold,
        "threshold_source": "M1_EVALUATION",
        "generated_at": "2026-11-18T18:00:00Z",
        "explained_variance_ratio": [0.25, 0.15] if method == "pca" else None,
        "cumulative_variance_explained": 0.40 if method == "pca" else None,
        "fitted_split": "train_part_a" if method == "pca" else None,
        "perplexity": 30.0 if method == "tsne" else None,
        "learning_rate": "auto" if method == "tsne" else None,
        "max_iter": 1000 if method == "tsne" else None,
        "random_state": 42 if method == "tsne" else None,
        "points": points,
    }


@pytest.fixture(autouse=True)
def reset_visualization_state():
    """Ensures each test starts with a clean visualization cache."""
    reset_visualization_cache()
    yield
    reset_visualization_cache()


# ==============================================================================
# 1. Pydantic v2 Schema & Validator Tests
# ==============================================================================

def test_visualization_point_valid():
    """Validates that a correctly formed point passes Pydantic validation."""
    data = make_synthetic_point(1, "fraud", "fraud", 0.85)
    point = VisualizationPoint.model_validate(data)
    assert point.transaction_id == "syn-tx-0001"
    assert point.error_type == ErrorType.TP
    assert point.fraud_probability == 0.85
    assert point.prediction_set == ["fraud"]


@pytest.mark.parametrize("bad_val", [float("nan"), float("inf"), float("-inf")])
def test_visualization_point_rejects_nan_inf_coords(bad_val):
    """Guarantees x and y cannot be NaN or Inf."""
    data = make_synthetic_point(1)
    data["x"] = bad_val
    with pytest.raises(ValueError, match="Coordinates x and y must be finite numbers"):
        VisualizationPoint.model_validate(data)

    data = make_synthetic_point(1)
    data["y"] = bad_val
    with pytest.raises(ValueError, match="Coordinates x and y must be finite numbers"):
        VisualizationPoint.model_validate(data)


@pytest.mark.parametrize("bad_prob", [-0.1, 1.05, float("nan"), float("inf")])
def test_visualization_point_rejects_invalid_probability(bad_prob):
    """Guarantees fraud_probability is strictly within [0.0, 1.0] and finite."""
    data = make_synthetic_point(1)
    data["fraud_probability"] = bad_prob
    with pytest.raises(ValueError):
        VisualizationPoint.model_validate(data)


def test_visualization_point_rejects_empty_id():
    """Guarantees transaction_id cannot be empty."""
    data = make_synthetic_point(1)
    data["transaction_id"] = ""
    with pytest.raises(ValueError):
        VisualizationPoint.model_validate(data)


def test_visualization_point_rejects_inconsistent_error_type():
    """Guarantees error_type matches true_label and predicted_label."""
    # true='legitimate', pred='legitimate' -> should be TN, not TP
    data = make_synthetic_point(1, "legitimate", "legitimate")
    data["error_type"] = "TP"
    with pytest.raises(ValueError, match="Inconsistent error_type"):
        VisualizationPoint.model_validate(data)


def test_visualization_point_rejects_invalid_prediction_set():
    """Guarantees prediction_set only contains authorized labels and no duplicates."""
    data = make_synthetic_point(1)
    data["prediction_set"] = ["legitimate", "invalid_label"]
    with pytest.raises(ValueError, match="Invalid label 'invalid_label'"):
        VisualizationPoint.model_validate(data)

    data["prediction_set"] = ["legitimate", "legitimate"]
    with pytest.raises(ValueError, match="Duplicate elements detected"):
        VisualizationPoint.model_validate(data)


def test_visualization_point_conformal_unavailability_vs_ambiguity():
    """Guarantees distinction between conformal unavailability and ambiguity."""
    data = make_synthetic_point(1)
    data["conformal_status"] = "UNAVAILABLE"
    data["prediction_set"] = ["legitimate"]
    with pytest.raises(ValueError, match="prediction_set must be empty or None when conformal_status is 'UNAVAILABLE'"):
        VisualizationPoint.model_validate(data)

    data["prediction_set"] = None
    data["is_ambiguous"] = True
    with pytest.raises(ValueError, match="is_ambiguous cannot be True when conformal_status is 'UNAVAILABLE'"):
        VisualizationPoint.model_validate(data)


def test_artifact_rejects_duplicate_transaction_ids():
    """Guarantees transaction_id uniqueness within an artifact."""
    art_data = make_synthetic_artifact("purchase", "pca", n_points=5)
    # duplicate transaction ID
    art_data["points"][1]["transaction_id"] = art_data["points"][0]["transaction_id"]
    with pytest.raises(ValueError, match="Duplicate transaction_id detected in artifact"):
        VisualizationArtifact.model_validate(art_data)


def test_artifact_rejects_unsupported_umap():
    """Guarantees UMAP is rejected as non-operational."""
    art_data = make_synthetic_artifact("purchase", "pca", n_points=5)
    art_data["method"] = "umap"
    with pytest.raises(ValueError, match="UMAP is not currently operational or supported"):
        VisualizationArtifact.model_validate(art_data)


def test_artifact_rejects_cross_domain_spec_mismatch():
    """Guarantees purchase requires purchase-v2 and transfer requires transfer-v2."""
    art_data = make_synthetic_artifact("purchase", "pca", n_points=5)
    art_data["feature_spec_version"] = "transfer-v2"
    with pytest.raises(ValueError, match="feature_spec_version mismatch"):
        VisualizationArtifact.model_validate(art_data)


# ==============================================================================
# 2. Domain Isolation & Loader Tests
# ==============================================================================

def test_loader_cache_injection_and_isolation():
    """Verifies that purchase and transfer artifacts are strictly segregated in cache."""
    mgr = get_visualization_manager()

    p_art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=20))
    t_art = VisualizationArtifact.model_validate(make_synthetic_artifact("transfer", "pca", n_points=15))

    mgr.set_artifact("purchase", "pca", p_art)
    mgr.set_artifact("transfer", "pca", t_art)

    cached_p = mgr.get_artifact("purchase", "pca")
    cached_t = mgr.get_artifact("transfer", "pca")

    assert cached_p.family == "purchase"
    assert len(cached_p.points) == 20
    assert cached_t.family == "transfer"
    assert len(cached_t.points) == 15

    # Cross retrieval fails
    assert not mgr.is_cached("purchase", "tsne")


def test_loader_rejects_unsupported_umap_method():
    """Verifies that requesting UMAP triggers HTTP 404 METHOD_NOT_SUPPORTED."""
    mgr = get_visualization_manager()
    from visualization_schemas import VisualizationMethodNotSupportedError

    with pytest.raises(VisualizationMethodNotSupportedError):
        mgr.get_artifact("purchase", "umap")


# ==============================================================================
# 3. Filtering & Pagination Tests
# ==============================================================================

def test_filtering_by_error_type():
    """Verifies filtering by confusion matrix quadrant."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=40))
    mgr.set_artifact("purchase", "pca", art)

    # 40 points total: 28 TN, 4 FP, 6 TP, 2 FN
    res_fp = mgr.filter_and_paginate("purchase", "pca", error_type="FP")
    assert res_fp.metadata.filtered_points_count == 4
    for p in res_fp.points:
        assert p.error_type == ErrorType.FP
        assert p.true_label == "legitimate"
        assert p.predicted_label == "fraud"

    res_fn = mgr.filter_and_paginate("purchase", "pca", error_type="FN")
    assert res_fn.metadata.filtered_points_count == 2
    for p in res_fn.points:
        assert p.error_type == ErrorType.FN
        assert p.true_label == "fraud"
        assert p.predicted_label == "legitimate"


def test_filtering_by_conformal_ambiguity_and_outliers():
    """Verifies filtering by is_ambiguous and is_outlier."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=40))
    mgr.set_artifact("purchase", "pca", art)

    res_amb = mgr.filter_and_paginate("purchase", "pca", is_ambiguous=True)
    assert res_amb.metadata.filtered_points_count == 6  # 4 FP + 2 FN in synthetic distribution
    for p in res_amb.points:
        assert p.is_ambiguous is True

    res_out = mgr.filter_and_paginate("purchase", "pca", is_outlier=True)
    assert res_out.metadata.filtered_points_count == 6
    for p in res_out.points:
        assert p.is_outlier is True


def test_pagination_disjoint_pages():
    """Verifies pagination divides points without overlap or omission."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=35))
    mgr.set_artifact("purchase", "pca", art)

    page1 = mgr.filter_and_paginate("purchase", "pca", page=1, page_size=10)
    page2 = mgr.filter_and_paginate("purchase", "pca", page=2, page_size=10)
    page4 = mgr.filter_and_paginate("purchase", "pca", page=4, page_size=10)

    assert len(page1.points) == 10
    assert len(page2.points) == 10
    assert len(page4.points) == 5
    assert page1.metadata.total_pages == 4

    # Disjoint check
    ids1 = {p.transaction_id for p in page1.points}
    ids2 = {p.transaction_id for p in page2.points}
    assert ids1.isdisjoint(ids2)


# ==============================================================================
# 4. REST API Endpoint Tests via TestClient
# ==============================================================================

def test_api_get_purchase_pca_success(test_client: TestClient):
    """Verifies GET /visualization/purchase/pca returns HTTP 200 with valid metadata."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=30))
    mgr.set_artifact("purchase", "pca", art)

    resp = test_client.get("/visualization/purchase/pca?page=1&page_size=20")
    assert resp.status_code == 200
    data = resp.json()
    assert data["metadata"]["family"] == "purchase"
    assert data["metadata"]["method"] == "pca"
    assert data["metadata"]["total_points_available"] == 30
    assert len(data["points"]) == 20


def test_api_get_transfer_tsne_success(test_client: TestClient):
    """Verifies GET /visualization/transfer/tsne returns HTTP 200 with t-SNE parameters."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("transfer", "tsne", n_points=25))
    mgr.set_artifact("transfer", "tsne", art)

    resp = test_client.get("/visualization/transfer/tsne")
    assert resp.status_code == 200
    data = resp.json()
    assert data["metadata"]["family"] == "transfer"
    assert data["metadata"]["method"] == "tsne"
    assert data["metadata"]["perplexity"] == 30.0
    assert data["metadata"]["random_state"] == 42


def test_api_generic_dispatcher(test_client: TestClient):
    """Verifies generic /visualization/{family}/{method} endpoint."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "tsne", n_points=15))
    mgr.set_artifact("purchase", "tsne", art)

    resp = test_client.get("/visualization/purchase/tsne")
    assert resp.status_code == 200
    assert resp.json()["metadata"]["method"] == "tsne"


def test_api_page_size_validation_error(test_client: TestClient):
    """Verifies page_size < 10 or > 2000 triggers HTTP 422 RFC 7807 response."""
    resp = test_client.get("/visualization/purchase/pca?page_size=5")
    assert resp.status_code == 422
    data = resp.json()
    assert data["status"] == 422
    assert "code" in data

    resp2 = test_client.get("/visualization/purchase/pca?page_size=3000")
    assert resp2.status_code == 422


def test_api_unsupported_umap_returns_404(test_client: TestClient):
    """Verifies requesting UMAP method returns RFC 7807 HTTP 404."""
    resp = test_client.get("/visualization/purchase/umap")
    assert resp.status_code == 404
    data = resp.json()
    assert data["code"] == "METHOD_NOT_SUPPORTED"


def test_api_absent_artifact_returns_404(
    test_client: TestClient, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """Verifies unseeded artifact returns HTTP 404 VISUALIZATION_NOT_FOUND."""
    empty_dir = tmp_path / "empty_artifacts"
    empty_dir.mkdir(exist_ok=True)
    mgr = VisualizationManager(search_paths=[empty_dir])
    import visualization_loader
    monkeypatch.setattr(visualization_loader, "_visualization_manager_instance", mgr)

    resp = test_client.get("/visualization/purchase/pca")
    assert resp.status_code == 404
    data = resp.json()
    assert data["status"] == 404
    assert data["code"] == "VISUALIZATION_NOT_FOUND"


def test_api_corrupted_artifact_handling(
    test_client: TestClient, tmp_path: Path, monkeypatch: pytest.MonkeyPatch
):
    """Verifies corrupted file on disk returns HTTP 503 VISUALIZATION_CORRUPTED."""
    # Write corrupt JSON
    corrupt_file = tmp_path / "purchase_pca_points.json"
    corrupt_file.write_text("{invalid_json: true", encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    monkeypatch.setattr(visualization_loader, "_visualization_manager_instance", mgr)

    resp = test_client.get("/visualization/purchase/pca")
    assert resp.status_code == 503
    data = resp.json()
    assert data["code"] == "VISUALIZATION_CORRUPTED"


def test_concurrent_access_thread_safety():
    """Verifies thread-safe reads under concurrent threads."""
    mgr = get_visualization_manager()
    art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=50))
    mgr.set_artifact("purchase", "pca", art)

    errors = []

    def worker():
        try:
            for _ in range(20):
                res = mgr.filter_and_paginate("purchase", "pca", page=1, page_size=15)
                assert len(res.points) == 15
        except Exception as e:
            errors.append(e)

    threads = [threading.Thread(target=worker) for _ in range(10)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(errors) == 0


# ==============================================================================
# 5. Non-Regression Tests (Existing Inference & Health Endpoints)
# ==============================================================================

def test_non_regression_health_probes(test_client: TestClient):
    """Guarantees /health/live continues working with HTTP 200."""
    resp = test_client.get("/health/live")
    assert resp.status_code == 200
    assert resp.json()["status"] == "UP"


def test_non_regression_prediction_endpoints(test_client: TestClient):
    """Guarantees /predict/purchase continues to respond or reject gracefully without crash."""
    payload = {
        "request_id": str(uuid.uuid4()),
        "features": {f"feat_{i}": 1.0 for i in range(29)},
        "feature_spec_version": "purchase-v2",
    }
    resp = test_client.post("/predict/purchase", json=payload)
    # Model not ready (503) or validation error (422), but must NEVER be 500 or broken router
    assert resp.status_code in (422, 503, 200)


# ==============================================================================
# 6. Method Consistency & Cache Non-Pollution Tests (Targeted Mission)
# ==============================================================================

def test_pca_request_with_pca_artifact_success(test_client: TestClient, tmp_path: Path):
    """Verifies that requesting PCA with a valid PCA artifact succeeds."""
    pca_data = make_synthetic_artifact("purchase", "pca", n_points=15)
    pca_file = tmp_path / "purchase_pca_points.json"
    pca_file.write_text(json.dumps(pca_data), encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    visualization_loader._visualization_manager_instance = mgr

    try:
        resp = test_client.get("/visualization/purchase/pca")
        assert resp.status_code == 200
        data = resp.json()
        assert data["metadata"]["method"] == "pca"
        assert mgr.is_cached("purchase", "pca")
    finally:
        visualization_loader._visualization_manager_instance = None


def test_tsne_request_with_tsne_artifact_success(test_client: TestClient, tmp_path: Path):
    """Verifies that requesting t-SNE with a valid t-SNE artifact succeeds."""
    tsne_data = make_synthetic_artifact("transfer", "tsne", n_points=12)
    tsne_file = tmp_path / "transfer_tsne_points.json"
    tsne_file.write_text(json.dumps(tsne_data), encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    visualization_loader._visualization_manager_instance = mgr

    try:
        resp = test_client.get("/visualization/transfer/tsne")
        assert resp.status_code == 200
        data = resp.json()
        assert data["metadata"]["method"] == "tsne"
        assert mgr.is_cached("transfer", "tsne")
    finally:
        visualization_loader._visualization_manager_instance = None


def test_pca_request_rejects_tsne_artifact(test_client: TestClient, tmp_path: Path):
    """Verifies requesting PCA when file contains t-SNE raises HTTP 503 and does not pollute cache."""
    # Write a file named purchase_pca_points.json but containing method='tsne'
    bad_data = make_synthetic_artifact("purchase", "tsne", n_points=10)
    bad_file = tmp_path / "purchase_pca_points.json"
    bad_file.write_text(json.dumps(bad_data), encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    visualization_loader._visualization_manager_instance = mgr

    try:
        resp = test_client.get("/visualization/purchase/pca")
        assert resp.status_code == 503
        data = resp.json()
        assert data["code"] == "VISUALIZATION_CORRUPTED"
        assert "purchase_pca_points.json" in data["detail"]
        assert "contains method 'tsne', but requested method was 'pca'" in data["detail"]
        # Guarantee no cache pollution
        assert not mgr.is_cached("purchase", "pca")
    finally:
        visualization_loader._visualization_manager_instance = None


def test_tsne_request_rejects_pca_artifact(test_client: TestClient, tmp_path: Path):
    """Verifies requesting t-SNE when file contains PCA raises HTTP 503 and does not pollute cache."""
    # Write a file named purchase_tsne_points.json but containing method='pca'
    bad_data = make_synthetic_artifact("purchase", "pca", n_points=10)
    bad_file = tmp_path / "purchase_tsne_points.json"
    bad_file.write_text(json.dumps(bad_data), encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    visualization_loader._visualization_manager_instance = mgr

    try:
        resp = test_client.get("/visualization/purchase/tsne")
        assert resp.status_code == 503
        data = resp.json()
        assert data["code"] == "VISUALIZATION_CORRUPTED"
        assert "purchase_tsne_points.json" in data["detail"]
        assert "contains method 'pca', but requested method was 'tsne'" in data["detail"]
        # Guarantee no cache pollution
        assert not mgr.is_cached("purchase", "tsne")
    finally:
        visualization_loader._visualization_manager_instance = None


def test_fallback_filename_rejects_mismatched_method(test_client: TestClient, tmp_path: Path):
    """
    Verifies that if only purchase_visualization_points.json exists with method='pca',
    requesting t-SNE rejects the fallback file with HTTP 503 and does not pollute cache.
    """
    pca_data = make_synthetic_artifact("purchase", "pca", n_points=10)
    fallback_file = tmp_path / "purchase_visualization_points.json"
    fallback_file.write_text(json.dumps(pca_data), encoding="utf-8")

    mgr = VisualizationManager(search_paths=[tmp_path])
    import visualization_loader
    visualization_loader._visualization_manager_instance = mgr

    try:
        resp = test_client.get("/visualization/purchase/tsne")
        assert resp.status_code == 503
        data = resp.json()
        assert data["code"] == "VISUALIZATION_CORRUPTED"
        assert "contains method 'pca', but requested method was 'tsne'" in data["detail"]
        assert not mgr.is_cached("purchase", "tsne")
    finally:
        visualization_loader._visualization_manager_instance = None


def test_set_artifact_rejects_method_and_family_mismatch():
    """Verifies in-memory set_artifact rejects method and family mismatches."""
    mgr = get_visualization_manager()
    from visualization_schemas import VisualizationCorruptedError

    pca_art = VisualizationArtifact.model_validate(make_synthetic_artifact("purchase", "pca", n_points=5))
    with pytest.raises(VisualizationCorruptedError, match="Artifact specifies method 'pca', but was injected under method 'tsne'"):
        mgr.set_artifact("purchase", "tsne", pca_art)

    transfer_art = VisualizationArtifact.model_validate(make_synthetic_artifact("transfer", "pca", n_points=5))
    with pytest.raises(VisualizationCorruptedError, match="Artifact specifies family 'transfer', but was injected under family 'purchase'"):
        mgr.set_artifact("purchase", "pca", transfer_art)

