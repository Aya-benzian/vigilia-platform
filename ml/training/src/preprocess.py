"""
preprocess.py — Feature engineering and sklearn Pipeline for Member 1.

Contract 1: the Pipeline built here is the single object Member 2 receives.
It accepts raw Sparkov fields and returns predictions directly.
"""

import logging
import json
from pathlib import Path
from typing import Literal

import numpy as np
import pandas as pd
from sklearn.compose import ColumnTransformer
from sklearn.impute import SimpleImputer
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import (
    FunctionTransformer,
    OneHotEncoder,
    StandardScaler,
)

from config import DROP_COLS, GROUP_COL, TARGET_COL, ARTIFACTS_DIR

logger = logging.getLogger(__name__)

# ==============================================================================
# Contract 1 — Raw input fields (agreed with Members 3 and 4)
# ==============================================================================
CONTRACT_1_RAW_FIELDS = [
    "trans_date_trans_time",
    "amt",
    "category",
    "gender",
    "dob",
    "lat",
    "long",
    "city_pop",
    "merch_lat",
    "merch_long",
]

# ==============================================================================
# Feature lists
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
]

ModelType = Literal["logreg", "rf", "xgboost", "svm", "mlp", "dummy", "linear_svc"]


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
    Compute great-circle distance in kilometres using the Haversine formula.

    Args:
        lat1, lon1: Cardholder coordinates (degrees).
        lat2, lon2: Merchant coordinates (degrees).

    Returns:
        Array of distances in km.
    """
    R = 6371.0  # Earth radius in km

    lat1_r = np.radians(lat1)
    lat2_r = np.radians(lat2)
    dlat   = np.radians(lat2 - lat1)
    dlon   = np.radians(lon2 - lon1)

    a = np.sin(dlat / 2) ** 2 + np.cos(lat1_r) * np.cos(lat2_r) * np.sin(dlon / 2) ** 2
    c = 2 * np.arctan2(np.sqrt(a), np.sqrt(1 - a))

    return R * c


def add_engineered_features(df: pd.DataFrame) -> pd.DataFrame:
    """
    Add all engineered features to a copy of df.

    Features added:
        amt_log      = log1p(amt)
        hour         = transaction hour (0-23)
        dow          = day of week (0=Monday)
        is_night     = 1 if hour in [0..5] else 0
        distance_km  = haversine(lat, long, merch_lat, merch_long)
        age          = (trans_date - dob).days / 365.25
        city_pop_log = log1p(city_pop)

    Args:
        df: Raw DataFrame containing Sparkov columns.

    Returns:
        New DataFrame with engineered columns added.
    """
    df = df.copy()

    # --- Parse datetime if not already done ---
    if not pd.api.types.is_datetime64_any_dtype(df["trans_date_trans_time"]):
        df["trans_date_trans_time"] = pd.to_datetime(df["trans_date_trans_time"])

    # --- Parse dob if it's a string ---
    if "dob" in df.columns:
        if not pd.api.types.is_datetime64_any_dtype(df["dob"]):
            df["dob"] = pd.to_datetime(df["dob"])

    # --- Amount log transform ---
    df["amt_log"] = np.log1p(df["amt"].astype(float))

    # --- Time features ---
    df["hour"]     = df["trans_date_trans_time"].dt.hour
    df["dow"]      = df["trans_date_trans_time"].dt.dayofweek
    df["is_night"] = (df["hour"].between(0, 5)).astype(int)

    # --- Geographic distance ---
    df["distance_km"] = haversine_km(
        df["lat"].values.astype(float),
        df["long"].values.astype(float),
        df["merch_lat"].values.astype(float),
        df["merch_long"].values.astype(float),
    )

    # --- Cardholder age at transaction time ---
    if "dob" in df.columns:
        df["age"] = (
            df["trans_date_trans_time"] - df["dob"]
        ).dt.days / 365.25
    else:
        df["age"] = np.nan  # will be imputed

    # --- City population log transform ---
    df["city_pop_log"] = np.log1p(df["city_pop"].astype(float))

    return df


# ==============================================================================
# Sklearn-compatible transformer wrappers
# ==============================================================================

def _engineer_features(X: pd.DataFrame) -> pd.DataFrame:
    """Wrapper for use inside FunctionTransformer."""
    return add_engineered_features(X)


def _drop_unwanted_cols(X: pd.DataFrame) -> pd.DataFrame:
    """Drop identifier columns and group key. Safe if column not present."""
    cols_to_drop = [c for c in DROP_COLS + [GROUP_COL] if c in X.columns]
    # Also drop raw columns that are now replaced by engineered features
    raw_to_drop  = ["trans_date_trans_time", "dob", "amt",
                    "lat", "long", "merch_lat", "merch_long", "city_pop"]
    cols_to_drop += [c for c in raw_to_drop if c in X.columns]
    return X.drop(columns=cols_to_drop, errors="ignore")


def _to_dataframe(X, feature_names=None) -> pd.DataFrame:
    """Convert numpy array back to DataFrame after ColumnTransformer."""
    if isinstance(X, pd.DataFrame):
        return X
    return pd.DataFrame(X, columns=feature_names)


# ==============================================================================
# Pipeline builder
# ==============================================================================

def build_pipeline(model, model_type: ModelType) -> Pipeline:
    """
    Assemble a full sklearn Pipeline:
        feature engineering → drop identifiers → preprocessing → (scale) → model

    Scaling rules (§5 of design v5):
        logreg, svm, mlp, linear_svc → StandardScaler on numeric features
        rf, xgboost, dummy           → no scaling (passthrough)

    Args:
        model: An unfitted sklearn-compatible estimator.
        model_type: Controls whether StandardScaler is applied.

    Returns:
        An sklearn Pipeline ready for fit() and predict_proba().
    """
    SCALE_MODELS = {"logreg", "svm", "linear_svc", "mlp"}
    apply_scaling = model_type in SCALE_MODELS

    # Step 1 — Feature engineering (FunctionTransformer keeps DataFrame intact)
    engineer = FunctionTransformer(func=_engineer_features, validate=False)

    # Step 2 — Drop identifiers and raw columns
    dropper = FunctionTransformer(func=_drop_unwanted_cols, validate=False)

    # Step 3 — Column-level preprocessing
    num_transformer = Pipeline([
        ("imputer", SimpleImputer(strategy="median")),
        *([("scaler", StandardScaler())] if apply_scaling else []),
    ])

    cat_transformer = Pipeline([
        ("imputer", SimpleImputer(strategy="most_frequent")),
        ("encoder", OneHotEncoder(handle_unknown="ignore", sparse_output=False)),
    ])

    preprocessor = ColumnTransformer(
        transformers=[
            ("num", num_transformer, NUMERIC_FEATURES),
            ("cat", cat_transformer, CATEGORICAL_FEATURES),
        ],
        remainder="drop",   # drop any column not in NUMERIC_FEATURES or CATEGORICAL_FEATURES
    )

    steps = [
        ("engineer",    engineer),
        ("dropper",     dropper),
        ("preprocessor", preprocessor),
        ("model",       model),
    ]

    pipe = Pipeline(steps)
    logger.debug(
        f"Pipeline built for model_type='{model_type}', "
        f"scaling={'yes' if apply_scaling else 'no'}"
    )
    return pipe


# ==============================================================================
# Feature spec export (Contract 1)
# ==============================================================================

def export_feature_spec(output_path: Path = None) -> dict:
    """
    Generate and save feature_spec.json from CONTRACT_1_RAW_FIELDS and
    NUMERIC_FEATURES / CATEGORICAL_FEATURES lists.

    This is the machine-readable part of Contract 1.
    Member 2 imports this so there is no mismatch between Training and Serving.

    Args:
        output_path: Path to write feature_spec.json.
                     Defaults to ARTIFACTS_DIR / "feature_spec.json".

    Returns:
        The spec dict (also written to output_path as JSON).
    """
    if output_path is None:
        output_path = ARTIFACTS_DIR / "feature_spec.json"

    spec = {
        "schema_version": "1.0",
        "raw_input_fields": [
            {"name": "trans_date_trans_time", "type": "datetime", "format": "ISO8601"},
            {"name": "amt",       "type": "float",  "unit": "USD",     "range": [0, None]},
            {"name": "category",  "type": "string", "note": "merchant category"},
            {"name": "gender",    "type": "string", "values": ["M", "F"]},
            {"name": "dob",       "type": "date",   "format": "YYYY-MM-DD"},
            {"name": "lat",       "type": "float",  "range": [-90, 90]},
            {"name": "long",      "type": "float",  "range": [-180, 180]},
            {"name": "city_pop",  "type": "int",    "range": [1, None]},
            {"name": "merch_lat", "type": "float",  "range": [-90, 90]},
            {"name": "merch_long","type": "float",  "range": [-180, 180]},
        ],
        "engineered_features": [
            {"name": "amt_log",      "type": "float", "formula": "log1p(amt)"},
            {"name": "hour",         "type": "int",   "formula": "trans_date_trans_time.hour"},
            {"name": "dow",          "type": "int",   "formula": "trans_date_trans_time.dayofweek"},
            {"name": "is_night",     "type": "int",   "formula": "1 if hour in [0..5] else 0"},
            {"name": "distance_km",  "type": "float", "formula": "haversine(lat, long, merch_lat, merch_long)"},
            {"name": "age",          "type": "float", "formula": "(trans_date - dob).days / 365.25"},
            {"name": "city_pop_log", "type": "float", "formula": "log1p(city_pop)"},
        ],
        "numeric_features":      NUMERIC_FEATURES,
        "categorical_features":  CATEGORICAL_FEATURES,
        "target":                TARGET_COL,
        "drop_columns":          DROP_COLS + [GROUP_COL],
    }

    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "w") as f:
        json.dump(spec, f, indent=2)

    logger.info(f"Feature spec written to {output_path}")
    return spec
