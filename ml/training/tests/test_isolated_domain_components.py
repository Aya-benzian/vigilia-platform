"""
test_isolated_domain_components.py — Unit test suite for isolated domain ML components.

Tests:
1. CalibratedModelWrapper (scikit-learn compliance, fit, predict, predict_proba, classes_).
2. Domain feature column constants and sentinels.
3. Split and preprocessing helpers in train_isolated_domains.
"""

import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sklearn.linear_model import LogisticRegression
from sklearn.isotonic import IsotonicRegression
from sklearn.svm import LinearSVC

SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from calibrated_wrapper import CalibratedModelWrapper
from cross_domain_features import (
    PURCHASE_COLUMNS_29,
    TRANSFER_COLUMNS_20,
    SENTINEL_TIME_S,
    SENTINEL_DAYS,
)
from train_isolated_domains import split_chronological, build_preprocessor


# ==============================================================================
# CalibratedModelWrapper Tests
# ==============================================================================

def test_calibrated_wrapper_initialization():
    wrapper = CalibratedModelWrapper()
    assert wrapper.base_model is None
    assert wrapper.calibrator is None
    # classes_ should default to [0, 1]
    np.testing.assert_array_equal(wrapper.classes_, np.array([0, 1]))


def test_calibrated_wrapper_fit_sets_classes():
    wrapper = CalibratedModelWrapper()
    wrapper.fit()
    np.testing.assert_array_equal(wrapper.classes_, np.array([0, 1]))


def test_calibrated_wrapper_classes_setter():
    wrapper = CalibratedModelWrapper()
    wrapper.classes_ = [10, 20]
    np.testing.assert_array_equal(wrapper.classes_, np.array([10, 20]))


def test_calibrated_wrapper_sklearn_is_fitted():
    wrapper = CalibratedModelWrapper()
    assert not wrapper.__sklearn_is_fitted__()

    base = LogisticRegression()
    cal = IsotonicRegression()
    wrapper = CalibratedModelWrapper(base_model=base, calibrator=cal)
    assert wrapper.__sklearn_is_fitted__()


def test_calibrated_wrapper_predict_proba_with_proba():
    X = np.array([[1.0], [2.0], [3.0], [4.0]])
    y = np.array([0, 0, 1, 1])

    base = LogisticRegression(random_state=42)
    base.fit(X, y)

    raw_p = base.predict_proba(X)[:, 1]
    cal = IsotonicRegression(out_of_bounds="clip")
    cal.fit(raw_p, y)

    wrapper = CalibratedModelWrapper(base_model=base, calibrator=cal)
    probs = wrapper.predict_proba(X)

    assert probs.shape == (4, 2)
    assert np.all((probs >= 0.0) & (probs <= 1.0))
    np.testing.assert_allclose(probs.sum(axis=1), np.ones(4), atol=1e-5)


def test_calibrated_wrapper_predict_proba_with_decision_function():
    X = np.array([[1.0], [2.0], [3.0], [4.0]])
    y = np.array([0, 0, 1, 1])

    base = LinearSVC(random_state=42, max_iter=2000)
    base.fit(X, y)

    df = base.decision_function(X)
    raw_p = 1.0 / (1.0 + np.exp(-df))
    cal = IsotonicRegression(out_of_bounds="clip")
    cal.fit(raw_p, y)

    wrapper = CalibratedModelWrapper(base_model=base, calibrator=cal)
    probs = wrapper.predict_proba(X)

    assert probs.shape == (4, 2)
    assert np.all((probs >= 0.0) & (probs <= 1.0))
    np.testing.assert_allclose(probs.sum(axis=1), np.ones(4), atol=1e-5)


def test_calibrated_wrapper_predict_threshold():
    X = np.array([[1.0], [2.0], [3.0], [4.0]])
    y = np.array([0, 0, 1, 1])

    base = LogisticRegression(random_state=42)
    base.fit(X, y)

    raw_p = base.predict_proba(X)[:, 1]
    cal = IsotonicRegression(out_of_bounds="clip")
    cal.fit(raw_p, y)

    wrapper = CalibratedModelWrapper(base_model=base, calibrator=cal)

    preds_50 = wrapper.predict(X, threshold=0.5)
    preds_00 = wrapper.predict(X, threshold=0.0)
    preds_99 = wrapper.predict(X, threshold=0.99)

    assert len(preds_50) == 4
    np.testing.assert_array_equal(preds_00, np.ones(4, dtype=int))
    assert set(np.unique(preds_99)).issubset({0, 1})


# ==============================================================================
# Domain Feature Constants Tests
# ==============================================================================

def test_purchase_columns_count_and_uniqueness():
    assert len(PURCHASE_COLUMNS_29) == 29
    assert len(set(PURCHASE_COLUMNS_29)) == 29
    # Crucial domain isolation verification: zero transfer features
    transfer_keywords = ["sender", "recipient", "transfer", "balance"]
    for col in PURCHASE_COLUMNS_29:
        for kw in transfer_keywords:
            assert kw not in col.lower(), f"Unexpected transfer keyword '{kw}' found in purchase feature '{col}'"


def test_transfer_columns_count_and_uniqueness():
    assert len(TRANSFER_COLUMNS_20) == 20
    assert len(set(TRANSFER_COLUMNS_20)) == 20
    # Crucial domain isolation verification: zero purchase/merchant features
    purchase_keywords = ["merchant", "merch", "city_pop", "distance_home"]
    for col in TRANSFER_COLUMNS_20:
        for kw in purchase_keywords:
            assert kw not in col.lower(), f"Unexpected purchase keyword '{kw}' found in transfer feature '{col}'"


def test_sentinels_values():
    assert SENTINEL_TIME_S == 2_592_000.0  # 30 days in seconds
    assert SENTINEL_DAYS == 30.0


# ==============================================================================
# Training Pipeline Helper Functions Tests
# ==============================================================================

def test_split_chronological_ratios():
    n_rows = 1000
    df = pd.DataFrame({
        "timestamp": pd.date_range("2025-01-01", periods=n_rows, freq="h"),
        "val": np.arange(n_rows),
    })

    train_df, cal_df, val_df, test_df = split_chronological(df)

    assert len(train_df) == 600
    assert len(cal_df) == 100
    assert len(val_df) == 150
    assert len(test_df) == 150

    # Ensure strictly sorted chronological order across partitions
    assert train_df["timestamp"].max() < cal_df["timestamp"].min()
    assert cal_df["timestamp"].max() < val_df["timestamp"].min()
    assert val_df["timestamp"].max() < test_df["timestamp"].min()


def test_build_preprocessor_transformation():
    numeric_cols = ["f1", "f2"]
    cat_cols = ["cat1"]

    df = pd.DataFrame({
        "f1": [1.0, 2.0, np.nan, 4.0],
        "f2": [10.0, 20.0, 30.0, 40.0],
        "cat1": ["A", "B", "A", None],
        "unused": [99, 99, 99, 99],
    })

    preprocessor = build_preprocessor(numeric_cols, cat_cols)
    preprocessor.fit(df)
    transformed = preprocessor.transform(df)

    assert transformed is not None
    assert transformed.shape[0] == 4
    # Numeric features (2) + One-hot encoded features (>= 2)
    assert transformed.shape[1] >= 3

