"""
schemas.py — Pydantic Validation Schemas & Error Contract for Member 2 (ML Serving).

Design 2 Architecture (Final v4):
1. Generic PredictionRequest (Design 2 Section 15.3):
   - request_id (RFC 4122 UUID validation)
   - feature_spec_version (non-empty string)
   - features (generic Dict[str, Any], extra="forbid" on top-level)
2. PredictionResponse (Design 2 Section 15.3 — structural schema only, no prediction logic)
3. ExplanationResponse & FeatureContribution (Design 2 Section 15.3)
4. ModelMetadata & ModelsListResponse (Design 2 Section 15.2 proposal)
5. Standardized Errors — RFC 7807 Problem Details (Design 2 Section 18.1 & 18.2):
   - HTTP 422: FEATURE_VALIDATION_ERROR / VALIDATION_ERROR
   - HTTP 409: FEATURE_SPEC_MISMATCH
   - HTTP 503: MODEL_NOT_READY
   - HTTP 500: INFERENCE_ERROR
6. Feature specification validation boundary interfaces
7. Backward compatibility: TransactionInput (Contract 1 legacy) & HealthResponse
"""

from datetime import date, datetime, timezone
from typing import Any, Dict, List, Literal, Optional, Union
import uuid

from pydantic import (
    AliasChoices,
    BaseModel,
    ConfigDict,
    Field,
    field_validator,
    model_validator,
)


# =====================================================================
# 1. Backward-Compatible Schemas (Legacy Contract 1 & Probes)
# =====================================================================

class TransactionInput(BaseModel):
    """
    Legacy raw transaction input schema (Contract 1 Sparkov 10-feature model).
    Preserved for backward compatibility with existing tests; not used for Design 2 endpoints.
    """
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "trans_date_trans_time": "2020-06-21T02:37:00",
                "amt": 850.00,
                "category": "grocery_pos",
                "gender": "F",
                "dob": "1987-04-19",
                "lat": 33.9659,
                "long": -80.9455,
                "city_pop": 149,
                "merch_lat": 33.986391,
                "merch_long": -81.200714,
            }
        },
    )

    # 1. Transaction timestamp
    trans_date_trans_time: datetime = Field(
        ...,
        description="Transaction timestamp (ISO 8601). Used to derive hour, dow, is_night.",
    )

    # 2. Amount
    amt: float = Field(
        ...,
        gt=0.0,
        description="Transaction amount in USD. Must be strictly positive.",
    )

    # 3. Category
    category: str = Field(
        ...,
        min_length=1,
        description="Merchant category.",
    )

    # 4. Gender
    gender: Literal["M", "F"] = Field(
        ...,
        description="Cardholder gender. Must be 'M' or 'F'.",
    )

    # 5. Date of Birth
    dob: date = Field(
        ...,
        description="Cardholder date of birth (YYYY-MM-DD).",
    )

    # 6. Latitude
    lat: float = Field(
        ...,
        ge=-90.0,
        le=90.0,
        description="Cardholder home latitude [-90, 90].",
    )

    # 7. Longitude
    long: float = Field(
        ...,
        ge=-180.0,
        le=180.0,
        description="Cardholder home longitude [-180, 180].",
    )

    # 8. City Population
    city_pop: int = Field(
        ...,
        gt=0,
        description="City population. Must be strictly positive.",
    )

    # 9. Merchant Latitude
    merch_lat: float = Field(
        ...,
        ge=-90.0,
        le=90.0,
        description="Merchant latitude [-90, 90].",
    )

    # 10. Merchant Longitude
    merch_long: float = Field(
        ...,
        ge=-180.0,
        le=180.0,
        description="Merchant longitude [-180, 180].",
    )

    @model_validator(mode="after")
    def validate_dob_before_transaction(self) -> "TransactionInput":
        """Cardholder must be born strictly before the transaction date."""
        tx_date = self.trans_date_trans_time.date()
        if self.dob >= tx_date:
            raise ValueError(
                f"Invalid date of birth: dob ({self.dob}) must be strictly before transaction date ({tx_date})."
            )
        return self


class HealthResponse(BaseModel):
    """Health response model for /health/live, /health/ready, and /health/startup."""
    model_config = ConfigDict(extra="forbid")

    status: str = Field(..., description="Service status ('UP' or 'DOWN').")
    service: str = Field(default="ml-serving", description="Service identifier.")
    environment: str = Field(..., description="Active runtime environment.")
    model_loaded: bool = Field(..., description="Whether the ML model is currently cached in memory.")
    model_name: Optional[str] = Field(None, description="Name of the registered ML model.")
    model_version: Optional[str] = Field(None, description="Version of the loaded model.")
    detail: Optional[str] = Field(None, description="Additional status details.")


# =====================================================================
# 2. Design 2 Request Schemas
# =====================================================================

class PredictionRequest(BaseModel):
    """
    Generic feature request schema for Design 2 serving endpoints (/predict/purchase, /predict/transfer).
    Supports both model families without hard-coding family-specific features until M1's
    authoritative feature specifications are published.
    """
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "request_id": "e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
                "feature_spec_version": "purchase-v1",
                "features": {
                    "amount": 120.0,
                    "log_amount": 4.79,
                    "merchant_category": "shopping_net",
                    "hour_of_day": 23,
                    "distance_home_merchant_km": 812.4,
                    "txn_count_24h": 6,
                },
            }
        },
    )

    request_id: str = Field(
        ...,
        description="Client or gateway correlation UUID (RFC 4122).",
    )
    feature_spec_version: str = Field(
        ...,
        min_length=1,
        description="Feature specification version contract (e.g., 'purchase-v1', 'transfer-v1').",
    )
    features: Dict[str, Any] = Field(
        ...,
        description="Map of feature names to feature values (numeric, boolean, or categorical string).",
    )

    @field_validator("request_id")
    @classmethod
    def validate_request_id_uuid(cls, v: str) -> str:
        """Validates that request_id is a valid RFC 4122 UUID string."""
        if not isinstance(v, str) or not v.strip():
            raise ValueError("request_id must be a non-empty UUID string.")
        try:
            parsed = uuid.UUID(v.strip())
            return str(parsed)
        except (ValueError, AttributeError):
            raise ValueError(f"request_id '{v}' is not a valid RFC 4122 UUID string.")

    @field_validator("feature_spec_version")
    @classmethod
    def validate_feature_spec_version(cls, v: str) -> str:
        """Validates that feature_spec_version is non-empty after stripping whitespace."""
        stripped = v.strip()
        if not stripped:
            raise ValueError("feature_spec_version cannot be empty or whitespace.")
        return stripped

    @field_validator("features")
    @classmethod
    def validate_features_payload(cls, v: Dict[str, Any]) -> Dict[str, Any]:
        """Validates that the features dictionary is non-empty with valid string keys and non-null values."""
        if not isinstance(v, dict):
            raise ValueError("features must be a dictionary.")
        if len(v) == 0:
            raise ValueError("features payload cannot be empty.")
        for k, val in v.items():
            if not isinstance(k, str) or not k.strip():
                raise ValueError(f"Feature name '{k}' must be a non-empty string.")
            if val is None:
                raise ValueError(f"Feature '{k}' value cannot be null.")
        return v


# =====================================================================
# 3. Design 2 Prediction Response Schema (Structural preparation only)
# =====================================================================

class PredictionResponse(BaseModel):
    """
    Structural response contract for Design 2 inference endpoints (/predict/purchase, /predict/transfer).

    NOTE ON AMBIGUITY:
    The Design 2 / Final v4 specification shows an example `"label": "FRAUD"`. However, the full
    set of permitted label strings (e.g. "FRAUD" vs "LEGITIMATE", "APPROVED" vs "DECLINED",
    or integer 0/1) is not formally enumerated in section 15.3. We define `label: str` to accommodate
    the required Design 2 string output, and flag this ambiguity for team alignment before Step 3.

    IMPORTANT:
    This schema defines structure only. No inference, calibration, probability calculation,
    or threshold logic is implemented in this step.
    """
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "request_id": "e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
                "label": "FRAUD",
                "fraud_probability": 0.94,
                "uncertainty": 0.08,
                "model_name": "purchase-fraud-model",
                "model_version": "3",
                "feature_spec_version": "purchase-v1",
                "inference_ms": 18.0,
            }
        },
    )

    request_id: str = Field(..., description="Echoes the request_id from PredictionRequest.")
    label: str = Field(..., min_length=1, description="Classification label (e.g. 'FRAUD' or 'LEGITIMATE').")
    fraud_probability: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Calibrated probability of fraud in range [0.0, 1.0].",
    )
    uncertainty: float = Field(
        ...,
        ge=0.0,
        le=1.0,
        description="Uncertainty score of the prediction in range [0.0, 1.0].",
    )
    model_name: str = Field(..., min_length=1, description="Registered name of the model in MLflow.")
    model_version: str = Field(..., min_length=1, description="Resolved version string of the loaded model.")
    feature_spec_version: str = Field(..., min_length=1, description="Feature specification version used for inference.")
    inference_ms: float = Field(..., ge=0.0, description="Server-side inference execution time in milliseconds.")


# =====================================================================
# 4. Design 2 Explanation Schemas (Structural preparation only)
# =====================================================================

class FeatureContribution(BaseModel):
    """
    Single feature attribution item for explainability endpoints (/explain/purchase, /explain/transfer).
    Supports both Design 2 Section 15.3 format (feature, value, shap) and aliases (feature_name, feature_value, contribution).
    """
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    feature: str = Field(
        ...,
        validation_alias=AliasChoices("feature", "feature_name"),
        description="Name of the input feature.",
    )
    value: Any = Field(
        ...,
        validation_alias=AliasChoices("value", "feature_value"),
        description="Evaluated value of the input feature.",
    )
    shap: float = Field(
        ...,
        validation_alias=AliasChoices("shap", "shap_value", "contribution"),
        description="SHAP attribution value. Positive pushes towards fraud, negative towards legitimate.",
    )


class ExplanationResponse(BaseModel):
    """
    Structural response contract for Design 2 explainability endpoints.

    IMPORTANT:
    This schema defines structure only. SHAP calculation is NOT implemented in Step 2.
    """
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "request_id": "e2c4a8f0-1111-4a6b-8f2e-aaaaaaaaaaaa",
                "model_name": "purchase-fraud-model",
                "model_version": "3",
                "base_value": 0.05,
                "contributions": [
                    {"feature": "distance_home_merchant_km", "value": 812.4, "shap": 0.42},
                    {"feature": "amount", "value": 120.0, "shap": 0.28},
                    {"feature": "hour_of_day", "value": 23, "shap": 0.15},
                ],
            }
        },
    )

    request_id: str = Field(..., description="Correlation request ID.")
    model_name: str = Field(..., min_length=1, description="Registered model name.")
    model_version: str = Field(..., min_length=1, description="Resolved model version.")
    base_value: float = Field(..., description="Base value (expected model output) from the training background.")
    contributions: List[FeatureContribution] = Field(
        default_factory=list,
        description="List of feature contributions, ordered by absolute impact.",
    )


# =====================================================================
# 5. Model Metadata Schemas (Design 2 Section 15.2 proposal)
# =====================================================================

class ModelMetadata(BaseModel):
    """
    Describes the runtime state and coordinates of an individual model family.
    Reuses state concepts from ModelEntry and ModelRegistry.
    """
    model_config = ConfigDict(extra="forbid")

    family: Literal["purchase", "transfer"] = Field(..., description="Model family identifier.")
    model_name: str = Field(..., description="Registered MLflow model name.")
    model_version: Optional[str] = Field(None, description="Loaded immutable model version.")
    alias: str = Field(default="champion", description="Registered model alias.")
    feature_spec_version: Optional[str] = Field(None, description="Feature specification version supported by model.")
    status: str = Field(..., description="Loading status: 'READY', 'LOADING', 'FAILED', or 'UNLOADED'.")
    loaded_at: Optional[float] = Field(None, description="UNIX timestamp when model was loaded into memory.")

    @classmethod
    def from_model_entry(
        cls,
        entry: Optional[Any],
        family: str,
        status: str,
        default_model_name: str = "",
    ) -> "ModelMetadata":
        """Factory creating ModelMetadata from a ModelEntry or unready state."""
        if entry is None:
            return cls(
                family=family,  # type: ignore[arg-type]
                model_name=default_model_name,
                model_version=None,
                alias="champion",
                feature_spec_version=None,
                status=status,
                loaded_at=None,
            )
        return cls(
            family=entry.family,  # type: ignore[arg-type]
            model_name=entry.model_name,
            model_version=entry.model_version,
            alias=entry.model_alias,
            feature_spec_version=entry.feature_spec_version,
            status=status,
            loaded_at=entry.loaded_at,
        )


class ModelsListResponse(BaseModel):
    """
    Proposed minimal structure for GET /models endpoint.

    PROPOSAL NOTE:
    Design 2 Section 15.2 specifies the endpoint purpose ('Loaded model names, versions,
    feature spec versions, load time') without formally defining the enclosing JSON schema.
    This schema is a proposed representation encapsulating all supported model families.
    """
    model_config = ConfigDict(extra="forbid")

    models: Dict[str, ModelMetadata] = Field(
        ...,
        description="Dictionary mapping family ('purchase', 'transfer') to its runtime ModelMetadata.",
    )
    all_loaded: bool = Field(..., description="True if both purchase and transfer models are ready to serve.")


class ReloadResponse(BaseModel):
    """
    Response model for administrative model reload (POST /admin/reload/{family}).
    Accurately reflects whether the reload was completed synchronously (HTTP 200)
    or accepted for background execution (HTTP 202).
    """
    model_config = ConfigDict(extra="forbid")

    status: Literal["COMPLETED", "ACCEPTED"] = Field(
        ...,
        description="Reload execution status ('COMPLETED' for synchronous success, 'ACCEPTED' for background scheduling).",
    )
    family: Literal["purchase", "transfer"] = Field(..., description="Target model family.")
    message: str = Field(..., description="Human-readable outcome description.")
    model: Optional[ModelMetadata] = Field(
        None,
        description="Metadata of the active model after reload, if completed synchronously.",
    )


# =====================================================================
# 6. Standardized Errors — RFC 7807 Problem Details
# =====================================================================

# Authoritative Error Codes for ML Serving (Design 2 Section 15.4 & Section 18.2)
ERROR_CODE_FEATURE_VALIDATION: str = "FEATURE_VALIDATION_ERROR"  # HTTP 422
ERROR_CODE_FEATURE_SPEC_MISMATCH: str = "FEATURE_SPEC_MISMATCH"  # HTTP 409
ERROR_CODE_MODEL_NOT_READY: str = "MODEL_NOT_READY"              # HTTP 503
ERROR_CODE_INFERENCE_ERROR: str = "INFERENCE_ERROR"              # HTTP 500
ERROR_CODE_VALIDATION_ERROR: str = "VALIDATION_ERROR"            # HTTP 422
ERROR_CODE_MALFORMED_REQUEST: str = "MALFORMED_REQUEST"          # HTTP 400
ERROR_CODE_UNAUTHORIZED: str = "UNAUTHORIZED"                    # HTTP 401
ERROR_CODE_FORBIDDEN: str = "FORBIDDEN"                          # HTTP 403
ERROR_CODE_RELOAD_IN_PROGRESS: str = "RELOAD_IN_PROGRESS"        # HTTP 409
ERROR_CODE_CONFLICT: str = "CONFLICT"                            # HTTP 409


class ValidationErrorItem(BaseModel):
    """Structured validation error detail for RFC 7807 problem envelopes."""
    model_config = ConfigDict(extra="forbid")

    field: Optional[str] = Field(None, description="Location or field name of the invalid parameter.")
    message: str = Field(..., description="Specific validation failure message.")


class ProblemDetail(BaseModel):
    """
    Standardized RFC 7807 Problem Details response envelope matching Design 2 Section 18.1.

    Security Guarantee:
    Stack traces, internal file paths, credentials, and raw system exceptions
    are never exposed in client-facing responses.
    """
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "type": "https://vigilia.example/errors/feature-validation-error",
                "code": "FEATURE_VALIDATION_ERROR",
                "title": "Feature validation failed",
                "status": 422,
                "detail": "Feature 'amount' is missing or out of valid range.",
                "trace_id": "4f0d2a...",
                "timestamp": "2026-10-26T18:00:00Z",
                "errors": [
                    {"field": "features.amount", "message": "Field required"}
                ],
            }
        },
    )

    type: str = Field(
        default="about:blank",
        description="URI reference that identifies the problem type.",
    )
    code: str = Field(
        ...,
        description="Machine-readable application error code from the Design 2 Error Catalogue.",
    )
    title: str = Field(
        ...,
        description="Short, human-readable summary of the problem type.",
    )
    status: int = Field(
        ...,
        description="HTTP status code applicable to this problem.",
    )
    detail: str = Field(
        ...,
        description="Human-readable explanation specific to this occurrence of the problem.",
    )
    trace_id: Optional[str] = Field(
        None,
        description="Correlation or trace identifier for log auditing and debugging.",
    )
    timestamp: Optional[str] = Field(
        None,
        description="ISO 8601 UTC timestamp of the error occurrence.",
    )
    errors: Optional[List[ValidationErrorItem]] = Field(
        None,
        description="Detailed field-level validation issues if applicable.",
    )


# =====================================================================
# 7. Domain Exception Hierarchy
# =====================================================================

class ServingException(Exception):
    """Base domain exception for ML serving errors conforming to RFC 7807."""

    def __init__(
        self,
        status_code: int,
        code: str,
        title: str,
        detail: str,
        errors: Optional[List[ValidationErrorItem]] = None,
        error_type: Optional[str] = None,
    ) -> None:
        super().__init__(detail)
        self.status_code = status_code
        self.code = code
        self.title = title
        self.detail = detail
        self.errors = errors
        self.error_type = error_type or f"https://vigilia.example/errors/{code.lower().replace('_', '-')}"

    def to_problem_detail(self, trace_id: Optional[str] = None) -> ProblemDetail:
        """Converts the exception to an RFC 7807 ProblemDetail Pydantic model."""
        return ProblemDetail(
            type=self.error_type,
            code=self.code,
            title=self.title,
            status=self.status_code,
            detail=self.detail,
            trace_id=trace_id,
            timestamp=datetime.now(timezone.utc).isoformat(),
            errors=self.errors,
        )


class FeatureValidationError(ServingException):
    """HTTP 422 — FEATURE_VALIDATION_ERROR: Feature missing, unknown, or out of range."""

    def __init__(self, detail: str, errors: Optional[List[ValidationErrorItem]] = None) -> None:
        super().__init__(
            status_code=422,
            code=ERROR_CODE_FEATURE_VALIDATION,
            title="Feature validation failed",
            detail=detail,
            errors=errors,
        )


class FeatureSpecMismatchError(ServingException):
    """HTTP 409 — FEATURE_SPEC_MISMATCH: Incoming feature_spec_version differs from loaded model."""

    def __init__(self, requested_version: str, expected_version: str, family: str) -> None:
        detail = (
            f"Feature specification version mismatch for family '{family}': "
            f"requested '{requested_version}', but loaded model requires '{expected_version}'."
        )
        super().__init__(
            status_code=409,
            code=ERROR_CODE_FEATURE_SPEC_MISMATCH,
            title="Feature specification version conflict",
            detail=detail,
        )


class ModelNotReadyError(ServingException):
    """HTTP 503 — MODEL_NOT_READY: Requested model family is still loading or unavailable."""

    def __init__(self, family: str, detail: Optional[str] = None) -> None:
        super().__init__(
            status_code=503,
            code=ERROR_CODE_MODEL_NOT_READY,
            title="Model family not ready",
            detail=detail or f"Model family '{family}' is not loaded. Download or retry is in progress.",
        )


class InferenceError(ServingException):
    """HTTP 500 — INFERENCE_ERROR: Unexpected error during model inference."""

    def __init__(self, detail: str = "An unexpected error occurred during model inference.") -> None:
        # Stack traces and credentials are never placed in detail to prevent leaking internals
        super().__init__(
            status_code=500,
            code=ERROR_CODE_INFERENCE_ERROR,
            title="Internal inference error",
            detail=detail,
        )


class UnauthorizedError(ServingException):
    """HTTP 401 — UNAUTHORIZED: Missing or invalid authentication credentials."""

    def __init__(self, detail: str = "Authentication credentials are missing or invalid.") -> None:
        super().__init__(
            status_code=401,
            code=ERROR_CODE_UNAUTHORIZED,
            title="Authentication required",
            detail=detail,
        )


class ConflictError(ServingException):
    """HTTP 409 — RELOAD_IN_PROGRESS: Operation conflicts with in-progress state."""

    def __init__(
        self,
        detail: str = "Operation conflicts with current resource state.",
        code: str = ERROR_CODE_RELOAD_IN_PROGRESS,
    ) -> None:
        super().__init__(
            status_code=409,
            code=code,
            title="Operation conflict",
            detail=detail,
        )


# =====================================================================
# 8. Feature Specification Validation Boundary Interfaces
# =====================================================================

def verify_feature_spec_version(
    family: str,
    requested_spec_version: str,
    active_model_spec_version: Optional[str],
) -> None:
    """
    Validates that the incoming feature_spec_version matches the loaded model's version.
    Raises FeatureSpecMismatchError (HTTP 409) if they differ.
    If the active model does not specify a version (e.g. dummy/unversioned), validation passes.
    """
    if active_model_spec_version is not None and requested_spec_version != active_model_spec_version:
        raise FeatureSpecMismatchError(
            requested_version=requested_spec_version,
            expected_version=active_model_spec_version,
            family=family,
        )


class BaseFeatureSpecValidator:
    """
    Abstract boundary interface for family-specific feature validation.

    NOTE ON DEPENDENCY ON M1:
    The authoritative schema, types, and range rules for Purchase ('purchase-fraud-model') and
    Transfer ('transfer-fraud-model') depend on M1's unpublished `feature_spec.json` artifacts.
    This boundary defines the contract that will be plugged in once M1 publishes those specs.
    """

    def validate(self, family: str, features: Dict[str, Any]) -> None:
        """
        Validates feature keys, types, and ranges for the given family.
        Raises FeatureValidationError (HTTP 422) if validation fails.
        """
        raise NotImplementedError("Authoritative feature specification validator pending M1 artifacts.")


# =====================================================================
# 9. FastAPI RFC 7807 Error Handler Registration
# =====================================================================

def register_error_handlers(app: Any) -> None:
    """
    Registers RFC 7807 problem detail exception handlers on a FastAPI application.

    Ensures:
    - ServingException (and its subclasses) return ProblemDetail with application/problem+json.
    - RequestValidationError returns RFC 7807 ProblemDetail (HTTP 422, VALIDATION_ERROR).
    - Client-facing error responses never expose internal stack traces or secrets.
    """
    from fastapi import Request
    from fastapi.exceptions import RequestValidationError
    from fastapi.responses import JSONResponse

    @app.exception_handler(ServingException)
    async def serving_exception_handler(request: Request, exc: ServingException) -> JSONResponse:
        trace_id = (
            getattr(request.state, "trace_id", None)
            or request.headers.get("X-Trace-Id")
            or request.headers.get("X-Request-Id")
        )
        problem = exc.to_problem_detail(trace_id=trace_id)
        return JSONResponse(
            status_code=exc.status_code,
            content=problem.model_dump(exclude_none=True),
            media_type="application/problem+json",
        )

    @app.exception_handler(RequestValidationError)
    async def validation_exception_handler(request: Request, exc: RequestValidationError) -> JSONResponse:
        trace_id = (
            getattr(request.state, "trace_id", None)
            or request.headers.get("X-Trace-Id")
            or request.headers.get("X-Request-Id")
        )
        formatted_errors: List[ValidationErrorItem] = []
        for err in exc.errors():
            loc = ".".join(str(part) for part in err.get("loc", []))
            msg = err.get("msg", "Validation error")
            formatted_errors.append(ValidationErrorItem(field=loc or None, message=msg))

        problem = ProblemDetail(
            type="https://vigilia.example/errors/validation-error",
            code=ERROR_CODE_VALIDATION_ERROR,
            title="Request validation failed",
            status=422,
            detail="The request body or parameters failed schema validation.",
            trace_id=trace_id,
            timestamp=datetime.now(timezone.utc).isoformat(),
            errors=formatted_errors if formatted_errors else None,
        )
        return JSONResponse(
            status_code=422,
            content=problem.model_dump(exclude_none=True),
            media_type="application/problem+json",
        )
