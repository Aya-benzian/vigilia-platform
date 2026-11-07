"""
test_explanation_engine.py — Unit tests for M2 SHAP Explainer Engine.

Tests:
1. Component extraction: preprocessor and underlying base estimator (including CalibratedModelWrapper unwrapping).
2. Independent explainer initialization for Purchase and Transfer.
3. Feature mapping: 20-feature 1:1 bijection for Transfer.
4. OneHotEncoder attribution aggregation: 28 numerical + grouped merchant_category for Purchase.
5. Exactly 29 Purchase contributions and 20 Transfer contributions.
6. Descending absolute SHAP ordering.
7. Base value and contribution finiteness.
8. Mathematical additivity in the raw estimator output space.
9. Fraud-class detection and handling.
10. Error cases: missing preprocessor, missing model, invalid spec, missing features, unready model.

Note: Tests use realistic synthetic pipelines with scikit-learn tree components.
Champion artifacts are not in local MLflow registry.
"""

from typing import Any, Dict, List
import math
import numpy as np
import pandas as pd
import pytest
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import RandomForestClassifier
from sklearn.impute import SimpleImputer
from sklearn.isotonic import IsotonicRegression
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

from model_loader import (
    ModelEntry,
    ModelExplainer,
    ModelRegistry,
    create_model_explainer,
    explain_transaction,
    get_model_registry,
    get_sample_features_for_family,
    prepare_inference_input,
)
from schemas import (
    FeatureContribution,
    FeatureSpecMismatchError,
    FeatureValidationError,
    FeatureSpecValidator,
    ModelNotReadyError,
)


class MockCalibratedModelWrapper:
    """Mock wrapper mimicking M1's CalibratedModelWrapper."""

    def __init__(self, base_model: Any, calibrator: Any) -> None:
        self.base_model = base_model
        self.calibrator = calibrator
        self.classes_ = getattr(base_model, "classes_", np.array([0, 1]))

    def predict_proba(self, X: Any) -> np.ndarray:
        raw_p = self.base_model.predict_proba(X)[:, 1]
        cal_p = np.clip(self.calibrator.predict(raw_p), 0.0, 1.0)
        return np.column_stack([1.0 - cal_p, cal_p])

    def predict(self, X: Any) -> np.ndarray:
        p = self.predict_proba(X)[:, 1]
        return (p >= 0.5).astype(int)


def create_synthetic_purchase_pipeline() -> Pipeline:
    """Builds and fits a realistic synthetic purchase pipeline with ColumnTransformer + CalibratedModelWrapper."""
    val = FeatureSpecValidator()
    expected_meta = val.get_expected_features("purchase")
    all_cols = [f["name"] for f in expected_meta]
    num_cols = [c for c in all_cols if c != "merchant_category"]

    num_pipe = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", StandardScaler()),
    ])
    cat_pipe = Pipeline([
        ("imputer", SimpleImputer(strategy="most_frequent")),
        ("ohe", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
    ])
    preprocessor = ColumnTransformer([
        ("num", num_pipe, num_cols),
        ("cat", cat_pipe, ["merchant_category"]),
    ])

    # Generate 15 rows of synthetic data
    np.random.seed(42)
    data: Dict[str, Any] = {}
    categories = ["grocery_pos", "gas_transport", "misc_net", "shopping_pos"]
    for col in num_cols:
        data[col] = np.random.uniform(0.0, 100.0, size=15)
    data["merchant_category"] = [categories[i % len(categories)] for i in range(15)]
    df = pd.DataFrame(data, columns=all_cols)
    y = np.array([0, 0, 0, 0, 1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0])

    X_trans = preprocessor.fit_transform(df)

    rf = RandomForestClassifier(n_estimators=10, random_state=42)
    rf.fit(X_trans, y)

    # Calibrator on raw probabilities
    raw_p = rf.predict_proba(X_trans)[:, 1]
    iso = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    iso.fit(raw_p, y)

    calibrated_model = MockCalibratedModelWrapper(base_model=rf, calibrator=iso)

    return Pipeline([
        ("preprocessor", preprocessor),
        ("model", calibrated_model),
    ])


def create_synthetic_transfer_pipeline() -> Pipeline:
    """Builds and fits a realistic synthetic transfer pipeline with ColumnTransformer + CalibratedModelWrapper."""
    val = FeatureSpecValidator()
    expected_meta = val.get_expected_features("transfer")
    all_cols = [f["name"] for f in expected_meta]

    num_pipe = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        ("scaler", StandardScaler()),
    ])
    preprocessor = ColumnTransformer([
        ("num", num_pipe, all_cols),
    ])

    np.random.seed(42)
    data: Dict[str, Any] = {}
    for col in all_cols:
        data[col] = np.random.uniform(0.0, 500.0, size=15)
    df = pd.DataFrame(data, columns=all_cols)
    y = np.array([0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 0])

    X_trans = preprocessor.fit_transform(df)

    rf = RandomForestClassifier(n_estimators=10, random_state=42)
    rf.fit(X_trans, y)

    raw_p = rf.predict_proba(X_trans)[:, 1]
    iso = IsotonicRegression(y_min=0.0, y_max=1.0, out_of_bounds="clip")
    iso.fit(raw_p, y)

    calibrated_model = MockCalibratedModelWrapper(base_model=rf, calibrator=iso)

    return Pipeline([
        ("preprocessor", preprocessor),
        ("model", calibrated_model),
    ])


# =====================================================================
# Unit Tests
# =====================================================================

def test_extract_components_from_calibrated_wrapper() -> None:
    """Verifies that preprocessor and base estimator are correctly extracted from CalibratedModelWrapper."""
    pipeline = create_synthetic_purchase_pipeline()
    explainer = create_model_explainer("purchase", pipeline)

    assert explainer.preprocessor is not None
    assert isinstance(explainer.preprocessor, ColumnTransformer)
    assert explainer.base_model is not None
    assert isinstance(explainer.base_model, RandomForestClassifier)
    assert explainer.positive_class_index == 1


def test_extract_components_from_direct_model_step() -> None:
    """Verifies that if model step is directly an estimator (unwrapped), it is extracted cleanly."""
    pipeline = create_synthetic_transfer_pipeline()
    # Replace calibrated wrapper with direct estimator
    rf = pipeline.named_steps["model"].base_model
    direct_pipeline = Pipeline([
        ("preprocessor", pipeline.named_steps["preprocessor"]),
        ("model", rf),
    ])

    explainer = create_model_explainer("transfer", direct_pipeline)
    assert explainer.base_model is rf
    assert explainer.positive_class_index == 1


def test_extract_components_missing_preprocessor() -> None:
    """Verifies that missing 'preprocessor' step raises a clear ValueError."""
    rf = RandomForestClassifier()
    pipeline = Pipeline([("model", rf)])
    with pytest.raises(ValueError, match="does not contain a 'preprocessor' step"):
        create_model_explainer("transfer", pipeline)


def test_extract_components_missing_model() -> None:
    """Verifies that missing 'model' step raises a clear ValueError."""
    preprocessor = ColumnTransformer([])
    pipeline = Pipeline([("preprocessor", preprocessor)])
    with pytest.raises(ValueError, match="does not contain a 'model' step"):
        create_model_explainer("purchase", pipeline)


def test_transfer_feature_mapping_1to1() -> None:
    """Verifies that Transfer domain has exactly 20 features mapped 1:1 to transformed columns."""
    pipeline = create_synthetic_transfer_pipeline()
    explainer = create_model_explainer("transfer", pipeline)

    assert len(explainer.raw_feature_names) == 20
    assert len(explainer.feature_mapping) == 20
    for feat, indices in explainer.feature_mapping.items():
        assert len(indices) == 1, f"Transfer feature {feat} mapped to {len(indices)} columns (expected 1)"

    all_indices = [idx for indices in explainer.feature_mapping.values() for idx in indices]
    assert sorted(all_indices) == list(range(20))


def test_purchase_feature_mapping_and_ohe_aggregation() -> None:
    """Verifies that Purchase domain maps 28 numeric features and aggregates all OneHotEncoder columns for merchant_category."""
    pipeline = create_synthetic_purchase_pipeline()
    explainer = create_model_explainer("purchase", pipeline)

    assert len(explainer.raw_feature_names) == 29
    assert len(explainer.feature_mapping) == 29

    # 28 numeric features have 1 index each
    for feat, indices in explainer.feature_mapping.items():
        if feat != "merchant_category":
            assert len(indices) == 1, f"Numeric feature {feat} mapped to {len(indices)} columns"

    # merchant_category maps to multiple columns (>1)
    cat_indices = explainer.feature_mapping["merchant_category"]
    assert len(cat_indices) >= 2, f"Expected multiple OHE columns for merchant_category, got {cat_indices}"

    # All indices cover the entire transformed output space without duplicates
    all_indices = [idx for indices in explainer.feature_mapping.values() for idx in indices]
    assert len(all_indices) == len(set(all_indices))
    assert len(all_indices) == len(explainer.output_feature_names)


def test_successful_purchase_explanation() -> None:
    """Verifies complete Purchase explanation with exactly 29 contributions and descending absolute ordering."""
    pipeline = create_synthetic_purchase_pipeline()
    explainer = create_model_explainer("purchase", pipeline)

    features = get_sample_features_for_family("purchase")
    input_df = prepare_inference_input("purchase", features, "purchase-v2")
    base_val, contributions = explainer.explain(input_df, features)

    # Base value is finite
    assert not math.isnan(base_val)
    assert not math.isinf(base_val)

    # Exactly 29 contributions
    assert len(contributions) == 29

    # Contributions are FeatureContribution instances
    assert all(isinstance(c, FeatureContribution) for c in contributions)

    # merchant_category retains its original string value
    cat_contrib = next(c for c in contributions if c.feature == "merchant_category")
    assert cat_contrib.value == "grocery_pos"
    assert not math.isnan(cat_contrib.shap)
    assert not math.isinf(cat_contrib.shap)

    # Descending absolute ordering: |c[i]| >= |c[i+1]|
    for i in range(len(contributions) - 1):
        assert abs(contributions[i].shap) >= abs(contributions[i + 1].shap) - 1e-9


def test_successful_transfer_explanation() -> None:
    """Verifies complete Transfer explanation with exactly 20 contributions and descending absolute ordering."""
    pipeline = create_synthetic_transfer_pipeline()
    explainer = create_model_explainer("transfer", pipeline)

    features = get_sample_features_for_family("transfer")
    input_df = prepare_inference_input("transfer", features, "transfer-v2")
    base_val, contributions = explainer.explain(input_df, features)

    assert not math.isnan(base_val)
    assert not math.isinf(base_val)
    assert len(contributions) == 20

    # Descending absolute ordering
    for i in range(len(contributions) - 1):
        assert abs(contributions[i].shap) >= abs(contributions[i + 1].shap) - 1e-9


def test_shap_additivity_property() -> None:
    """
    Verifies that the sum of all aggregated SHAP contributions plus the base value
    equals the model's raw output prediction on the transformed input.
    sum(phi_i) + phi_0 == f(x)
    """
    pipeline = create_synthetic_purchase_pipeline()
    explainer = create_model_explainer("purchase", pipeline)

    features = get_sample_features_for_family("purchase")
    input_df = prepare_inference_input("purchase", features, "purchase-v2")
    base_val, contributions = explainer.explain(input_df, features)

    # Sum of all 29 feature contributions
    total_shap = sum(c.shap for c in contributions)

    # Compute expected output directly from underlying base_model
    X_trans = explainer.preprocessor.transform(input_df)
    # For RandomForestClassifier, predict_proba returns [P(0), P(1)]
    # TreeExplainer default output space is probability for RF
    rf_pred = explainer.base_model.predict_proba(X_trans)[0, explainer.positive_class_index]

    # Verify additivity holds closely (within rounding threshold of round(shap, 6))
    assert abs((total_shap + base_val) - rf_pred) < 1e-4, (
        f"Additivity violated: total_shap={total_shap}, base_val={base_val}, "
        f"sum={total_shap + base_val}, model_output={rf_pred}"
    )


def test_independent_explainer_initialization() -> None:
    """Verifies that Purchase and Transfer explainers are strictly independent."""
    p_pipe = create_synthetic_purchase_pipeline()
    t_pipe = create_synthetic_transfer_pipeline()

    p_explainer = create_model_explainer("purchase", p_pipe)
    t_explainer = create_model_explainer("transfer", t_pipe)

    assert p_explainer is not t_explainer
    assert p_explainer.family == "purchase"
    assert t_explainer.family == "transfer"
    assert len(p_explainer.raw_feature_names) == 29
    assert len(t_explainer.raw_feature_names) == 20
    assert "merchant_category" in p_explainer.feature_mapping
    assert "merchant_category" not in t_explainer.feature_mapping


def test_explain_transaction_registry_integration() -> None:
    """Verifies explain_transaction with ModelRegistry and ModelEntry."""
    registry = get_model_registry()
    p_pipe = create_synthetic_purchase_pipeline()
    p_explainer = create_model_explainer("purchase", p_pipe)

    registry.set_model(
        family="purchase",
        model=p_pipe,
        version="synthetic-p1",
        feature_spec_version="purchase-v2",
        explainer=p_explainer,
    )

    features = get_sample_features_for_family("purchase")
    entry, base_val, contributions = explain_transaction(
        family="purchase",
        features=features,
        feature_spec_version="purchase-v2",
    )

    assert entry.family == "purchase"
    assert entry.model_version == "synthetic-p1"
    assert len(contributions) == 29


def test_explain_transaction_model_not_ready() -> None:
    """Verifies HTTP 503 ModelNotReadyError when model is unloaded or explainer is None."""
    registry = get_model_registry()
    registry.reset("transfer")

    with pytest.raises(ModelNotReadyError, match="not loaded or not ready"):
        explain_transaction(
            family="transfer",
            features={"amount": 100.0},
            feature_spec_version="transfer-v2",
        )


def test_explain_transaction_unexplainable_model() -> None:
    """Verifies HTTP 503 ModelNotReadyError with technical reason when model is loaded without explainer."""
    registry = get_model_registry()
    # Dummy object as model without explainer
    registry.set_model(
        family="transfer",
        model={"not_a_pipeline": True},
        version="stub-v1",
        explainer=None,
        explainer_error="Pipeline missing required 'preprocessor' step",
    )

    with pytest.raises(ModelNotReadyError, match="does not support SHAP explanations"):
        explain_transaction(
            family="transfer",
            features={"amount": 100.0},
            feature_spec_version="transfer-v2",
        )


def test_explain_transaction_feature_validation_error() -> None:
    """Verifies that missing or extra features raise FeatureValidationError (HTTP 422)."""
    registry = get_model_registry()
    t_pipe = create_synthetic_transfer_pipeline()
    t_explainer = create_model_explainer("transfer", t_pipe)
    registry.set_model(family="transfer", model=t_pipe, version="t1", explainer=t_explainer)

    with pytest.raises(FeatureValidationError):
        explain_transaction(
            family="transfer",
            features={"amount": 100.0},  # Missing 19 features
            feature_spec_version="transfer-v2",
        )


def test_explain_transaction_spec_mismatch() -> None:
    """Verifies that invalid feature_spec_version raises FeatureSpecMismatchError (HTTP 409)."""
    registry = get_model_registry()
    t_pipe = create_synthetic_transfer_pipeline()
    t_explainer = create_model_explainer("transfer", t_pipe)
    registry.set_model(family="transfer", model=t_pipe, version="t1", explainer=t_explainer)

    with pytest.raises(FeatureSpecMismatchError):
        explain_transaction(
            family="transfer",
            features={"amount": 100.0},
            feature_spec_version="transfer-v1",  # Wrong version
        )
