"""
test_baselines.py — Unit tests for baseline classifiers (Member 1).

Tests verify:
- make_dummy_classifier returns correct type and strategy
- AmountThresholdClassifier fit/predict/predict_proba API contract
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import numpy as np
import pandas as pd
import pytest
from sklearn.dummy import DummyClassifier

from baselines import make_dummy_classifier, AmountThresholdClassifier
from config import SEED


# ==============================================================================
# DummyClassifier factory
# ==============================================================================

def test_make_dummy_classifier_returns_dummy():
    clf = make_dummy_classifier()
    assert isinstance(clf, DummyClassifier)


def test_make_dummy_classifier_default_strategy_is_stratified():
    clf = make_dummy_classifier()
    assert clf.strategy == "stratified"


def test_make_dummy_classifier_most_frequent_strategy():
    clf = make_dummy_classifier(strategy="most_frequent")
    assert clf.strategy == "most_frequent"


def test_make_dummy_classifier_prior_strategy():
    clf = make_dummy_classifier(strategy="prior")
    assert clf.strategy == "prior"


def test_make_dummy_classifier_has_seed():
    clf = make_dummy_classifier()
    assert clf.random_state == SEED


def test_make_dummy_classifier_fit_predict():
    X = np.zeros((100, 3))
    y = np.array([0] * 95 + [1] * 5)
    clf = make_dummy_classifier()
    clf.fit(X, y)
    preds = clf.predict(X)
    assert preds.shape == (100,)
    assert set(preds).issubset({0, 1})


# ==============================================================================
# AmountThresholdClassifier
# ==============================================================================

@pytest.fixture
def small_dataset():
    """Minimal fraud dataset: high-amount = fraud, low-amount = legit."""
    np.random.seed(42)
    n = 200
    amt = np.concatenate([
        np.random.uniform(1, 100, 190),
        np.random.uniform(500, 2000, 10),
    ])
    y = np.concatenate([np.zeros(190), np.ones(10)]).astype(int)
    X = pd.DataFrame({"amt": amt, "other": np.random.randn(n)})
    return X, y


def test_amount_threshold_instantiates():
    clf = AmountThresholdClassifier()
    assert clf is not None


def test_amount_threshold_fit_returns_self(small_dataset):
    X, y = small_dataset
    result = AmountThresholdClassifier().fit(X, y)
    assert isinstance(result, AmountThresholdClassifier)


def test_amount_threshold_fit_sets_threshold(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier()
    clf.fit(X, y)
    assert hasattr(clf, "threshold_")
    assert isinstance(float(clf.threshold_), float)
    assert clf.threshold_ > 0


def test_amount_threshold_predict_shape(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    preds = clf.predict(X)
    assert preds.shape == (len(X),)


def test_amount_threshold_predict_binary(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    preds = clf.predict(X)
    assert set(preds).issubset({0, 1})


def test_amount_threshold_predict_proba_shape(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    proba = clf.predict_proba(X)
    assert proba.shape == (len(X), 2)


def test_amount_threshold_predict_proba_sums_to_one(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    proba = clf.predict_proba(X)
    np.testing.assert_allclose(proba.sum(axis=1), 1.0, atol=1e-6)


def test_amount_threshold_high_amount_is_fraud(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    high = pd.DataFrame({"amt": [9999.0], "other": [0.0]})
    assert clf.predict(high)[0] == 1


def test_amount_threshold_low_amount_is_legit(small_dataset):
    X, y = small_dataset
    clf = AmountThresholdClassifier().fit(X, y)
    low = pd.DataFrame({"amt": [0.01], "other": [0.0]})
    assert clf.predict(low)[0] == 0
