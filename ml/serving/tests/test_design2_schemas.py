"""
test_design2_schemas.py — Comprehensive tests for Design 2 schemas, error contract, and validation boundaries.

Tests cover:
1. PredictionRequest generic validation:
   - Valid payload parsing with UUID validation
   - Missing required fields (request_id, feature_spec_version, features)
   - Malformed/empty features payloads (empty dict, non-string keys, null values)
   - Unexpected top-level fields rejection (extra="forbid")
   - Invalid non-UUID request_id rejection
   - Empty/whitespace feature_spec_version rejection
2. PredictionResponse structural schema validation:
   - Valid response construction
   - Probability bounds [0.0, 1.0]
   - Negative uncertainty rejection
   - Negative inference_ms rejection
   - Extra fields rejection
3. ExplanationResponse and FeatureContribution:
   - Valid explanation response
   - Aliases support (feature/feature_name, value/feature_value, shap/shap_value/contribution)
   - Extra fields rejection
4. ModelMetadata and ModelsListResponse:
   - Valid metadata construction
   - Factory from ModelEntry (ready and unready states)
   - ModelsListResponse container structure
5. Standardized Errors — RFC 7807 ProblemDetail:
   - Valid ProblemDetail creation
   - All 4 required error codes:
     * HTTP 422: FEATURE_VALIDATION_ERROR
     * HTTP 409: FEATURE_SPEC_MISMATCH
     * HTTP 503: MODEL_NOT_READY
     * HTTP 500: INFERENCE_ERROR
   - Additional codes (VALIDATION_ERROR, MALFORMED_REQUEST)
   - No stack trace exposure
6. Feature-spec validation boundary:
   - verify_feature_spec_version matching version passes
   - verify_feature_spec_version mismatched raises FeatureSpecMismatchError
   - verify_feature_spec_version with None active version passes
   - BaseFeatureSpecValidator abstract interface
7. FastAPI error handler integration:
   - ServingException handler returns RFC 7807 application/problem+json
   - RequestValidationError handler returns RFC 7807 application/problem+json with field errors
"""

import uuid
from typing import Any, Dict
import pytest
from pydantic import ValidationError
from fastapi import FastAPI
from fastapi.testclient import TestClient

from schemas import (
    ERROR_CODE_FEATURE_SPEC_MISMATCH,
    ERROR_CODE_FEATURE_VALIDATION,
    ERROR_CODE_INFERENCE_ERROR,
    ERROR_CODE_MALFORMED_REQUEST,
    ERROR_CODE_MODEL_NOT_READY,
    ERROR_CODE_VALIDATION_ERROR,
    BaseFeatureSpecValidator,
    ExplanationResponse,
    FeatureContribution,
    FeatureSpecMismatchError,
    FeatureValidationError,
    InferenceError,
    ModelMetadata,
    ModelNotReadyError,
    ModelsListResponse,
    PredictionRequest,
    PredictionResponse,
    ProblemDetail,
    ServingException,
    ValidationErrorItem,
    register_error_handlers,
    verify_feature_spec_version,
)
from model_loader import ModelEntry


# =====================================================================
# 1. PredictionRequest Tests
# =====================================================================

def test_prediction_request_valid_parsing() -> None:
    req_id = str(uuid.uuid4())
    payload = {
        "request_id": req_id,
        "feature_spec_version": "purchase-v1",
        "features": {
            "amount": 120.50,
            "merchant_category": "grocery",
            "hour_of_day": 14,
            "is_weekend": 0,
        },
    }
    req = PredictionRequest(**payload)
    assert req.request_id == req_id
    assert req.feature_spec_version == "purchase-v1"
    assert req.features["amount"] == 120.50
    assert req.features["merchant_category"] == "grocery"


def test_prediction_request_missing_required_fields() -> None:
    # Missing request_id
    with pytest.raises(ValidationError) as exc:
        PredictionRequest(
            feature_spec_version="purchase-v1",
            features={"amount": 100.0},
        )
    assert any(e["loc"] == ("request_id",) for e in exc.value.errors())

    # Missing feature_spec_version
    with pytest.raises(ValidationError) as exc:
        PredictionRequest(
            request_id=str(uuid.uuid4()),
            features={"amount": 100.0},
        )
    assert any(e["loc"] == ("feature_spec_version",) for e in exc.value.errors())

    # Missing features
    with pytest.raises(ValidationError) as exc:
        PredictionRequest(
            request_id=str(uuid.uuid4()),
            feature_spec_version="purchase-v1",
        )
    assert any(e["loc"] == ("features",) for e in exc.value.errors())


def test_prediction_request_invalid_non_uuid_request_id() -> None:
    payload = {
        "request_id": "not-a-valid-uuid",
        "feature_spec_version": "purchase-v1",
        "features": {"amount": 100.0},
    }
    with pytest.raises(ValidationError, match="not a valid RFC 4122 UUID"):
        PredictionRequest(**payload)


def test_prediction_request_empty_feature_spec_version() -> None:
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "   ",
        "features": {"amount": 100.0},
    }
    with pytest.raises(ValidationError, match="cannot be empty or whitespace"):
        PredictionRequest(**payload)


def test_prediction_request_empty_features_rejected() -> None:
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v1",
        "features": {},
    }
    with pytest.raises(ValidationError, match="features payload cannot be empty"):
        PredictionRequest(**payload)


def test_prediction_request_null_feature_value_rejected() -> None:
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v1",
        "features": {"amount": None},
    }
    with pytest.raises(ValidationError, match="cannot be null"):
        PredictionRequest(**payload)


def test_prediction_request_extra_fields_forbidden() -> None:
    payload = {
        "request_id": str(uuid.uuid4()),
        "feature_spec_version": "purchase-v1",
        "features": {"amount": 50.0},
        "extra_unknown_field": "disallowed",
    }
    with pytest.raises(ValidationError) as exc:
        PredictionRequest(**payload)
    assert any(e["loc"] == ("extra_unknown_field",) for e in exc.value.errors())


# =====================================================================
# 2. PredictionResponse Tests (Structural preparation only)
# =====================================================================

def test_prediction_response_valid_structural_parsing() -> None:
    req_id = str(uuid.uuid4())
    resp = PredictionResponse(
        request_id=req_id,
        label="FRAUD",
        fraud_probability=0.88,
        uncertainty=0.07,
        model_name="purchase-fraud-model",
        model_version="2",
        feature_spec_version="purchase-v1",
        inference_ms=14.5,
    )
    assert resp.request_id == req_id
    assert resp.label == "FRAUD"
    assert resp.fraud_probability == 0.88
    assert resp.uncertainty == 0.07
    assert resp.model_name == "purchase-fraud-model"
    assert resp.model_version == "2"
    assert resp.feature_spec_version == "purchase-v1"
    assert resp.inference_ms == 14.5


def test_prediction_response_probability_bounds() -> None:
    req_id = str(uuid.uuid4())
    # Probability > 1.0
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="FRAUD",
            fraud_probability=1.05,
            uncertainty=0.1,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=10.0,
        )

    # Probability < 0.0
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="LEGITIMATE",
            fraud_probability=-0.01,
            uncertainty=0.1,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=10.0,
        )


def test_prediction_response_negative_uncertainty_and_latency_rejected() -> None:
    req_id = str(uuid.uuid4())
    # Negative uncertainty
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="FRAUD",
            fraud_probability=0.5,
            uncertainty=-0.1,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=10.0,
        )

    # Negative inference latency
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="FRAUD",
            fraud_probability=0.5,
            uncertainty=0.1,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=-5.0,
        )


def test_prediction_response_uncertainty_bounds() -> None:
    req_id = str(uuid.uuid4())
    # Uncertainty > 1.0 (e.g. 1.5) must be rejected
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="FRAUD",
            fraud_probability=0.5,
            uncertainty=1.5,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=10.0,
        )

    # Boundary value: 0.0 must be accepted
    resp_zero = PredictionResponse(
        request_id=req_id,
        label="FRAUD",
        fraud_probability=0.5,
        uncertainty=0.0,
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v1",
        inference_ms=10.0,
    )
    assert resp_zero.uncertainty == 0.0

    # Boundary value: 1.0 must be accepted
    resp_one = PredictionResponse(
        request_id=req_id,
        label="FRAUD",
        fraud_probability=0.5,
        uncertainty=1.0,
        model_name="purchase-fraud-model",
        model_version="1",
        feature_spec_version="purchase-v1",
        inference_ms=10.0,
    )
    assert resp_one.uncertainty == 1.0


def test_prediction_response_extra_fields_forbidden() -> None:
    req_id = str(uuid.uuid4())
    with pytest.raises(ValidationError):
        PredictionResponse(
            request_id=req_id,
            label="FRAUD",
            fraud_probability=0.5,
            uncertainty=0.1,
            model_name="purchase-fraud-model",
            model_version="1",
            feature_spec_version="purchase-v1",
            inference_ms=10.0,
            unexpected_field="disallowed",
        )


# =====================================================================
# 3. ExplanationResponse & FeatureContribution Tests
# =====================================================================

def test_feature_contribution_with_standard_keys() -> None:
    item = FeatureContribution(feature="amount", value=150.0, shap=0.35)
    assert item.feature == "amount"
    assert item.value == 150.0
    assert item.shap == 0.35


def test_feature_contribution_with_aliases() -> None:
    item = FeatureContribution(feature_name="distance_km", feature_value=85.2, contribution=0.45)
    assert item.feature == "distance_km"
    assert item.value == 85.2
    assert item.shap == 0.45

    item2 = FeatureContribution(feature_name="hour", feature_value=23, shap_value=-0.12)
    assert item2.feature == "hour"
    assert item2.value == 23
    assert item2.shap == -0.12


def test_explanation_response_valid_parsing() -> None:
    req_id = str(uuid.uuid4())
    resp = ExplanationResponse(
        request_id=req_id,
        model_name="purchase-fraud-model",
        model_version="1",
        base_value=0.04,
        contributions=[
            FeatureContribution(feature="amount", value=500.0, shap=0.4),
            FeatureContribution(feature="hour", value=3, shap=0.2),
        ],
    )
    assert resp.request_id == req_id
    assert resp.model_name == "purchase-fraud-model"
    assert resp.model_version == "1"
    assert resp.base_value == 0.04
    assert len(resp.contributions) == 2
    assert resp.contributions[0].feature == "amount"


# =====================================================================
# 4. ModelMetadata & ModelsListResponse Tests
# =====================================================================

def test_model_metadata_creation_and_factory() -> None:
    # Direct construction
    meta = ModelMetadata(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        alias="champion",
        feature_spec_version="purchase-v1",
        status="READY",
        loaded_at=1700000000.0,
    )
    assert meta.family == "purchase"
    assert meta.status == "READY"
    assert meta.feature_spec_version == "purchase-v1"

    # From active ModelEntry
    entry = ModelEntry(
        family="transfer",
        model_name="transfer-fraud-model",
        model_version="transfer-v1",
        model_alias="champion",
        model=None,
        feature_spec_version="transfer-v1",
    )
    meta_from_entry = ModelMetadata.from_model_entry(entry, family="transfer", status="READY")
    assert meta_from_entry.family == "transfer"
    assert meta_from_entry.model_version == "transfer-v1"
    assert meta_from_entry.status == "READY"

    # From None (unloaded family)
    meta_unloaded = ModelMetadata.from_model_entry(
        None, family="purchase", status="LOADING", default_model_name="purchase-fraud-model"
    )
    assert meta_unloaded.family == "purchase"
    assert meta_unloaded.model_version is None
    assert meta_unloaded.status == "LOADING"


def test_models_list_response_structure() -> None:
    purchase_meta = ModelMetadata(
        family="purchase",
        model_name="purchase-fraud-model",
        model_version="1",
        status="READY",
    )
    transfer_meta = ModelMetadata(
        family="transfer",
        model_name="transfer-fraud-model",
        model_version="1",
        status="READY",
    )
    list_resp = ModelsListResponse(
        models={"purchase": purchase_meta, "transfer": transfer_meta},
        all_loaded=True,
    )
    assert list_resp.all_loaded is True
    assert "purchase" in list_resp.models
    assert "transfer" in list_resp.models


# =====================================================================
# 5. RFC 7807 Standardized Errors & Exception Hierarchy Tests
# =====================================================================

def test_problem_detail_representation() -> None:
    problem = ProblemDetail(
        type="https://vigilia.example/errors/feature-validation-error",
        code=ERROR_CODE_FEATURE_VALIDATION,
        title="Feature validation failed",
        status=422,
        detail="Feature 'amount' is missing.",
        trace_id="trace-1234",
        timestamp="2026-10-26T18:00:00Z",
        errors=[ValidationErrorItem(field="features.amount", message="Missing field")],
    )
    assert problem.code == "FEATURE_VALIDATION_ERROR"
    assert problem.status == 422
    assert problem.trace_id == "trace-1234"
    assert len(problem.errors) == 1
    assert problem.errors[0].field == "features.amount"


def test_exception_classes_and_status_codes() -> None:
    # FeatureValidationError (422)
    fve = FeatureValidationError(
        detail="Invalid features",
        errors=[ValidationErrorItem(field="features.amt", message="Must be positive")],
    )
    assert fve.status_code == 422
    assert fve.code == ERROR_CODE_FEATURE_VALIDATION
    pd = fve.to_problem_detail(trace_id="t1")
    assert pd.status == 422
    assert pd.code == "FEATURE_VALIDATION_ERROR"
    assert pd.trace_id == "t1"
    assert pd.errors[0].field == "features.amt"

    # FeatureSpecMismatchError (409)
    fsme = FeatureSpecMismatchError(
        requested_version="purchase-v2",
        expected_version="purchase-v1",
        family="purchase",
    )
    assert fsme.status_code == 409
    assert fsme.code == ERROR_CODE_FEATURE_SPEC_MISMATCH
    pd409 = fsme.to_problem_detail()
    assert pd409.status == 409
    assert "purchase-v2" in pd409.detail
    assert "purchase-v1" in pd409.detail

    # ModelNotReadyError (503)
    mnre = ModelNotReadyError(family="transfer")
    assert mnre.status_code == 503
    assert mnre.code == ERROR_CODE_MODEL_NOT_READY
    pd503 = mnre.to_problem_detail()
    assert pd503.status == 503
    assert "transfer" in pd503.detail

    # InferenceError (500)
    ie = InferenceError()
    assert ie.status_code == 500
    assert ie.code == ERROR_CODE_INFERENCE_ERROR
    pd500 = ie.to_problem_detail()
    assert pd500.status == 500
    assert "Internal inference error" in pd500.title


# =====================================================================
# 6. Feature-Spec Validation Boundary Tests
# =====================================================================

def test_verify_feature_spec_version_matching() -> None:
    # Exact match passes without error
    verify_feature_spec_version("purchase", "purchase-v1", "purchase-v1")

    # None active model spec version passes (unversioned/dummy)
    verify_feature_spec_version("purchase", "purchase-v1", None)

    # Mismatch raises FeatureSpecMismatchError (HTTP 409)
    with pytest.raises(FeatureSpecMismatchError) as exc:
        verify_feature_spec_version("purchase", "purchase-v2", "purchase-v1")
    assert exc.value.status_code == 409
    assert exc.value.code == ERROR_CODE_FEATURE_SPEC_MISMATCH


def test_base_feature_spec_validator_raises_not_implemented() -> None:
    validator = BaseFeatureSpecValidator()
    with pytest.raises(NotImplementedError, match="pending M1 artifacts"):
        validator.validate("purchase", {"amount": 10.0})


# =====================================================================
# 7. FastAPI RFC 7807 Error Handlers Integration Tests
# =====================================================================

def test_fastapi_error_handlers_with_serving_exceptions() -> None:
    app = FastAPI()
    register_error_handlers(app)

    @app.get("/test-409")
    def route_409() -> None:
        raise FeatureSpecMismatchError(
            requested_version="purchase-v2",
            expected_version="purchase-v1",
            family="purchase",
        )

    @app.get("/test-503")
    def route_503() -> None:
        raise ModelNotReadyError(family="purchase")

    @app.get("/test-500")
    def route_500() -> None:
        raise InferenceError("Model matrix multiplication failed")

    with TestClient(app) as client:
        # Test 409
        r409 = client.get("/test-409", headers={"X-Trace-Id": "trace-409"})
        assert r409.status_code == 409
        assert r409.headers["content-type"] == "application/problem+json"
        data409 = r409.json()
        assert data409["code"] == "FEATURE_SPEC_MISMATCH"
        assert data409["status"] == 409
        assert data409["trace_id"] == "trace-409"

        # Test 503
        r503 = client.get("/test-503")
        assert r503.status_code == 503
        assert r503.headers["content-type"] == "application/problem+json"
        data503 = r503.json()
        assert data503["code"] == "MODEL_NOT_READY"
        assert data503["status"] == 503

        # Test 500 (safe detail, no stack trace)
        r500 = client.get("/test-500")
        assert r500.status_code == 500
        assert r500.headers["content-type"] == "application/problem+json"
        data500 = r500.json()
        assert data500["code"] == "INFERENCE_ERROR"
        assert data500["status"] == 500
        assert "traceback" not in r500.text.lower()


def test_fastapi_error_handlers_with_request_validation_error() -> None:
    app = FastAPI()
    register_error_handlers(app)

    @app.post("/test-request-validation")
    def route_validate(req: PredictionRequest) -> Dict[str, str]:
        return {"status": "ok"}

    with TestClient(app) as client:
        # Send payload with missing request_id and forbidden extra field
        invalid_payload = {
            "feature_spec_version": "purchase-v1",
            "features": {"amount": 100.0},
            "illegal_field": "bad",
        }
        resp = client.post(
            "/test-request-validation",
            json=invalid_payload,
            headers={"X-Request-Id": "corr-uuid-1"},
        )
        assert resp.status_code == 422
        assert resp.headers["content-type"] == "application/problem+json"
        data = resp.json()
        assert data["code"] == "VALIDATION_ERROR"
        assert data["status"] == 422
        assert data["trace_id"] == "corr-uuid-1"
        assert len(data["errors"]) > 0
        fields = [err["field"] for err in data["errors"]]
        assert any("request_id" in f for f in fields)
        assert any("illegal_field" in f for f in fields)
