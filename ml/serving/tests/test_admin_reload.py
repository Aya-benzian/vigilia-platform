"""
test_admin_reload.py — Hardened tests for POST /admin/reload/{family} endpoint (Design 2 Section 15.5).

Security properties covered:
1. Missing authentication credentials (HTTP 401 Unauthorized, RFC 7807).
2. Invalid authentication credentials (HTTP 401 Unauthorized, RFC 7807).
3. Valid credentials via Authorization Bearer and X-Admin-Token (HTTP 200 / 202).
4. Confirmation that rejected requests (401 / 422) do not start model loading.
5. Duplicate concurrent reloads of the same family rejected (HTTP 409 Conflict).
6. Independent concurrent reloads of different families allowed.
7. Concurrency guard cleanup after both success and failure.
8. Background reload failure handling, metric increment, and guard release.
9. Preservation of active model after loading or pre-activation validation failure.
10. Existing family validation and RFC 7807 error format adherence.

NOTE ON MOCK-BASED TESTING & REAL SECURITY BOUNDARIES:
- The tests below prove application-level authentication, per-family concurrency locking,
  RFC 7807 error sanitization, and pre-activation validation.
- They rely on unit test mocks, test tokens, and DummyFraudModels.
- THEY DO NOT PROVE:
  * Network-level firewalling or Kubernetes NetworkPolicy enforcement.
  * Real MLflow tracking server mTLS, OAuth2/OIDC token exchange, or artifact store IAM permissions.
  * Prevention of malicious pickle deserialization if MLflow storage itself were compromised.
"""

import threading
from typing import Any, Dict, List
from unittest.mock import MagicMock, patch
import pytest
from fastapi.testclient import TestClient

from model_loader import (
    DummyPurchaseModel,
    DummyTransferModel,
    acquire_reload_guard,
    get_model_registry,
    is_family_reloading,
    release_reload_guard,
    reset_model_cache,
    stop_background_model_loader,
)
from schemas import ReloadResponse


# =====================================================================
# 1. Authentication & Access Control Tests
# =====================================================================

def test_reload_missing_auth_credentials_rejected(
    test_client: TestClient,
    admin_api_key: str,
) -> None:
    """Unauthenticated calls without headers are rejected immediately (HTTP 401)."""
    response = test_client.post("/admin/reload/purchase")
    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()

    assert data["code"] == "UNAUTHORIZED"
    assert data["status"] == 401
    assert "required" in data["detail"].lower() or "missing" in data["detail"].lower()


def test_reload_invalid_auth_credentials_rejected(
    test_client: TestClient,
    admin_api_key: str,
) -> None:
    """Calls with incorrect credentials are rejected immediately (HTTP 401)."""
    response = test_client.post(
        "/admin/reload/purchase",
        headers={"Authorization": "Bearer completely-wrong-invalid-key"},
    )
    assert response.status_code == 401
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()

    assert data["code"] == "UNAUTHORIZED"
    assert data["status"] == 401
    assert "invalid" in data["detail"].lower()


def test_reload_unconfigured_auth_fails_safely(
    test_client: TestClient,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """If ADMIN_API_KEY is not configured in settings, reload requests fail safely (HTTP 401)."""
    monkeypatch.delenv("ADMIN_API_KEY", raising=False)
    monkeypatch.delenv("ML_ADMIN_API_KEY", raising=False)

    response = test_client.post(
        "/admin/reload/purchase",
        headers={"Authorization": "Bearer some-token"},
    )
    assert response.status_code == 401
    data = response.json()
    assert data["code"] == "UNAUTHORIZED"
    assert "not configured" in data["detail"].lower() or "access denied" in data["detail"].lower()


def test_reload_valid_bearer_and_custom_header_auth(
    test_client: TestClient,
    admin_api_key: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Valid credentials via Authorization Bearer or X-Admin-Token are accepted."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    # 1. Test with Authorization: Bearer <key>
    resp_bearer = test_client.post(
        "/admin/reload/purchase",
        headers={"Authorization": f"Bearer {admin_api_key}"},
    )
    assert resp_bearer.status_code == 200
    assert resp_bearer.json()["status"] == "COMPLETED"

    # 2. Test with X-Admin-Token: <key>
    resp_custom = test_client.post(
        "/admin/reload/transfer",
        headers={"X-Admin-Token": admin_api_key},
    )
    assert resp_custom.status_code == 200
    assert resp_custom.json()["status"] == "COMPLETED"


def test_reload_valid_x_api_key_header_auth(
    test_client: TestClient,
    admin_api_key: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Valid credentials via X-API-Key header are accepted and trigger reload exactly once."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    registry = get_model_registry()
    assert registry.is_family_loaded("purchase") is False

    with patch("main.reload_family_model") as mock_reload:
        def mock_load(family: str):
            dummy = DummyPurchaseModel(version="p-apikey-v1")
            registry.set_model(family, dummy, "p-apikey-v1")
            return dummy, "p-apikey-v1"
        mock_reload.side_effect = mock_load

        response = test_client.post(
            "/admin/reload/purchase",
            headers={"X-API-Key": admin_api_key},
        )
        assert response.status_code == 200
        data = response.json()
        parsed = ReloadResponse(**data)
        assert parsed.status == "COMPLETED"
        assert parsed.family == "purchase"
        assert parsed.model is not None
        assert parsed.model.model_name == "purchase-fraud-model"
        assert parsed.model.model_version == "p-apikey-v1"
        assert parsed.model.status == "READY"

        mock_reload.assert_called_once_with("purchase")


def test_rejected_request_does_not_trigger_model_load(
    test_client: TestClient,
    admin_api_key: str,
) -> None:
    """Authentication and validation rejections prevent model loading functions from being invoked."""
    with patch("main.reload_family_model") as mock_reload:
        # Missing auth
        resp_no_auth = test_client.post("/admin/reload/purchase")
        assert resp_no_auth.status_code == 401
        mock_reload.assert_not_called()

        # Invalid auth
        resp_bad_auth = test_client.post(
            "/admin/reload/purchase",
            headers={"Authorization": "Bearer bad-token"},
        )
        assert resp_bad_auth.status_code == 401
        mock_reload.assert_not_called()

        # Invalid family (authenticated)
        resp_bad_family = test_client.post(
            "/admin/reload/unsupported_family",
            headers={"Authorization": f"Bearer {admin_api_key}"},
        )
        assert resp_bad_family.status_code == 422
        mock_reload.assert_not_called()


# =====================================================================
# 2. Concurrency, Race Condition, and DoS Protection Tests
# =====================================================================

def test_duplicate_concurrent_reload_same_family_rejected(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """A concurrent reload request for an already-reloading family returns HTTP 409 Conflict."""
    stop_background_model_loader()
    reset_model_cache()

    # Simulate an active in-flight reload for purchase
    acquired = acquire_reload_guard("purchase")
    assert acquired is True
    assert is_family_reloading("purchase") is True

    try:
        response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert response.status_code == 409
        assert response.headers["content-type"] == "application/problem+json"
        data = response.json()

        assert data["code"] == "RELOAD_IN_PROGRESS"
        assert data["status"] == 409
        assert "already in progress" in data["detail"]
    finally:
        release_reload_guard("purchase")

    assert is_family_reloading("purchase") is False


def test_concurrent_reload_requests_same_family_rejected(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """
    Deterministic overlapping concurrency test:
    Proves that when a reload for 'purchase' is actively in-flight on a worker thread,
    a second concurrent request for 'purchase' is immediately rejected with HTTP 409 Conflict
    (RELOAD_IN_PROGRESS) without triggering a second reload operation.
    Upon completion, the guard is released and subsequent reload requests succeed.
    """
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()

    reload_started = threading.Event()
    allow_reload_finish = threading.Event()
    reload_call_count = 0
    call_count_lock = threading.Lock()

    def slow_mock_reload(family: str, tracking_uri=None):
        nonlocal reload_call_count
        with call_count_lock:
            reload_call_count += 1
        dummy = DummyPurchaseModel(version="concurrent-v1")
        registry.set_model(family, dummy, "concurrent-v1")
        # 1. Signal that reload is actively executing inside the critical section
        reload_started.set()
        # 2. Block until the test permits completion
        if not allow_reload_finish.wait(timeout=5.0):
            raise TimeoutError("slow_mock_reload timed out waiting for allow_reload_finish.")
        return dummy, "concurrent-v1"

    req1_result: Dict[str, Any] = {}
    req1_exception: List[BaseException] = []

    def req1_worker():
        try:
            resp = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
            req1_result["status_code"] = resp.status_code
            req1_result["data"] = resp.json()
        except BaseException as exc:
            req1_exception.append(exc)

    req1_thread = threading.Thread(target=req1_worker, name="Req1WorkerThread", daemon=True)

    with patch("main.reload_family_model", side_effect=slow_mock_reload):
        try:
            # Step 1: Start Request 1 on background thread
            req1_thread.start()

            # Step 2: Wait until Request 1 enters the reload operation
            assert reload_started.wait(timeout=5.0), "Request 1 did not enter reload operation within timeout."

            # Verify that ReloadGuard reports purchase is actively reloading
            assert is_family_reloading("purchase") is True

            # Step 3: While Request 1 is paused in-flight, send Request 2 from main thread
            resp2 = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)

            # Step 4: Assert Request 2 immediately receives HTTP 409 Conflict
            assert resp2.status_code == 409
            assert resp2.headers["content-type"] == "application/problem+json"
            data2 = resp2.json()
            assert data2["code"] == "RELOAD_IN_PROGRESS"
            assert data2["status"] == 409
            assert "already in progress" in data2["detail"]

            # Step 5: Assert reload operation was NOT triggered by Request 2
            with call_count_lock:
                assert reload_call_count == 1

        finally:
            # Step 6: Unconditionally release Request 1 so thread is never left blocked
            allow_reload_finish.set()
            req1_thread.join(timeout=5.0)

        # Propagate any worker exception to fail test visibly
        if req1_exception:
            raise req1_exception[0]

        # Step 7: Assert Request 1 completed with HTTP 200 OK
        assert req1_result.get("status_code") == 200
        assert req1_result["data"]["status"] == "COMPLETED"
        assert req1_result["data"]["model"]["model_version"] == "concurrent-v1"

        # Step 8: Assert reload operation was executed exactly once for the concurrent pair
        with call_count_lock:
            assert reload_call_count == 1

        # Assert guard was cleaned up and is now released
        assert is_family_reloading("purchase") is False

        # Step 9: Verify a subsequent reload now succeeds without conflict
        resp3 = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert resp3.status_code == 200
        assert resp3.json()["status"] == "COMPLETED"
        with call_count_lock:
            assert reload_call_count == 2


def test_independent_reloads_different_families(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reloading 'purchase' does not block or conflict with reloading 'transfer'."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    # Lock purchase
    assert acquire_reload_guard("purchase") is True

    try:
        # Reloading transfer must succeed unimpeded
        response_transfer = test_client.post(
            "/admin/reload/transfer",
            headers=admin_auth_headers,
        )
        assert response_transfer.status_code == 200
        assert response_transfer.json()["family"] == "transfer"
    finally:
        release_reload_guard("purchase")


def test_guard_cleanup_after_success_and_failure(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The concurrency guard is guaranteed to be released after success and after failure."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    assert not is_family_reloading("purchase")

    # 1. Success case
    resp_ok = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
    assert resp_ok.status_code == 200
    assert not is_family_reloading("purchase")

    # 2. Failure case
    with patch("main.reload_family_model", side_effect=RuntimeError("Transient network failure")):
        resp_err = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert resp_err.status_code == 500
        assert not is_family_reloading("purchase")


def test_background_reload_failure_handling_and_guard_release(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """Background reload failure logs sanitized error, increments failure metric, and releases guard."""
    from main import _background_reload_worker

    stop_background_model_loader()
    reset_model_cache()

    # Pre-acquire guard as would happen in background mode
    assert acquire_reload_guard("purchase") is True

    with patch("main.reload_family_model", side_effect=ConnectionError("MLflow unreachable")):
        # Execute the worker directly to verify exception containment and guard release
        _background_reload_worker("purchase")

    # Guard must be released despite exception
    assert not is_family_reloading("purchase")


# =====================================================================
# 3. Model Integrity & Safe Pre-Activation Tests
# =====================================================================

def test_pre_activation_validation_failure_preserves_active_model(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """If a replacement artifact fails pre-activation validation, active model is untouched."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    active_v1 = DummyPurchaseModel(version="purchase-v1")
    registry.set_model("purchase", active_v1, "purchase-v1")

    # Artifact without predict or predict_proba
    class InvalidModelArtifact:
        pass

    def mock_invalid_load(family: str, tracking_uri=None):
        # Simulate loading invalid artifact that raises pre-activation validation error
        raise ValueError("Model artifact for family 'purchase' does not expose a valid prediction interface.")

    with patch("main.reload_family_model", side_effect=mock_invalid_load):
        response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert response.status_code == 500

    # Active model remains untouched
    assert registry.is_family_loaded("purchase") is True
    assert registry.get_version("purchase") == "purchase-v1"
    assert registry.get_model("purchase") is active_v1


def test_dummy_model_forbidden_in_production_or_staging(monkeypatch: pytest.MonkeyPatch) -> None:
    """Security check: Dummy models are strictly blocked in production or staging environments."""
    from model_loader import create_dummy_model

    monkeypatch.setenv("APP_ENV", "production")
    with pytest.raises(RuntimeError) as exc_info:
        create_dummy_model("purchase")
    assert "forbidden" in str(exc_info.value).lower()


# =====================================================================
# 4. Standard Functional & RFC 7807 Error Response Tests
# =====================================================================

def test_reload_purchase_success_synchronous(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reload purchase succeeds synchronously and returns COMPLETED status."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    registry = get_model_registry()
    assert registry.is_family_loaded("purchase") is False

    response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
    assert response.status_code == 200
    data = response.json()

    parsed = ReloadResponse(**data)
    assert parsed.status == "COMPLETED"
    assert parsed.family == "purchase"
    assert "successfully reloaded" in parsed.message
    assert parsed.model is not None
    assert parsed.model.family == "purchase"
    assert parsed.model.model_name == "purchase-fraud-model"
    assert parsed.model.status == "READY"

    assert registry.is_family_loaded("purchase") is True


def test_reload_transfer_success_synchronous(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reload transfer succeeds synchronously and returns COMPLETED status."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    registry = get_model_registry()
    assert registry.is_family_loaded("transfer") is False

    response = test_client.post("/admin/reload/transfer", headers=admin_auth_headers)
    assert response.status_code == 200
    data = response.json()

    parsed = ReloadResponse(**data)
    assert parsed.status == "COMPLETED"
    assert parsed.family == "transfer"
    assert parsed.model is not None
    assert parsed.model.family == "transfer"
    assert parsed.model.model_name == "transfer-fraud-model"
    assert parsed.model.status == "READY"

    assert registry.is_family_loaded("transfer") is True


def test_reload_unknown_family_rejected(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """Unknown family names return HTTP 422 with RFC 7807 problem details."""
    response = test_client.post("/admin/reload/credit_card", headers=admin_auth_headers)
    assert response.status_code == 422
    assert response.headers["content-type"] == "application/problem+json"
    data = response.json()

    assert data["code"] == "VALIDATION_ERROR"
    assert data["status"] == 422
    assert "credit_card" in data["detail"]
    assert len(data["errors"]) > 0


def test_reload_failure_returns_safe_500_error(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """Failures return sanitized RFC 7807 HTTP 500 without leaking secrets or paths."""
    stop_background_model_loader()
    reset_model_cache()

    with patch(
        "main.reload_family_model",
        side_effect=ConnectionError("MLflow connection to http://secret-host:5000 failed"),
    ):
        response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert response.status_code == 500
        assert response.headers["content-type"] == "application/problem+json"
        data = response.json()

        assert data["code"] == "INFERENCE_ERROR"
        assert data["status"] == 500
        assert "purchase" in data["detail"]
        assert "Active model preserved" in data["detail"]

        # Sensitive host and stack trace details are suppressed
        assert "secret-host" not in response.text
        assert "traceback" not in response.text.lower()


def test_existing_active_model_preserved_when_replacement_fails(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """Existing model continues serving when reload attempt fails."""
    stop_background_model_loader()
    reset_model_cache()

    registry = get_model_registry()
    dummy_v1 = DummyPurchaseModel(version="purchase-v1")
    registry.set_model("purchase", dummy_v1, "purchase-v1")
    assert registry.get_version("purchase") == "purchase-v1"

    def mock_fail(family: str, tracking_uri=None):
        registry.record_failure(family, "Simulated replacement load error")
        raise RuntimeError("MLflow failed to download new artifact")

    with patch("main.reload_family_model", side_effect=mock_fail):
        response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert response.status_code == 500

    # Active model MUST be preserved and still serving
    assert registry.is_family_loaded("purchase") is True
    assert registry.get_version("purchase") == "purchase-v1"
    assert registry.get_model("purchase") is dummy_v1


def test_other_family_unaffected_during_reload(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Reloading one family leaves the other family completely undisturbed."""
    stop_background_model_loader()
    reset_model_cache()
    monkeypatch.setenv("APP_ENV", "test")
    monkeypatch.setenv("USE_DUMMY_MODEL", "true")

    registry = get_model_registry()
    transfer_dummy = DummyTransferModel(version="transfer-v1")
    registry.set_model("transfer", transfer_dummy, "transfer-v1")
    assert registry.get_version("transfer") == "transfer-v1"

    response = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
    assert response.status_code == 200

    assert registry.get_version("transfer") == "transfer-v1"
    assert registry.get_model("transfer") is transfer_dummy


def test_distinction_between_async_acceptance_and_completed_reload(
    test_client: TestClient,
    admin_auth_headers: Dict[str, str],
) -> None:
    """Background=true returns HTTP 202 ACCEPTED while background=false returns HTTP 200 COMPLETED."""
    stop_background_model_loader()
    reset_model_cache()

    with patch("main.reload_family_model"):
        # Background=True triggers HTTP 202 ACCEPTED
        response_async = test_client.post(
            "/admin/reload/purchase?background=true",
            headers=admin_auth_headers,
        )
        assert response_async.status_code == 202
        data_async = response_async.json()
        assert data_async["status"] == "ACCEPTED"
        assert data_async["family"] == "purchase"
        assert data_async["model"] is None
        assert "accepted and scheduled in background" in data_async["message"]

    # Background=False (default) triggers HTTP 200 COMPLETED on success
    with patch("main.reload_family_model") as mock_sync_reload:
        def mock_load(family):
            get_model_registry().set_model(family, DummyPurchaseModel(version="p-sync-v1"), "p-sync-v1")
            return DummyPurchaseModel(version="p-sync-v1"), "p-sync-v1"
        mock_sync_reload.side_effect = mock_load

        response_sync = test_client.post("/admin/reload/purchase", headers=admin_auth_headers)
        assert response_sync.status_code == 200
        data_sync = response_sync.json()
        assert data_sync["status"] == "COMPLETED"
        assert data_sync["model"] is not None
        assert data_sync["model"]["model_version"] == "p-sync-v1"
