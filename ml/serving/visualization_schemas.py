"""
visualization_schemas.py — Pydantic v2 schemas and RFC 7807 exceptions for Data Visualization.

Design 2 / Final v4.2:
- Strict validation of 2D projection coordinates (rejects NaN and Inf).
- ErrorType consistency validation with true_label and predicted_label (TP, TN, FP, FN).
- Split Conformal status and ambiguity validation.
- Mandatory schema_version and metadata consistency.
- Subclasses of ServingException for standardized RFC 7807 problem detail responses.
"""

from enum import Enum
import math
from typing import Any, Dict, List, Literal, Optional, Set
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from schemas import ProblemDetail, ServingException, ValidationErrorItem


VALID_FAMILIES: Set[str] = {"purchase", "transfer"}
VALID_LABELS: Set[str] = {"legitimate", "fraud"}
VALID_ERROR_TYPES: Set[str] = {"TP", "TN", "FP", "FN"}
CANONICAL_SPECS: Dict[str, str] = {
    "purchase": "purchase-v2",
    "transfer": "transfer-v2",
}


class VisualizationMethod(str, Enum):
    """Supported 2D dimensionality reduction visualization methods."""
    PCA = "pca"
    TSNE = "tsne"
    UMAP = "umap"  # Non-operational, reserved for future extension


class ErrorType(str, Enum):
    """Confusion matrix error category."""
    TP = "TP"
    TN = "TN"
    FP = "FP"
    FN = "FN"


class VisualizationPoint(BaseModel):
    """Represents a single 2D projected transaction point for visualization."""
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "transaction_id": "tx_sparkov_0001",
                "x": 1.45,
                "y": -0.82,
                "true_label": "fraud",
                "fraud_probability": 0.89,
                "predicted_label": "fraud",
                "error_type": "TP",
                "prediction_set": ["fraud"],
                "is_ambiguous": False,
                "conformal_status": "LOADED",
                "cluster_id": 2,
                "is_outlier": False,
                "anomaly_score": -0.15,
            }
        },
    )
    transaction_id: str = Field(..., min_length=1, description="Opaque, pseudonymized transaction identifier")
    x: float = Field(..., description="Abscissa coordinate (PC1 or t-SNE 1)")
    y: float = Field(..., description="Ordinate coordinate (PC2 or t-SNE 2)")
    true_label: Optional[Literal["legitimate", "fraud"]] = Field(
        None, description="Ground truth class from M1 reference test partition"
    )
    fraud_probability: float = Field(
        ..., ge=0.0, le=1.0, description="Calibrated fraud probability output by champion model"
    )
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Field(
        None, description="Predicted class based on M1 evaluation threshold"
    )
    error_type: Optional[ErrorType] = Field(
        None, description="Confusion matrix quadrant (TP, TN, FP, FN)"
    )
    prediction_set: Optional[List[str]] = Field(
        None, description="Split Conformal prediction set, e.g. ['legitimate'] or ['legitimate', 'fraud']"
    )
    is_ambiguous: Optional[bool] = Field(
        None, description="True if conformal prediction set is ambiguous (empty or multi-class)"
    )
    conformal_status: Optional[str] = Field(
        None, description="Conformal availability state (e.g. 'LOADED', 'UNAVAILABLE', 'FAILED')"
    )
    cluster_id: Optional[int] = Field(
        None, description="Cluster identifier computed on HD features (-1 for noise)"
    )
    is_outlier: Optional[bool] = Field(
        None, description="True if statistical outlier detected by Isolation Forest"
    )
    anomaly_score: Optional[float] = Field(
        None, description="Continuous outlier score (decision function value)"
    )

    @field_validator("x", "y", mode="after")
    @classmethod
    def validate_finite_coords(cls, v: float) -> float:
        if math.isnan(v) or math.isinf(v):
            raise ValueError("Coordinates x and y must be finite numbers (non-NaN, non-Inf).")
        return v

    @field_validator("fraud_probability", mode="after")
    @classmethod
    def validate_fraud_prob(cls, v: float) -> float:
        if math.isnan(v) or not (0.0 <= v <= 1.0):
            raise ValueError("fraud_probability must be a finite float between 0.0 and 1.0.")
        return v

    @field_validator("anomaly_score", mode="after")
    @classmethod
    def validate_anomaly_score(cls, v: Optional[float]) -> Optional[float]:
        if v is not None and (math.isnan(v) or math.isinf(v)):
            raise ValueError("anomaly_score must be a finite float (non-NaN, non-Inf).")
        return v

    @field_validator("prediction_set", mode="after")
    @classmethod
    def validate_prediction_set(cls, v: Optional[List[str]]) -> Optional[List[str]]:
        if v is not None:
            for item in v:
                if item not in VALID_LABELS:
                    raise ValueError(f"Invalid label '{item}' in prediction_set. Must be one of {sorted(VALID_LABELS)}.")
            if len(v) != len(set(v)):
                raise ValueError(f"Duplicate elements detected in prediction_set: {v}")
        return v

    @model_validator(mode="after")
    def validate_consistency(self) -> "VisualizationPoint":
        # 1. ErrorType consistency with true_label and predicted_label
        if self.error_type is not None:
            if self.true_label is None or self.predicted_label is None:
                raise ValueError(
                    f"error_type '{self.error_type}' requires both true_label and predicted_label to be specified."
                )
            expected: Optional[ErrorType] = None
            if self.true_label == "fraud" and self.predicted_label == "fraud":
                expected = ErrorType.TP
            elif self.true_label == "legitimate" and self.predicted_label == "legitimate":
                expected = ErrorType.TN
            elif self.true_label == "legitimate" and self.predicted_label == "fraud":
                expected = ErrorType.FP
            elif self.true_label == "fraud" and self.predicted_label == "legitimate":
                expected = ErrorType.FN

            if self.error_type != expected:
                raise ValueError(
                    f"Inconsistent error_type '{self.error_type}': for true_label='{self.true_label}' "
                    f"and predicted_label='{self.predicted_label}', expected error_type is '{expected}'."
                )

        # 2. Conformal unavailability vs ambiguity distinction
        if self.conformal_status in ("UNAVAILABLE", "NOT_CONFIGURED", "FAILED"):
            if self.prediction_set is not None and len(self.prediction_set) > 0:
                raise ValueError(
                    f"prediction_set must be empty or None when conformal_status is '{self.conformal_status}'."
                )
            if self.is_ambiguous is True:
                raise ValueError(
                    f"is_ambiguous cannot be True when conformal_status is '{self.conformal_status}'."
                )

        return self


class VisualizationMetadata(BaseModel):
    """Metadata describing the origin, models, and sampling of the visualization payload."""
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "schema_version": "1.0.0",
                "family": "purchase",
                "method": "pca",
                "dataset_name": "Sparkov",
                "dataset_split": "test",
                "sampling_strategy": "stratified_500",
                "model_name": "purchase-fraud-model",
                "model_version": "1",
                "feature_spec_version": "purchase-v2",
                "preprocessing_version": "1.0.0",
                "evaluation_threshold": 0.12,
                "threshold_source": "M1_EVALUATION",
                "generated_at": "2026-11-18T12:00:00Z",
                "total_points_available": 2000,
                "filtered_points_count": 2000,
                "page": 1,
                "page_size": 500,
                "total_pages": 4,
                "explained_variance_ratio": [0.35, 0.22],
                "cumulative_variance_explained": 0.57,
                "fitted_split": "train",
            }
        },
    )
    schema_version: str = Field(..., min_length=1, description="Schema specification version (mandatory)")
    family: Literal["purchase", "transfer"] = Field(..., description="Model family domain")
    method: VisualizationMethod = Field(..., description="Dimensionality reduction technique")
    dataset_name: str = Field(..., min_length=1, description="Dataset origin name (e.g. 'Sparkov', 'PaySim')")
    dataset_split: str = Field(..., min_length=1, description="Evaluation partition (e.g. 'test')")
    sampling_strategy: str = Field(..., min_length=1, description="Sampling strategy description")
    model_name: str = Field(..., min_length=1, description="Associated champion model name")
    model_version: str = Field(..., min_length=1, description="MLflow model version string")
    feature_spec_version: str = Field(..., min_length=1, description="Canonical feature specification version")
    preprocessing_version: str = Field(..., min_length=1, description="Preprocessor pipeline version")
    evaluation_threshold: float = Field(..., ge=0.0, le=1.0, description="M1 technical evaluation threshold")
    threshold_source: str = Field("M1_EVALUATION", description="Source of threshold (distinct from backend policy)")
    generated_at: str = Field(..., min_length=1, description="ISO 8601 generation timestamp")
    total_points_available: int = Field(..., ge=0, description="Total points in the underlying artifact")
    filtered_points_count: int = Field(..., ge=0, description="Total points matching active query filters")
    page: int = Field(..., ge=1, description="Current page number")
    page_size: int = Field(..., ge=1, description="Requested page size")
    total_pages: int = Field(..., ge=0, description="Total pages available for current filter set")

    # Method-specific parameters
    explained_variance_ratio: Optional[List[float]] = Field(
        None, description="Variance explained by components (PCA only)"
    )
    cumulative_variance_explained: Optional[float] = Field(
        None, description="Cumulative variance explained by displayed components (PCA only)"
    )
    fitted_split: Optional[str] = Field(
        None, description="Dataset partition on which projector was fitted (PCA only, must be training)"
    )
    perplexity: Optional[float] = Field(
        None, description="t-SNE perplexity parameter"
    )
    learning_rate: Optional[str] = Field(
        None, description="t-SNE learning rate"
    )
    max_iter: Optional[int] = Field(
        None, description="t-SNE iteration count"
    )
    random_state: Optional[int] = Field(
        None, description="t-SNE random seed for reproducibility"
    )

    @field_validator("evaluation_threshold", mode="after")
    @classmethod
    def validate_eval_threshold(cls, v: float) -> float:
        if math.isnan(v) or not (0.0 <= v <= 1.0):
            raise ValueError("evaluation_threshold must be a finite float between 0.0 and 1.0.")
        return v

    @field_validator("explained_variance_ratio", mode="after")
    @classmethod
    def validate_variance_ratio(cls, v: Optional[List[float]]) -> Optional[List[float]]:
        if v is not None:
            for item in v:
                if math.isnan(item) or math.isinf(item):
                    raise ValueError("All elements in explained_variance_ratio must be finite numbers.")
        return v

    @field_validator("cumulative_variance_explained", "perplexity", mode="after")
    @classmethod
    def validate_optional_finites(cls, v: Optional[float]) -> Optional[float]:
        if v is not None and (math.isnan(v) or math.isinf(v)):
            raise ValueError("Numerical metadata fields must be finite numbers (non-NaN, non-Inf).")
        return v

    @model_validator(mode="after")
    def validate_metadata_consistency(self) -> "VisualizationMetadata":
        # Check feature_spec_version matches family
        expected_spec = CANONICAL_SPECS.get(self.family)
        if expected_spec and self.feature_spec_version != expected_spec:
            raise ValueError(
                f"feature_spec_version mismatch: family '{self.family}' requires '{expected_spec}', "
                f"got '{self.feature_spec_version}'."
            )

        # Check model_name corresponds to family
        if self.family not in self.model_name.lower():
            raise ValueError(
                f"model_name '{self.model_name}' is inconsistent with family '{self.family}'."
            )

        # UMAP operational guard
        if self.method == VisualizationMethod.UMAP:
            raise ValueError("UMAP is not currently operational or supported.")

        return self


class VisualizationResponse(BaseModel):
    """Complete REST response payload returned by /visualization/{family}/{method}."""
    model_config = ConfigDict(
        extra="forbid",
        json_schema_extra={
            "example": {
                "metadata": {
                    "schema_version": "1.0.0",
                    "family": "purchase",
                    "method": "pca",
                    "dataset_name": "Sparkov",
                    "dataset_split": "test",
                    "sampling_strategy": "stratified_500",
                    "model_name": "purchase-fraud-model",
                    "model_version": "1",
                    "feature_spec_version": "purchase-v2",
                    "preprocessing_version": "1.0.0",
                    "evaluation_threshold": 0.12,
                    "threshold_source": "M1_EVALUATION",
                    "generated_at": "2026-11-18T12:00:00Z",
                    "total_points_available": 2000,
                    "filtered_points_count": 2000,
                    "page": 1,
                    "page_size": 500,
                    "total_pages": 4,
                    "explained_variance_ratio": [0.35, 0.22],
                    "cumulative_variance_explained": 0.57,
                    "fitted_split": "train",
                },
                "points": [
                    {
                        "transaction_id": "tx_sparkov_0001",
                        "x": 1.45,
                        "y": -0.82,
                        "true_label": "fraud",
                        "fraud_probability": 0.89,
                        "predicted_label": "fraud",
                        "error_type": "TP",
                        "prediction_set": ["fraud"],
                        "is_ambiguous": False,
                        "conformal_status": "LOADED",
                        "cluster_id": 2,
                        "is_outlier": False,
                        "anomaly_score": -0.15,
                    }
                ],
            }
        },
    )
    metadata: VisualizationMetadata
    points: List[VisualizationPoint]


class VisualizationArtifact(BaseModel):
    """
    On-disk JSON artifact schema produced by M1 and loaded by VisualizationManager.
    Guarantees integrity, uniqueness of transaction IDs, and version compatibility.
    """
    schema_version: str = Field(..., min_length=1, description="Mandatory schema version")
    family: Literal["purchase", "transfer"]
    method: VisualizationMethod
    dataset_name: str = Field(..., min_length=1)
    dataset_split: str = Field(..., min_length=1)
    sampling_strategy: str = Field(..., min_length=1)
    model_name: str = Field(..., min_length=1)
    model_version: str = Field(..., min_length=1)
    feature_spec_version: str = Field(..., min_length=1)
    preprocessing_version: str = Field(..., min_length=1)
    evaluation_threshold: float = Field(..., ge=0.0, le=1.0)
    threshold_source: str = Field("M1_EVALUATION")
    generated_at: str = Field(..., min_length=1)

    # Optional parameters
    explained_variance_ratio: Optional[List[float]] = None
    cumulative_variance_explained: Optional[float] = None
    fitted_split: Optional[str] = None
    perplexity: Optional[float] = None
    learning_rate: Optional[str] = None
    max_iter: Optional[int] = None
    random_state: Optional[int] = None

    points: List[VisualizationPoint] = Field(..., description="Collection of projected points")

    @field_validator("evaluation_threshold", mode="after")
    @classmethod
    def validate_artifact_threshold(cls, v: float) -> float:
        if math.isnan(v) or not (0.0 <= v <= 1.0):
            raise ValueError("evaluation_threshold must be a finite float between 0.0 and 1.0.")
        return v

    @model_validator(mode="after")
    def validate_artifact_integrity(self) -> "VisualizationArtifact":
        # 1. Unique transaction IDs requirement
        ids = [p.transaction_id for p in self.points]
        if len(ids) != len(set(ids)):
            seen = set()
            duplicates = set()
            for id_ in ids:
                if id_ in seen:
                    duplicates.add(id_)
                seen.add(id_)
            raise ValueError(f"Duplicate transaction_id detected in artifact: {sorted(list(duplicates))[:5]}")

        # 2. UMAP guard
        if self.method == VisualizationMethod.UMAP:
            raise ValueError("UMAP is not currently operational or supported.")

        # 3. Family consistency
        expected_spec = CANONICAL_SPECS.get(self.family)
        if expected_spec and self.feature_spec_version != expected_spec:
            raise ValueError(
                f"feature_spec_version mismatch: family '{self.family}' requires '{expected_spec}', "
                f"got '{self.feature_spec_version}'."
            )
        if self.family not in self.model_name.lower():
            raise ValueError(
                f"model_name '{self.model_name}' is inconsistent with family '{self.family}'."
            )

        return self


# ==============================================================================
# RFC 7807 Domain Exceptions for Data Visualization
# ==============================================================================

class VisualizationNotFoundError(ServingException):
    """HTTP 404 — VISUALIZATION_NOT_FOUND: Artifact or resource could not be found."""
    def __init__(self, detail: str) -> None:
        super().__init__(
            status_code=404,
            code="VISUALIZATION_NOT_FOUND",
            title="Visualization Not Found",
            detail=detail,
            errors=[ValidationErrorItem(field="visualization", message=detail)],
        )


class VisualizationMethodNotSupportedError(ServingException):
    """HTTP 404 — METHOD_NOT_SUPPORTED: Requested visualization method is unsupported."""
    def __init__(self, method: str) -> None:
        detail = f"Visualization method '{method}' is not supported or operational."
        super().__init__(
            status_code=404,
            code="METHOD_NOT_SUPPORTED",
            title="Visualization Method Not Supported",
            detail=detail,
            errors=[ValidationErrorItem(field="method", message=detail)],
        )


class VisualizationCorruptedError(ServingException):
    """HTTP 503 — VISUALIZATION_CORRUPTED: Artifact is unreadable, corrupted, or invalid."""
    def __init__(self, detail: str) -> None:
        super().__init__(
            status_code=503,
            code="VISUALIZATION_CORRUPTED",
            title="Visualization Artifact Corrupted",
            detail=detail,
            errors=[ValidationErrorItem(field="artifact", message=detail)],
        )


class VisualizationValidationError(ServingException):
    """HTTP 422 — VISUALIZATION_VALIDATION_ERROR: Query parameters or payload failed validation."""
    def __init__(self, detail: str, errors: Optional[List[ValidationErrorItem]] = None) -> None:
        super().__init__(
            status_code=422,
            code="VISUALIZATION_VALIDATION_ERROR",
            title="Visualization Validation Error",
            detail=detail,
            errors=errors or [ValidationErrorItem(field="query", message=detail)],
        )
