"""
model_loader.py — Multi-Model Registry & Background Loader for Member 2 (ML Serving).

Design 2 Architecture:
1. Thread-safe ModelRegistry tracking independent model families:
   - purchase -> purchase-fraud-model@champion
   - transfer -> transfer-fraud-model@champion
2. Independent loading state, versioning, and error tracking per family.
3. Isolated failure handling: failure in one family never corrupts or unloads the other family.
4. Active model preservation: if a replacement load fails, the existing active model remains serving.
5. In-memory dummy models for both families (strictly permitted only in 'local' and 'test').
6. Non-blocking background loader with bounded exponential backoff.
7. Backward-compatible helper functions for legacy single-model callers.
"""

from dataclasses import dataclass, field
import logging
import math
import os
import threading
import time
from typing import Any, Dict, List, Optional, Tuple, Union

import mlflow
import mlflow.pyfunc
import mlflow.sklearn
from mlflow.tracking import MlflowClient
import numpy as np
import pandas as pd

from config import get_settings
from conformal_loader import (
    ConformalLoadResult,
    ConformalLoadStatus,
    MLflowConformalAdapter,
)
from schemas import (
    FeatureContribution,
    FeatureSpecMismatchError,
    FeatureSpecValidator,
    FeatureValidationError,
    ModelNotReadyError,
)

try:
    import shap
except ImportError:
    shap = None

logger = logging.getLogger("ml_serving.model_loader")

_SHAP_PATCH_LOCK = threading.Lock()


def _patch_shap_xgboost_parser() -> None:
    """
    Safely patches shap's internal XGBTreeModelLoader UBJSON decoder.

    In XGBoost 3.0+ (xgboost==3.4.1), learner_model_param['base_score']
    is serialized as a string with brackets (e.g. '[5E-1]'). SHAP 0.46.0's
    XGBTreeModelLoader attempts float(base_score), raising:
        ValueError: could not convert string to float: '[5E-1]'

    This hook intercepts the decoded UBJSON dictionary and strips outer brackets
    from 'base_score' if present, allowing float() conversion to succeed.
    Unexpected decoding errors from orig_decode are preserved and propagate normally.
    """
    if shap is None or not hasattr(shap, "explainers"):
        return
    tree_mod = getattr(shap.explainers, "_tree", None)
    if tree_mod is None or not hasattr(tree_mod, "decode_ubjson_buffer"):
        return

    with _SHAP_PATCH_LOCK:
        orig_decode = tree_mod.decode_ubjson_buffer
        if getattr(orig_decode, "_vigilia_patched", False):
            return

        def safe_decode_ubjson_buffer(fd: Any) -> Any:
            jmodel = orig_decode(fd)
            try:
                learner = jmodel.get("learner")
                if isinstance(learner, dict):
                    param = learner.get("learner_model_param")
                    if isinstance(param, dict) and "base_score" in param:
                        raw_base = param["base_score"]
                        if isinstance(raw_base, str):
                            val = raw_base.strip()
                            if val.startswith("[") and val.endswith("]"):
                                param["base_score"] = val[1:-1].strip()
            except Exception as patch_exc:
                logger.debug("SHAP base_score unbracket hook encountered non-fatal error: %s", patch_exc)
            return jmodel

        safe_decode_ubjson_buffer._vigilia_patched = True  # type: ignore[attr-defined]
        tree_mod.decode_ubjson_buffer = safe_decode_ubjson_buffer


@dataclass
class ModelEntry:
    """Represents an active, loaded model family entry in memory."""
    family: str                          # "purchase" or "transfer"
    model_name: str                      # e.g. "purchase-fraud-model"
    model_version: str                   # resolved immutable version, e.g. "1" or "dummy-purchase-v1"
    model_alias: str                     # e.g. "champion"
    model: Any                           # model / pipeline instance
    loaded_at: float = field(default_factory=time.time)
    feature_spec_version: Optional[str] = None
    explainer: Optional[Any] = None       # ModelExplainer instance if initialized
    explainer_error: Optional[str] = None # Technical reason if explainer initialization failed
    conformal_config: Optional[Any] = None    # Validated ConformalConfig instance if available
    conformal_evaluator: Optional[Any] = None # ConformalEvaluator instance if available
    conformal_error: Optional[str] = None     # Technical reason if conformal evaluation failed

    @property
    def is_conformal_available(self) -> bool:
        """Returns True if a ConformalEvaluator is initialized and ready on this entry."""
        return self.conformal_evaluator is not None


class DummyFraudModel:
    """Base in-memory stub model for local development and test suites."""

    def __init__(self, version: str = "dummy-v1", family: str = "generic") -> None:
        self.version = version
        self.family = family
        self.classes_ = np.array([0, 1])

    def predict_proba(self, X: Any) -> Any:
        import numpy as np
        # Returns [P(legitimate)=0.85, P(fraud)=0.15]
        n_samples = len(X) if hasattr(X, "__len__") else 1
        return np.tile([0.85, 0.15], (n_samples, 1))

    def predict(self, X: Any) -> Any:
        import numpy as np
        n_samples = len(X) if hasattr(X, "__len__") else 1
        return np.zeros(n_samples, dtype=int)


class DummyPurchaseModel(DummyFraudModel):
    """Dummy model for the purchase fraud detection family."""

    def __init__(self, version: str = "dummy-purchase-v1") -> None:
        super().__init__(version=version, family="purchase")


class DummyTransferModel(DummyFraudModel):
    """Dummy model for the transfer fraud detection family."""

    def __init__(self, version: str = "dummy-transfer-v1") -> None:
        super().__init__(version=version, family="transfer")


def create_dummy_model(family: str, version: Optional[str] = None) -> DummyFraudModel:
    """Creates a family-specific dummy model (strictly allowed only in 'local' and 'test' environments)."""
    settings = get_settings()
    if settings.APP_ENV not in ("local", "test"):
        raise RuntimeError(
            f"Security violation: Dummy models are forbidden in '{settings.APP_ENV}'. "
            "Dummy models are strictly restricted to 'local' and 'test' environments."
        )
    if family == "purchase":
        return DummyPurchaseModel(version=version or "dummy-purchase-v1")
    elif family == "transfer":
        return DummyTransferModel(version=version or "dummy-transfer-v1")
    return DummyFraudModel(version=version or f"dummy-{family}-v1", family=family)


# Canonical sample feature payloads for model warm-up (Design 2 Section 15.5)
# Purchase: exactly 29 canonical features matching purchase-v2
SAMPLE_PURCHASE_FEATURES: Dict[str, Any] = {
    "amount": 75.0,
    "log_amount": 4.3175,
    "hour_of_day": 12,
    "day_of_week": 2,
    "is_weekend": 0,
    "is_night": 0,
    "customer_age": 42.0,
    "distance_home_merchant_km": 15.5,
    "city_pop_log": 11.5,
    "merchant_category": "grocery_pos",
    "customer_avg_amount": 65.0,
    "customer_std_amount": 25.0,
    "customer_max_amount": 350.0,
    "amount_to_avg_ratio": 1.15,
    "amount_zscore_customer": 0.4,
    "hour_share": 0.08,
    "category_share": 0.12,
    "merchant_visit_count": 3,
    "avg_distance_home_km": 12.0,
    "distance_zscore": 0.25,
    "txn_count_1h": 1,
    "txn_count_24h": 4,
    "txn_count_7d": 18,
    "amount_sum_24h": 180.0,
    "time_since_last_txn_s": 7200.0,
    "is_new_merchant": 0,
    "is_new_category": 0,
    "history_length": 120,
    "days_since_first_txn": 45.0,
}

# Transfer: exactly 20 canonical features matching transfer-v2
SAMPLE_TRANSFER_FEATURES: Dict[str, Any] = {
    "amount": 2500.0,
    "log_amount": 7.824,
    "hour_of_day": 14,
    "day_of_week": 3,
    "sender_avg_amount": 1800.0,
    "sender_std_amount": 600.0,
    "sender_max_amount": 8000.0,
    "amount_to_avg_ratio": 1.38,
    "sender_amount_zscore": 1.16,
    "is_new_recipient": 0,
    "pair_transfer_count": 2,
    "recipient_share": 0.15,
    "days_since_last_pair_transfer": 5.0,
    "distinct_recipients_30d": 8,
    "recipient_inbound_count_24h": 1,
    "recipient_unique_senders_24h": 1,
    "sender_txn_count_1h": 1,
    "sender_txn_count_24h": 3,
    "amount_to_balance_ratio": 0.45,
    "history_length": 65,
}


def get_sample_features_for_family(family: str) -> Dict[str, Any]:
    """Returns a canonical sample feature dict for warm-up of the specified family."""
    if family == "purchase":
        return dict(SAMPLE_PURCHASE_FEATURES)
    elif family == "transfer":
        return dict(SAMPLE_TRANSFER_FEATURES)
    return {"amount": 100.0, "hour_of_day": 12}


def prepare_inference_input(
    family: str,
    features: Dict[str, Any],
    feature_spec_version: Optional[str] = None,
    validator: Optional[FeatureSpecValidator] = None,
) -> pd.DataFrame:
    """
    Validates inference features and constructs a single-row pandas DataFrame
    with columns strictly in the canonical order defined by M1 specifications.

    Requirements:
    - Reuses the authoritative FeatureSpecValidator from Phase 1.
    - Ensures exactly 29 Purchase features or 20 Transfer features.
    - DataFrame columns follow the exact canonical feature order.
    - DataFrame is constructed explicitly with canonical columns (not relying solely on dict insertion order).
    - No historical feature engineering or cold-start calculation is performed in M2.
    - Feature values are not silently modified.

    Raises:
        FeatureValidationError (HTTP 422): If features are missing, unexpected, or have invalid types.
        FeatureSpecMismatchError (HTTP 409): If requested feature_spec_version differs from expected.
    """
    val = validator or FeatureSpecValidator()
    # 1. Validate features (raises FeatureValidationError / FeatureSpecMismatchError)
    validated = val.validate(
        family=family,
        features=features,
        feature_spec_version=feature_spec_version,
    )

    # 2. Retrieve explicit canonical column list
    expected_meta = val.get_expected_features(family)
    canonical_columns = [f["name"] for f in expected_meta]

    # 3. Explicitly construct row values matching the canonical column list
    row_values = [[validated[col] for col in canonical_columns]]
    df = pd.DataFrame(row_values, columns=canonical_columns)

    return df


def extract_positive_class_index(model: Any) -> int:
    """
    Determines the column index corresponding to the positive (FRAUD) class.

    Inspects model.classes_ (or the final estimator's classes_ if model is an sklearn Pipeline).
    Avoids blindly assuming column index 1.
    Supported representations of the positive class:
      - Integer 1 (e.g. M1 is_fraud target in [0, 1])
      - Boolean True
      - Case-insensitive string: '1', 'FRAUD', 'IS_FRAUD', 'POSITIVE'
    If classes_ is absent (e.g. generic stub model without classes_ metadata),
    falls back safely to column index 1 (the standard binary classification convention).

    Raises:
        ValueError: If classes_ is present but the positive class cannot be identified.
    """
    classes = getattr(model, "classes_", None)
    if classes is None and hasattr(model, "steps") and len(model.steps) > 0:
        classes = getattr(model.steps[-1][1], "classes_", None)

    if classes is None:
        return 1

    classes_list = list(classes)

    # 1. Check for integer 1 (M1 canonical binary target: is_fraud == 1)
    for idx, c in enumerate(classes_list):
        if c == 1 and not isinstance(c, bool):
            return idx

    # 2. Check for boolean True
    for idx, c in enumerate(classes_list):
        if c is True:
            return idx

    # 3. Check for string representations
    for idx, c in enumerate(classes_list):
        str_c = str(c).strip().upper()
        if str_c in ("1", "FRAUD", "IS_FRAUD", "POSITIVE"):
            return idx

    # 4. If 2 classes and first is negative label, second is positive
    if len(classes_list) == 2:
        str_0 = str(classes_list[0]).strip().upper()
        if str_0 in ("0", "LEGITIMATE", "NEGATIVE", "NON_FRAUD", "NOT_FRAUD"):
            return 1

    raise ValueError(
        f"Unable to determine positive (FRAUD) class index from model classes: {classes_list}"
    )


def predict_fraud_probability(
    model: Any,
    input_df: Any,
    family: Optional[str] = None,
) -> Tuple[float, float]:
    """
    Executes model inference and extracts the positive-class (fraud) probability.

    Args:
        model: Loaded model or pipeline instance exposing predict_proba.
        input_df: Prepared pandas DataFrame containing canonical feature columns.
        family: Optional family name for context and logging.

    Returns:
        Tuple of (fraud_probability: float, inference_ms: float).

    Raises:
        ValueError: If model is None, lacks predict_proba, or returns invalid probabilities.
        RuntimeError: If inference execution fails.
    """
    if model is None:
        raise ValueError(f"Cannot execute prediction on None model for family '{family or 'unknown'}'.")

    if not hasattr(model, "predict_proba"):
        raise ValueError(
            f"Model for family '{family or 'unknown'}' does not expose 'predict_proba'."
        )

    clean_fam = str(family or "unknown").replace("\r", "").replace("\n", "")

    # Measure inference duration in milliseconds with high-resolution clock
    start_time = time.perf_counter()
    try:
        raw_output = model.predict_proba(input_df)
    except Exception as exc:
        logger.error("Inference failed for family '%s': %s", clean_fam, exc)
        raise RuntimeError(f"Model inference failed for family '{clean_fam}': {exc}") from exc
    inference_ms = (time.perf_counter() - start_time) * 1000.0

    output_arr = np.asarray(raw_output)

    if output_arr.ndim == 2:
        if output_arr.shape[0] == 0:
            raise ValueError("Model predict_proba returned empty output.")
        pos_idx = extract_positive_class_index(model)
        if pos_idx >= output_arr.shape[1]:
            raise ValueError(
                f"Positive class index {pos_idx} exceeds output dimension {output_arr.shape[1]}."
            )
        prob = float(output_arr[0, pos_idx])
    elif output_arr.ndim == 1:
        if len(output_arr) == 0:
            raise ValueError("Model predict_proba returned empty output.")
        prob = float(output_arr[0])
    else:
        raise ValueError(
            f"Unexpected prediction output shape: {output_arr.shape} (expected 1D or 2D array)."
        )

    if math.isnan(prob) or math.isinf(prob):
        raise ValueError(f"Model returned invalid non-finite probability: {prob}")

    if prob < 0.0 or prob > 1.0:
        raise ValueError(f"Model returned out-of-bounds probability: {prob} (expected [0.0, 1.0])")

    return prob, round(inference_ms, 4)


class ModelExplainer:
    """
    Computes individual SHAP feature contributions for a specific model family.
    Uses Option A: TreeExplainer applied directly to the underlying fitted tree
    estimator (e.g. XGBClassifier) before isotonic probability calibration.
    """

    def __init__(
        self,
        family: str,
        pipeline: Any,
        feature_spec_version: Optional[str] = None,
    ) -> None:
        self.family = family
        self.feature_spec_version = feature_spec_version
        self.preprocessor: Any = None
        self.base_model: Any = None
        self.explainer: Any = None
        self.raw_feature_names: List[str] = []
        self.output_feature_names: List[str] = []
        self.feature_mapping: Dict[str, List[int]] = {}
        self.positive_class_index: int = 1
        self.base_value: float = 0.0

        self._extract_components(pipeline)
        self._build_feature_mapping()
        self._init_explainer()

    def _extract_components(self, pipeline: Any) -> None:
        if pipeline is None:
            raise ValueError(f"Pipeline for family '{self.family}' is None.")

        # 1. Extract preprocessor
        if hasattr(pipeline, "named_steps") and "preprocessor" in pipeline.named_steps:
            self.preprocessor = pipeline.named_steps["preprocessor"]
        elif hasattr(pipeline, "steps") and len(pipeline.steps) > 0 and pipeline.steps[0][0] == "preprocessor":
            self.preprocessor = pipeline.steps[0][1]
        else:
            raise ValueError(
                f"Pipeline for family '{self.family}' does not contain a 'preprocessor' step. "
                f"Available steps: {list(getattr(pipeline, 'named_steps', {}).keys())}"
            )

        # 2. Extract model step
        model_step = None
        if hasattr(pipeline, "named_steps") and "model" in pipeline.named_steps:
            model_step = pipeline.named_steps["model"]
        elif hasattr(pipeline, "steps") and len(pipeline.steps) > 1 and pipeline.steps[-1][0] == "model":
            model_step = pipeline.steps[-1][1]
        else:
            raise ValueError(
                f"Pipeline for family '{self.family}' does not contain a 'model' step. "
                f"Available steps: {list(getattr(pipeline, 'named_steps', {}).keys())}"
            )

        # 3. Extract underlying base_model from CalibratedModelWrapper if wrapped
        if hasattr(model_step, "base_model"):
            self.base_model = model_step.base_model
        else:
            self.base_model = model_step

        if self.base_model is None:
            raise ValueError(f"Extracted base_model for family '{self.family}' is None.")

        # 4. Extract positive class index
        try:
            self.positive_class_index = extract_positive_class_index(self.base_model)
        except Exception:
            self.positive_class_index = 1

    def _build_feature_mapping(self) -> None:
        val = FeatureSpecValidator()
        if self.family not in ("purchase", "transfer"):
            raise ValueError(f"Unsupported family '{self.family}' for feature mapping.")
        expected_meta = val.get_expected_features(self.family)
        self.raw_feature_names = [f["name"] for f in expected_meta]

        if hasattr(self.preprocessor, "get_feature_names_out"):
            try:
                self.output_feature_names = list(self.preprocessor.get_feature_names_out())
            except Exception as exc:
                raise ValueError(
                    f"Failed to obtain feature_names_out from preprocessor for family '{self.family}': {exc}"
                ) from exc
        else:
            raise ValueError(
                f"Preprocessor for family '{self.family}' does not implement 'get_feature_names_out()'."
            )

        n_output = len(self.output_feature_names)
        self.feature_mapping = {}

        if self.family == "transfer":
            if n_output != len(self.raw_feature_names):
                raise ValueError(
                    f"Transfer preprocessor output dimension ({n_output}) does not match expected 20 features."
                )
            for idx, out_name in enumerate(self.output_feature_names):
                matched = False
                for raw_col in self.raw_feature_names:
                    if out_name == raw_col or out_name.endswith(f"__{raw_col}"):
                        self.feature_mapping[raw_col] = [idx]
                        matched = True
                        break
                if not matched:
                    raw_col = self.raw_feature_names[idx]
                    self.feature_mapping[raw_col] = [idx]

            if len(self.feature_mapping) != len(self.raw_feature_names):
                raise ValueError(
                    f"Failed to map all 20 Transfer features (mapped {len(self.feature_mapping)}/20)."
                )

        elif self.family == "purchase":
            cat_feature = "merchant_category"
            cat_indices: List[int] = []

            for idx, out_name in enumerate(self.output_feature_names):
                if (
                    out_name.startswith("cat__merchant_category_")
                    or out_name.startswith("cat__merchant_category")
                    or out_name.startswith("merchant_category_")
                    or "merchant_category" in out_name
                ):
                    cat_indices.append(idx)
                else:
                    for raw_col in self.raw_feature_names:
                        if raw_col == cat_feature:
                            continue
                        if out_name == raw_col or out_name.endswith(f"__{raw_col}"):
                            self.feature_mapping[raw_col] = [idx]
                            break

            if not cat_indices:
                raise ValueError(
                    f"Could not identify one-hot encoded columns for '{cat_feature}' in preprocessor output: {self.output_feature_names}"
                )

            self.feature_mapping[cat_feature] = cat_indices

            missing = [f for f in self.raw_feature_names if f not in self.feature_mapping]
            if missing:
                raise ValueError(
                    f"Purchase feature mapping incomplete. Missing features: {missing}"
                )

    def _init_explainer(self) -> None:
        if shap is None:
            raise RuntimeError("shap package is not installed.")

        _patch_shap_xgboost_parser()

        try:
            self.explainer = shap.TreeExplainer(self.base_model, model_output="raw")
        except Exception:
            try:
                self.explainer = shap.TreeExplainer(self.base_model)
            except Exception as exc:
                raise ValueError(
                    f"Failed to initialize shap.TreeExplainer for family '{self.family}': {exc}"
                ) from exc

        exp_val = getattr(self.explainer, "expected_value", 0.0)
        if isinstance(exp_val, (list, tuple, np.ndarray)):
            arr = np.asarray(exp_val)
            if arr.ndim > 0 and len(arr) > self.positive_class_index:
                self.base_value = float(arr[self.positive_class_index])
            elif arr.ndim > 0 and len(arr) > 0:
                self.base_value = float(arr[-1])
            else:
                self.base_value = 0.0
        elif isinstance(exp_val, (int, float, np.floating)):
            self.base_value = float(exp_val)
        else:
            self.base_value = 0.0

        if math.isnan(self.base_value) or math.isinf(self.base_value):
            raise ValueError(f"TreeExplainer produced non-finite base_value: {self.base_value}")

    def explain(
        self,
        features_df: pd.DataFrame,
        raw_features_dict: Dict[str, Any],
    ) -> Tuple[float, List[FeatureContribution]]:
        # 1. Transform raw input DataFrame through preprocessor
        try:
            X_trans = self.preprocessor.transform(features_df)
        except Exception as exc:
            raise ValueError(f"Preprocessor transform failed: {exc}") from exc

        # 2. Compute SHAP values
        try:
            raw_shap = self.explainer.shap_values(X_trans)
        except Exception as exc:
            raise RuntimeError(f"TreeExplainer computation failed: {exc}") from exc

        # 3. Extract 1D array of contributions for positive class
        if isinstance(raw_shap, list):
            idx = min(self.positive_class_index, len(raw_shap) - 1)
            class_shap = np.asarray(raw_shap[idx])
            sample_shap = class_shap[0] if class_shap.ndim > 1 else class_shap
        else:
            arr = np.asarray(raw_shap)
            if arr.ndim == 3:
                idx = min(self.positive_class_index, arr.shape[2] - 1)
                sample_shap = arr[0, :, idx]
            elif arr.ndim == 2:
                sample_shap = arr[0]
            elif arr.ndim == 1:
                sample_shap = arr
            else:
                raise ValueError(f"Unexpected SHAP values shape: {arr.shape}")

        # Update dynamic base_value from explainer.expected_value post-computation
        exp_val = getattr(self.explainer, "expected_value", self.base_value)
        dyn_base = self.base_value
        if isinstance(exp_val, (list, tuple, np.ndarray)):
            arr = np.asarray(exp_val)
            if arr.ndim > 0 and len(arr) > self.positive_class_index:
                dyn_base = float(arr[self.positive_class_index])
            elif arr.ndim > 0 and len(arr) > 0:
                dyn_base = float(arr[-1])
        elif isinstance(exp_val, (int, float, np.floating)):
            dyn_base = float(exp_val)

        if not (math.isnan(dyn_base) or math.isinf(dyn_base)):
            self.base_value = dyn_base

        # 4. Map contributions back to raw features (aggregating OHE dummy columns)
        contributions: List[FeatureContribution] = []
        for raw_name in self.raw_feature_names:
            indices = self.feature_mapping.get(raw_name, [])
            if not indices:
                continue
            feat_shap = float(np.sum(sample_shap[indices]))
            if math.isnan(feat_shap) or math.isinf(feat_shap):
                raise ValueError(f"Non-finite SHAP value for feature '{raw_name}': {feat_shap}")

            raw_val = raw_features_dict.get(raw_name)
            contributions.append(
                FeatureContribution(
                    feature=raw_name,
                    value=raw_val,
                    shap=round(feat_shap, 6),
                )
            )

        # 5. Sort contributions in descending order of absolute SHAP value
        contributions.sort(key=lambda c: abs(c.shap), reverse=True)

        return round(self.base_value, 6), contributions


def create_model_explainer(
    family: str,
    pipeline: Any,
    feature_spec_version: Optional[str] = None,
) -> ModelExplainer:
    """
    Factory function to create and initialize a ModelExplainer for a loaded model pipeline.
    Validates components, preprocessor, and estimator compatibility.
    """
    return ModelExplainer(
        family=family,
        pipeline=pipeline,
        feature_spec_version=feature_spec_version,
    )


def explain_transaction(
    family: str,
    features: Dict[str, Any],
    feature_spec_version: Optional[str] = None,
) -> Tuple[ModelEntry, float, List[FeatureContribution]]:
    """
    Validates features, checks explainer availability, executes SHAP explanation,
    and returns (ModelEntry, base_value, sorted_contributions).

    Raises:
        FeatureValidationError (422)
        FeatureSpecMismatchError (409)
        ModelNotReadyError (503)
        RuntimeError / ValueError (500)
    """
    registry = get_model_registry()
    entry = registry.get_entry(family)

    if entry is None or entry.model is None:
        raise ModelNotReadyError(
            family=family,
            detail=f"Model family '{family}' is not loaded or not ready. Status: {registry.get_loading_status(family)}.",
        )

    if entry.explainer is None:
        reason = entry.explainer_error or "Model is not an explainable pipeline."
        raise ModelNotReadyError(
            family=family,
            detail=f"Model family '{family}' is loaded but does not support SHAP explanations: {reason}",
        )

    # 1. Prepare and validate input features DataFrame
    input_df = prepare_inference_input(
        family=family,
        features=features,
        feature_spec_version=feature_spec_version,
    )

    # 2. Compute explanation via cached explainer
    base_val, contributions = entry.explainer.explain(
        features_df=input_df,
        raw_features_dict=features,
    )

    return entry, base_val, contributions


def validate_model_signature(model: Any, family: str) -> bool:
    """
    Validates model signature and structural interface (Design 2 Section 15.5).
    Verifies that the model artifact is non-null and exposes valid inference callable interfaces.
    If the model artifact contains MLflow signature metadata, validates its structural definition.
    """
    if model is None:
        raise ValueError(f"Model artifact for family '{family}' is None.")

    # Ensure model artifact exposes expected inference callable
    if not hasattr(model, "predict_proba") and not hasattr(model, "predict"):
        raise ValueError(
            f"Model artifact for family '{family}' does not expose a valid prediction interface ('predict_proba' or 'predict')."
        )

    # If the artifact carries MLflow signature metadata, verify it has input specifications
    signature = getattr(model, "metadata", None)
    if signature is not None:
        sig_obj = getattr(signature, "signature", None)
        if sig_obj is not None and hasattr(sig_obj, "inputs"):
            inputs = sig_obj.inputs
            if inputs is not None and hasattr(inputs, "__len__") and len(inputs) == 0:
                raise ValueError(f"Model signature for family '{family}' contains empty input schema.")

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Model signature validation passed for family '%s'.", clean_family)
    return True


def warmup_model(model: Any, family: str) -> None:
    """
    Executes a warm-up inference request on the loaded model using a sample feature payload (Design 2 Section 15.5).
    Ensures graph compilation, memory allocation, and JIT/C-extensions are primed before accepting live traffic.
    """
    if model is None:
        raise ValueError(f"Cannot warm up None model for family '{family}'.")

    if family in ("purchase", "transfer"):
        payload = prepare_inference_input(family, get_sample_features_for_family(family))
    else:
        sample_features = get_sample_features_for_family(family)
        try:
            payload = pd.DataFrame([sample_features])
        except Exception:
            payload = [sample_features]

    warmed = False
    last_err: Optional[Exception] = None

    if hasattr(model, "predict_proba"):
        try:
            model.predict_proba(payload)
            warmed = True
        except Exception as exc:
            last_err = exc

    if not warmed and hasattr(model, "predict"):
        try:
            model.predict(payload)
            warmed = True
        except Exception as exc:
            last_err = exc

    if not warmed:
        if last_err is not None:
            raise RuntimeError(
                f"Warm-up inference failed for model family '{family}': {last_err}"
            ) from last_err
        raise RuntimeError(
            f"Model artifact for family '{family}' does not expose callable inference methods for warm-up."
        )

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Model warm-up completed successfully for family '%s'.", clean_family)


class ModelRegistry:
    """
    Thread-safe registry managing independent model families.
    Tracks state, version, and active entry per family.
    """

    SUPPORTED_FAMILIES: Tuple[str, ...] = ("purchase", "transfer")

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._entries: Dict[str, Optional[ModelEntry]] = {
            fam: None for fam in self.SUPPORTED_FAMILIES
        }
        self._loading_status: Dict[str, str] = {
            fam: "UNLOADED" for fam in self.SUPPORTED_FAMILIES
        }
        self._last_errors: Dict[str, Optional[str]] = {
            fam: None for fam in self.SUPPORTED_FAMILIES
        }
        self._load_attempts: Dict[str, int] = {
            fam: 0 for fam in self.SUPPORTED_FAMILIES
        }
        # Startup initialization lifecycle tracking (Design 2 Section 15.5)
        self._warmed_up: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }
        self._signature_validated: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }
        self._startup_complete: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }

    @property
    def get_supported_families(self) -> Tuple[str, ...]:
        return self.SUPPORTED_FAMILIES

    def _validate_family(self, family: str) -> None:
        if family not in self.SUPPORTED_FAMILIES:
            raise ValueError(
                f"Unknown model family '{family}'. Supported families: {list(self.SUPPORTED_FAMILIES)}"
            )

    def get_entry(self, family: str) -> Optional[ModelEntry]:
        """Returns the current ModelEntry for a family, or None if unloaded."""
        self._validate_family(family)
        with self._lock:
            return self._entries.get(family)

    def get_model(self, family: str) -> Optional[Any]:
        """Returns the active model instance for a family, or None."""
        entry = self.get_entry(family)
        return entry.model if entry else None

    def get_version(self, family: str) -> Optional[str]:
        """Returns the active model version string for a family, or None."""
        entry = self.get_entry(family)
        return entry.model_version if entry else None

    def is_family_loaded(self, family: str) -> bool:
        """Returns True if the specified family is currently loaded and ready."""
        self._validate_family(family)
        with self._lock:
            return self._entries.get(family) is not None

    def are_all_models_loaded(self) -> bool:
        """Returns True only when all supported families are loaded and ready."""
        with self._lock:
            return all(self._entries.get(fam) is not None for fam in self.SUPPORTED_FAMILIES)

    def is_family_warmed_up(self, family: str) -> bool:
        """Returns True if the family has been successfully warmed up with a sample request."""
        self._validate_family(family)
        with self._lock:
            return self._warmed_up.get(family, False)

    def is_family_signature_validated(self, family: str) -> bool:
        """Returns True if the family's model signature has been validated."""
        self._validate_family(family)
        with self._lock:
            return self._signature_validated.get(family, False)

    def is_family_startup_complete(self, family: str) -> bool:
        """
        Returns True only when the family has completed the full 4-step startup sequence:
        1. Alias resolution, 2. Artifact download/load, 3. Signature validation, 4. Warm-up.
        """
        self._validate_family(family)
        with self._lock:
            return (
                self.is_family_loaded(family)
                and self._signature_validated.get(family, False)
                and self._warmed_up.get(family, False)
                and self._startup_complete.get(family, False)
            )

    def are_all_models_started(self) -> bool:
        """Returns True only when all supported families have completed the full startup sequence."""
        with self._lock:
            return all(self.is_family_startup_complete(fam) for fam in self.SUPPORTED_FAMILIES)

    def mark_family_warmed_up(self, family: str, warmed: bool = True) -> None:
        """Sets the warm-up completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._warmed_up[family] = warmed
            if warmed and self._signature_validated.get(family, False) and self.is_family_loaded(family):
                self._startup_complete[family] = True
            elif not warmed:
                self._startup_complete[family] = False

    def mark_family_signature_validated(self, family: str, validated: bool = True) -> None:
        """Sets the signature validation completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._signature_validated[family] = validated
            if validated and self._warmed_up.get(family, False) and self.is_family_loaded(family):
                self._startup_complete[family] = True
            elif not validated:
                self._startup_complete[family] = False

    def mark_startup_complete(self, family: str, complete: bool = True) -> None:
        """Sets the full startup sequence completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._startup_complete[family] = complete
            self._signature_validated[family] = complete
            self._warmed_up[family] = complete

    def set_entry(self, family: str, entry: ModelEntry) -> None:
        """Atomically updates the model entry for a family."""
        self._validate_family(family)
        if entry.family != family:
            raise ValueError(f"Entry family '{entry.family}' does not match target family '{family}'.")
        with self._lock:
            self._entries[family] = entry
            self._loading_status[family] = "READY"
            self._last_errors[family] = None

    def set_model(
        self,
        family: str,
        model: Any,
        version: str,
        model_name: Optional[str] = None,
        model_alias: Optional[str] = None,
        feature_spec_version: Optional[str] = None,
        explainer: Optional[Any] = None,
        explainer_error: Optional[str] = None,
        conformal_config: Optional[Any] = None,
        conformal_evaluator: Optional[Any] = None,
        conformal_error: Optional[str] = None,
    ) -> ModelEntry:
        """Convenience method to set a model in the registry."""
        self._validate_family(family)
        settings = get_settings()
        name = model_name or settings.get_model_name_for_family(family)
        alias = model_alias or settings.get_model_alias_for_family(family)
        entry = ModelEntry(
            family=family,
            model_name=name,
            model_version=version,
            model_alias=alias,
            model=model,
            loaded_at=time.time(),
            feature_spec_version=feature_spec_version,
            explainer=explainer,
            explainer_error=explainer_error,
            conformal_config=conformal_config,
            conformal_evaluator=conformal_evaluator,
            conformal_error=conformal_error,
        )
        self.set_entry(family, entry)
        return entry

    def set_conformal_evaluator(
        self,
        family: str,
        evaluator: Optional[Any],
        config: Optional[Any] = None,
        error: Optional[str] = None,
    ) -> None:
        """
        Attaches a ConformalEvaluator (and optional ConformalConfig) to the active ModelEntry.
        Thread-safe. Raises ValueError if no active model entry exists for the family.
        """
        self._validate_family(family)
        with self._lock:
            entry = self._entries.get(family)
            if entry is None:
                raise ValueError(
                    f"Cannot attach conformal evaluator: no active model entry for family '{family}'."
                )
            entry.conformal_evaluator = evaluator
            entry.conformal_config = config
            entry.conformal_error = error

    def record_failure(self, family: str, error_message: str) -> None:
        """
        Records a loading failure for a family without corrupting any active entry.
        Preserves the currently active model if one was already serving.
        """
        self._validate_family(family)
        with self._lock:
            self._load_attempts[family] += 1
            self._last_errors[family] = error_message
            if self._entries.get(family) is None:
                self._loading_status[family] = "FAILED"
                self._startup_complete[family] = False
                self._warmed_up[family] = False
                self._signature_validated[family] = False
            # If an active entry exists, active model is preserved and not cleared!

    def get_loading_status(self, family: str) -> str:
        """Returns the loading status ('UNLOADED', 'READY', 'FAILED')."""
        self._validate_family(family)
        with self._lock:
            return self._loading_status[family]

    def get_last_error(self, family: str) -> Optional[str]:
        """Returns the last recorded error for a family, or None."""
        self._validate_family(family)
        with self._lock:
            return self._last_errors[family]

    def get_load_attempts(self, family: str) -> int:
        """Returns the number of load attempts recorded for a family."""
        self._validate_family(family)
        with self._lock:
            return self._load_attempts[family]

    def reset(self, family: Optional[str] = None) -> None:
        """Resets registry state for one or all families."""
        with self._lock:
            targets = (family,) if family is not None else self.SUPPORTED_FAMILIES
            for fam in targets:
                self._validate_family(fam)
                self._entries[fam] = None
                self._loading_status[fam] = "UNLOADED"
                self._last_errors[fam] = None
                self._load_attempts[fam] = 0
                self._warmed_up[fam] = False
                self._signature_validated[fam] = False
                self._startup_complete[fam] = False

    def get_status_summary(self) -> Dict[str, Any]:
        """Returns a snapshot of the registry's state for health/monitoring."""
        with self._lock:
            return {
                fam: {
                    "loaded": self._entries.get(fam) is not None,
                    "model_name": self._entries[fam].model_name if self._entries.get(fam) else None,
                    "model_version": self._entries[fam].model_version if self._entries.get(fam) else None,
                    "status": self._loading_status[fam],
                    "attempts": self._load_attempts[fam],
                    "last_error": self._last_errors[fam],
                    "warmed_up": self._warmed_up[fam],
                    "signature_validated": self._signature_validated[fam],
                    "startup_complete": self.is_family_startup_complete(fam),
                }
                for fam in self.SUPPORTED_FAMILIES
            }


# Singleton registry instance
_REGISTRY_INSTANCE: Optional[ModelRegistry] = None
_REGISTRY_LOCK = threading.Lock()


class ReloadGuard:
    """
    Thread-safe guard managing in-progress model reload operations per family.
    Enforces concurrency bounds, prevents duplicate parallel reloads for the same family,
    and permits independent concurrent reloads across different families.
    """

    def __init__(self, supported_families: Tuple[str, ...]) -> None:
        self._supported_families = supported_families
        self._state_lock = threading.Lock()
        self._in_progress: Dict[str, bool] = {fam: False for fam in supported_families}

    def acquire(self, family: str) -> bool:
        """
        Attempts to acquire the reload guard for the specified family without blocking.
        Returns True if acquired (no reload in progress).
        Returns False if a reload is already in progress for this family.
        """
        with self._state_lock:
            if self._in_progress.get(family, False):
                return False
            self._in_progress[family] = True
            return True

    def release(self, family: str) -> None:
        """
        Releases the reload guard for the specified family.
        Always called in a finally block after reload success or failure.
        """
        with self._state_lock:
            self._in_progress[family] = False

    def is_reloading(self, family: str) -> bool:
        """Checks if a reload operation is currently active for the given family."""
        with self._state_lock:
            return self._in_progress.get(family, False)

    def reset(self) -> None:
        """Resets reload guards for all families (used primarily in test cleanup)."""
        with self._state_lock:
            for fam in self._supported_families:
                self._in_progress[fam] = False


_RELOAD_GUARD = ReloadGuard(ModelRegistry.SUPPORTED_FAMILIES)


def acquire_reload_guard(family: str) -> bool:
    """Attempts non-blocking acquisition of the reload guard for the specified family."""
    return _RELOAD_GUARD.acquire(family)


def release_reload_guard(family: str) -> None:
    """Releases the reload guard for the specified family."""
    _RELOAD_GUARD.release(family)


def is_family_reloading(family: str) -> bool:
    """Checks if a reload operation is in progress for the specified family."""
    return _RELOAD_GUARD.is_reloading(family)


def reset_reload_guards() -> None:
    """Resets all reload guards (test teardown helper)."""
    _RELOAD_GUARD.reset()


def get_model_registry() -> ModelRegistry:
    """Returns the singleton ModelRegistry instance."""
    global _REGISTRY_INSTANCE
    if _REGISTRY_INSTANCE is None:
        with _REGISTRY_LOCK:
            if _REGISTRY_INSTANCE is None:
                _REGISTRY_INSTANCE = ModelRegistry()
    return _REGISTRY_INSTANCE


# Background thread control
_STOP_EVENT = threading.Event()
_LOADER_THREAD: Optional[threading.Thread] = None


def get_tracking_uri() -> str:
    """Returns the MLflow tracking URI from configuration."""
    settings = get_settings()
    return settings.MLFLOW_TRACKING_URI


def load_family_model(
    family: str,
    tracking_uri: Optional[str] = None,
) -> Tuple[Any, str]:
    """
    Retrieves and loads the champion model for the specified family (Design 2 Section 15.5).
    Full startup sequence executed per family:
      1. Resolve 'champion' alias to immutable model version.
      2. Download and load model artifact.
      3. Validate model signature.
      4. Warm up model with sample request.
    Updates ModelRegistry atomically on success.
    Preserves active model entry if loading fails.
    """
    registry = get_model_registry()
    settings = get_settings()

    target_name = settings.get_model_name_for_family(family)
    target_alias = settings.get_model_alias_for_family(family)
    uri = tracking_uri or settings.MLFLOW_TRACKING_URI

    # Dummy model fallback for local/test
    if settings.USE_DUMMY_MODEL:
        try:
            clean_family = str(family).replace("\r", "").replace("\n", "")
            logger.info("USE_DUMMY_MODEL is enabled. Initializing in-memory dummy model for family '%s'.", clean_family)
            dummy = create_dummy_model(family)

            # Step 3: Validate model signature
            validate_model_signature(dummy, family)
            registry.mark_family_signature_validated(family, True)

            # Step 4: Warm up model with sample request
            warmup_model(dummy, family)
            registry.mark_family_warmed_up(family, True)

            # Step 5: Explainer initialization for dummy model
            explainer = None
            explainer_err = None
            try:
                explainer = create_model_explainer(family=family, pipeline=dummy)
                logger.info("Explainer initialized successfully for family '%s'.", clean_family)
            except Exception as exc:
                explainer_err = f"{exc.__class__.__name__}: {str(exc)}"

            registry.set_model(
                family=family,
                model=dummy,
                version=dummy.version,
                model_name=target_name,
                model_alias=target_alias,
                explainer=explainer,
                explainer_error=explainer_err,
            )
            registry.mark_startup_complete(family, True)
            return dummy, dummy.version
        except Exception as exc:
            registry.record_failure(family, f"{exc.__class__.__name__}: {str(exc)}")
            raise

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Connecting to MLflow Tracking Server at %s for family '%s'...", uri, clean_family)
    mlflow.set_tracking_uri(uri)
    client = MlflowClient(tracking_uri=uri)

    try:
        # Step 1: Resolve alias to immutable version
        model_version_meta = client.get_model_version_by_alias(name=target_name, alias=target_alias)
        version = str(model_version_meta.version)
        clean_family = str(family).replace("\r", "").replace("\n", "")
        logger.info(
            "Resolved model '%s@%s' to version %s for family '%s'",
            target_name,
            target_alias,
            version,
            clean_family,
        )

        # Step 2: Download and load model artifact via proxy
        model_uri = f"models:/{target_name}@{target_alias}"
        logger.info(f"Downloading model bundle from '{model_uri}'...")
        try:
            loaded_model = mlflow.sklearn.load_model(model_uri)
        except Exception:
            # Fallback to pyfunc if sklearn loader encounters differences
            loaded_model = mlflow.pyfunc.load_model(model_uri)

        if loaded_model is None:
            raise ValueError(f"Model artifact downloaded for family '{family}' is None.")

        if not version or not str(version).strip():
            raise ValueError(f"Resolved model version for family '{family}' is empty.")

        # Step 3: Validate model signature
        validate_model_signature(loaded_model, family)
        registry.mark_family_signature_validated(family, True)

        # Step 4: Warm up model with sample request
        warmup_model(loaded_model, family)
        registry.mark_family_warmed_up(family, True)

        # Step 5: Initialize Explainer (Option A: TreeExplainer on base estimator before calibration)
        explainer = None
        explainer_err = None
        try:
            explainer = create_model_explainer(family=family, pipeline=loaded_model)
            logger.info("Explainer initialized successfully for family '%s'.", clean_family)
        except Exception as exc:
            explainer_err = f"{exc.__class__.__name__}: {str(exc)}"
            logger.warning(
                "Explainer initialization failed for family '%s' (%s). Serving will report model as not explainable.",
                clean_family,
                explainer_err,
            )

        # Step 6: Split Conformal initialization via MLflow adapter
        conformal_evaluator = None
        conformal_config = None
        conformal_error = None
        try:
            conformal_adapter = MLflowConformalAdapter()
            feature_spec_ver = "purchase-v2" if family == "purchase" else "transfer-v2"
            run_id = getattr(model_version_meta, "run_id", None)

            conformal_res = conformal_adapter.load_conformal(
                family=family,
                model_name=target_name,
                model_version=version,
                feature_spec_version=feature_spec_ver,
                client=client,
                model_version_meta=model_version_meta,
                run_id=run_id,
            )

            if conformal_res.status == ConformalLoadStatus.LOADED:
                conformal_evaluator = conformal_res.evaluator
                conformal_config = conformal_res.config
                logger.info(
                    "Split Conformal evaluator loaded for family '%s' (source: %s, quantile: %s).",
                    clean_family,
                    conformal_res.source,
                    conformal_config.quantile if conformal_config else "N/A",
                )
            else:
                conformal_error = conformal_res.error_message or conformal_res.status.value
                logger.warning(
                    "Split Conformal not active for family '%s': %s (%s). Serving continues without conformal enrichment.",
                    clean_family,
                    conformal_res.status.value,
                    conformal_res.error_message,
                )
        except Exception as conf_exc:
            conformal_error = f"{conf_exc.__class__.__name__}: {str(conf_exc)}"
            logger.warning(
                "Unexpected error during Split Conformal loading for family '%s': %s. Serving continues without conformal enrichment.",
                clean_family,
                conformal_error,
            )

        # Update registry atomically
        registry.set_model(
            family=family,
            model=loaded_model,
            version=version,
            model_name=target_name,
            model_alias=target_alias,
            explainer=explainer,
            explainer_error=explainer_err,
            conformal_config=conformal_config,
            conformal_evaluator=conformal_evaluator,
            conformal_error=conformal_error,
        )
        registry.mark_startup_complete(family, True)
        return loaded_model, version

    except Exception as exc:
        registry.record_failure(family, f"{exc.__class__.__name__}: {str(exc)}")
        raise


def reload_family_model(
    family: str,
    tracking_uri: Optional[str] = None,
) -> Tuple[Any, str]:
    """
    Triggers an atomic reload of the champion model for the specified family.
    Re-downloads and loads the model into ModelRegistry on success.
    Preserves any active serving model if reloading fails.
    """
    return load_family_model(family=family, tracking_uri=tracking_uri)


def load_all_models(tracking_uri: Optional[str] = None) -> Dict[str, Tuple[Any, str]]:
    """Attempts to load all supported model families. Returns loaded models per family."""
    registry = get_model_registry()
    results = {}
    for family in registry.get_supported_families:
        try:
            model, version = load_family_model(family=family, tracking_uri=tracking_uri)
            results[family] = (model, version)
        except Exception as exc:
            clean_family = str(family).replace("\r", "").replace("\n", "")
            logger.warning("Failed to load model family '%s' (%s).", clean_family, exc.__class__.__name__)
    return results


def _background_loader_loop() -> None:
    """Background worker thread that attempts to load missing models with exponential backoff."""
    settings = get_settings()
    registry = get_model_registry()
    attempt = 0

    while not _STOP_EVENT.is_set():
        for family in registry.get_supported_families:
            if not registry.is_family_startup_complete(family):
                if not acquire_reload_guard(family):
                    continue
                try:
                    load_family_model(family)
                    clean_family = str(family).replace("\r", "").replace("\n", "")
                    logger.info("Model family '%s' successfully loaded and warmed up by background loader.", clean_family)
                except Exception as exc:
                    clean_family = str(family).replace("\r", "").replace("\n", "")
                    logger.warning(
                        "Model load attempt for family '%s' failed (%s).",
                        clean_family,
                        exc.__class__.__name__,
                    )
                finally:
                    release_reload_guard(family)

        if registry.are_all_models_started():
            logger.info("All model families successfully loaded into memory and warmed up by background loader.")
            break

        attempt += 1
        backoff = min(
            settings.MODEL_LOAD_MAX_BACKOFF_SEC,
            settings.MODEL_LOAD_INITIAL_BACKOFF_SEC * (2 ** attempt),
        )
        logger.info(f"Background loader retrying in {backoff:.1f}s (attempt {attempt})...")
        if _STOP_EVENT.wait(timeout=backoff):
            logger.info("Background loader loop stopped.")
            break


def start_background_model_loader() -> None:
    """Spawns the model loader daemon thread during application startup."""
    global _LOADER_THREAD
    _STOP_EVENT.clear()
    registry = get_model_registry()
    if registry.are_all_models_started():
        logger.info("All models already loaded and started. Skipping background loader.")
        return

    _LOADER_THREAD = threading.Thread(
        target=_background_loader_loop,
        name="ModelLoaderThread",
        daemon=True,
    )
    _LOADER_THREAD.start()
    logger.info("Background model loader thread started.")


def stop_background_model_loader(timeout: float = 2.0) -> None:
    """Signals the background model loader thread to terminate."""
    global _LOADER_THREAD
    _STOP_EVENT.set()
    if _LOADER_THREAD and _LOADER_THREAD.is_alive():
        _LOADER_THREAD.join(timeout=timeout)
        logger.info("Background model loader thread terminated.")
    _LOADER_THREAD = None


# Backward-compatible helper functions for legacy single-model callers
def is_model_loaded(family: Optional[str] = None) -> bool:
    """
    Returns True if models are ready in memory.
    If family is specified, checks that family.
    If family is None, returns True only if ALL families are loaded in memory.
    """
    registry = get_model_registry()
    if family is not None:
        return registry.is_family_loaded(family)
    return registry.are_all_models_loaded()


def is_startup_complete(family: Optional[str] = None) -> bool:
    """
    Returns True if startup sequence (alias resolution, load, signature validation, warmup) is complete.
    If family is specified, checks that family.
    If family is None, returns True only if ALL supported families have completed startup.
    """
    registry = get_model_registry()
    if family is not None:
        return registry.is_family_startup_complete(family)
    return registry.are_all_models_started()


def get_loaded_model(family: str = "purchase") -> Optional[Any]:
    """Returns the cached model instance for a family (defaults to 'purchase')."""
    return get_model_registry().get_model(family)


def get_loaded_model_version(family: Optional[str] = None) -> Optional[str]:
    """Returns the version of the specified family, or purchase model version by default."""
    registry = get_model_registry()
    if family is not None:
        return registry.get_version(family)
    return registry.get_version("purchase")


def reset_model_cache(family: Optional[str] = None) -> None:
    """Resets cached models in memory and resets reload guards."""
    get_model_registry().reset(family)
    reset_reload_guards()


def set_cached_model(
    model: Any,
    version: str,
    family: Optional[str] = None,
    mark_started: bool = True,
) -> None:
    """Manually sets the in-memory cached model (used by test fixtures)."""
    registry = get_model_registry()
    if family is not None:
        registry.set_model(family, model, version)
        if mark_started:
            registry.mark_startup_complete(family, True)
    else:
        # Legacy: sets for all supported families
        for fam in registry.get_supported_families:
            registry.set_model(fam, model, version)
            if mark_started:
                registry.mark_startup_complete(fam, True)


def attach_test_conformal_evaluator(
    family: str,
    evaluator: Optional[Any],
    config: Optional[Any] = None,
) -> None:
    """
    Explicit test utility to inject a synthetic ConformalEvaluator into the active ModelEntry.
    STRICTLY FOR TEST HARNESSES — Never invoked during production MLflow model loading.
    """
    registry = get_model_registry()
    registry.set_conformal_evaluator(family=family, evaluator=evaluator, config=config, error=None)


def load_champion_model(
    model_name: Optional[str] = None,
    alias: Optional[str] = None,
    tracking_uri: Optional[str] = None,
    family: str = "purchase",
) -> Tuple[Any, str]:
    """Legacy helper loading a champion model (defaults to purchase family)."""
    return load_family_model(family=family, tracking_uri=tracking_uri)
