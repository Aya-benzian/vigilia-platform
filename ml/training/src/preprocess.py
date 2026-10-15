"""
preprocess.py — Feature engineering and sklearn Pipeline for Member 1.

Responsibilities:
- Define all engineered features (amt_log, hour, dow, is_night, distance_km, age,
  city_pop_log) as sklearn transformers or FunctionTransformers.
- Assemble one ColumnTransformer inside a Pipeline.
- The Pipeline is saved with the model so that Member 2 receives a single object
  that accepts raw input and returns a prediction.

Contract 1 note:
- The output of this module (feature names, types, order, preprocessing) is exactly
  what Member 2 must receive. The feature_spec.json is generated from this module.
- Member 3 (Transaction Service) must supply the raw fields listed in CONTRACT_1_RAW_FIELDS.
- No history-based features in v1 (they require state on the serving side).

Rules:
- ALL preprocessing is fitted INSIDE the Pipeline, INSIDE each CV fold.
- Resampling (if any) only inside training folds — never applied to test/val data.
- Imputers are always present even if there are no missing values on day 1
  (the serving side may receive incomplete data).
- Unseen categories → handle_unknown="ignore" in OneHotEncoder.
- Scaling only where required (see table in design v5 §5):
    LogReg  → StandardScaler
    RF      → no scaling
    XGBoost → no scaling
    SVM     → StandardScaler mandatory
    MLP     → StandardScaler mandatory
- The Pipeline is model-specific: pass `model_type` to build_pipeline().
"""

import logging
from typing import Literal

import numpy as np
import pandas as pd
from sklearn.pipeline import Pipeline
from sklearn.compose import ColumnTransformer
from sklearn.preprocessing import (
    StandardScaler,
    OneHotEncoder,
    FunctionTransformer,
)
from sklearn.impute import SimpleImputer

from config import DROP_COLS, GROUP_COL, TARGET_COL

logger = logging.getLogger(__name__)

# ==============================================================================
# Contract 1 — Raw input fields (agree with Members 3 and 4 before finalizing)
# ==============================================================================
# These are the raw fields the platform must supply to the ML service.
# Verify names against the real Sparkov CSV on day 1.
CONTRACT_1_RAW_FIELDS = [
    "trans_date_trans_time",  # timestamp
    "amt",                    # transaction amount
    "category",               # merchant category
    "gender",                 # cardholder gender
    "dob",                    # date of birth → age
    "lat",                    # cardholder latitude
    "long",                   # cardholder longitude
    "city_pop",               # city population
    "merch_lat",              # merchant latitude
    "merch_long",             # merchant longitude
    # [NICE] "merchant", "job", "state" — only if target/frequency encoding is used
]
# Note: dob and coordinates are personal data.
# Report must state that the service receives only what the model needs.


# ==============================================================================
# Feature engineering helpers
# ==============================================================================

def haversine_km(
    lat1: np.ndarray,
    lon1: np.ndarray,
    lat2: np.ndarray,
    lon2: np.ndarray,
) -> np.ndarray:
    """
    Compute great-circle distance in kilometres between two sets of coordinates.

    Uses the Haversine formula. Tested in tests/test_preprocess.py on a known
    pair (see unit test for exact expected value).

    Args:
        lat1, lon1: Cardholder coordinates (degrees).
        lat2, lon2: Merchant coordinates (degrees).

    Returns:
        Array of distances in km.
    """
    raise NotImplementedError


def add_engineered_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add all engineered features to a copy of df.

    Features added:
        amt_log      = log1p(amt)
        hour         = transaction hour (0–23)
        dow          = day of week (0=Monday)
        is_night     = 1 if hour in [0, 5] else 0
        distance_km  = haversine(lat, long, merch_lat, merch_long)
        age          = (trans_date - dob).days / 365.25
        city_pop_log = log1p(city_pop)

    Args:
        df: Raw DataFrame (must contain the Sparkov columns).

    Returns:
        New DataFrame with engineered columns added. Original columns kept.

    Notes:
        - trans_date_trans_time must be parsed as datetime before calling this.
        - dob must be parsed as date before calling this.
        - Does NOT drop DROP_COLS or GROUP_COL — that happens in build_pipeline().
    """
    raise NotImplementedError


# ==============================================================================
# Numerical and categorical feature lists
# (verify against real column names on day 1)
# ==============================================================================

NUMERIC_FEATURES = [
    "amt_log",
    "hour",
    "dow",
    "is_night",
    "distance_km",
    "age",
    "city_pop_log",
]

CATEGORICAL_FEATURES = [
    "category",
    "gender",
    # [NICE]: "merchant", "job", "state" — via frequency or OOF target encoding
]


# ==============================================================================
# Pipeline builder
# ==============================================================================

ModelType = Literal["logreg", "rf", "xgboost", "svm", "mlp", "dummy", "linear_svc"]


def build_pipeline(model, model_type: ModelType) -> Pipeline:
    """
    Assemble a full sklearn Pipeline: feature engineering → preprocessing → model.

    The Pipeline is the single object logged to MLflow and served by Member 2.
    It accepts the raw Sparkov fields and returns a prediction directly.

    Scaling strategy (§5 of design v5):
        logreg, svm, mlp, linear_svc → StandardScaler on numeric features
        rf, xgboost, dummy           → no scaling (passthrough)

    Args:
        model: A fitted or unfitted sklearn-compatible estimator.
        model_type: One of the ModelType literals. Controls scaling.

    Returns:
        An sklearn Pipeline ready for fit() and predict_proba().

    Notes:
        - Imputers are always present (median for numeric, most-frequent for categorical).
        - OneHotEncoder uses handle_unknown="ignore" so unseen categories are all zeros.
        - The Pipeline does NOT apply resampling — that is handled inside CV folds
          by the caller (cv_compare.py) using imbalanced-learn Pipeline if needed.
    """
    raise NotImplementedError


def export_feature_spec(output_path) -> dict:
    """
    Generate and save feature_spec.json from the CONTRACT_1_RAW_FIELDS and
    NUMERIC_FEATURES / CATEGORICAL_FEATURES lists.

    This file is the machine-readable part of Contract 1 (§5 of design v5).
    Member 2 imports it so there is no mismatch between what the Training side
    produces and what the Serving side expects.

    Output format (example):
    {
        "schema_version": "1.0",
        "raw_input_fields": [
            {"name": "amt", "type": "float", "unit": "USD", "range": [0, null]},
            ...
        ],
        "engineered_features": [
            {"name": "amt_log", "type": "float", "formula": "log1p(amt)"},
            ...
        ],
        "feature_order": ["amt_log", "hour", ...],
        "categorical_features": ["category", "gender"],
        "target": "is_fraud"
    }

    Args:
        output_path: Path to write feature_spec.json.

    Returns:
        The spec dict (also written to output_path as JSON).
    """
    raise NotImplementedError
