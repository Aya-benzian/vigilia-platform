"""
test_models.py — Unit tests for model factory functions (Member 1).

These tests verify factory behavior without training data:
- Correct estimator types returned
- random_state is always SEED (never caller-overridable)
- Default hyperparameters are reasonable
- Custom params are applied (except random_state)
"""

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "src"))

import pytest
from sklearn.linear_model import LogisticRegression
from sklearn.ensemble import RandomForestClassifier
from sklearn.svm import LinearSVC, SVC
from sklearn.neural_network import MLPClassifier

from models import (
    make_logreg,
    make_random_forest,
    make_xgboost,
    make_svm,
    make_mlp,
    MODEL_FACTORIES,
)
from config import SEED


# ==============================================================================
# Logistic Regression
# ==============================================================================

def test_make_logreg_returns_correct_type():
    model = make_logreg()
    assert isinstance(model, LogisticRegression)


def test_make_logreg_has_seed():
    model = make_logreg()
    assert model.random_state == SEED


def test_make_logreg_default_class_weight():
    model = make_logreg()
    assert model.class_weight == "balanced"


def test_make_logreg_custom_params_applied():
    model = make_logreg({"C": 10.0})
    assert model.C == 10.0


def test_make_logreg_seed_not_overridable():
    """Caller cannot override random_state."""
    model = make_logreg({"random_state": 999})
    assert model.random_state == SEED


# ==============================================================================
# Random Forest
# ==============================================================================

def test_make_rf_returns_correct_type():
    model = make_random_forest()
    assert isinstance(model, RandomForestClassifier)


def test_make_rf_has_seed():
    model = make_random_forest()
    assert model.random_state == SEED


def test_make_rf_default_class_weight():
    model = make_random_forest()
    assert model.class_weight == "balanced_subsample"


def test_make_rf_custom_n_estimators():
    model = make_random_forest({"n_estimators": 50})
    assert model.n_estimators == 50


def test_make_rf_seed_not_overridable():
    model = make_random_forest({"random_state": 999})
    assert model.random_state == SEED


# ==============================================================================
# XGBoost
# ==============================================================================

def test_make_xgboost_returns_correct_type():
    from xgboost import XGBClassifier
    model = make_xgboost()
    assert isinstance(model, XGBClassifier)


def test_make_xgboost_has_seed():
    model = make_xgboost()
    assert model.random_state == SEED


def test_make_xgboost_scale_pos_weight_applied():
    model = make_xgboost(scale_pos_weight=10.0)
    assert model.scale_pos_weight == 10.0


def test_make_xgboost_seed_not_overridable():
    model = make_xgboost(params={"random_state": 999})
    assert model.random_state == SEED


# ==============================================================================
# SVM
# ==============================================================================

def test_make_svm_linear_returns_linearsvc():
    model = make_svm(use_linear=True)
    assert isinstance(model, LinearSVC)


def test_make_svm_rbf_returns_svc():
    model = make_svm(use_linear=False)
    assert isinstance(model, SVC)


def test_make_svm_linear_has_seed():
    model = make_svm(use_linear=True)
    assert model.random_state == SEED


def test_make_svm_rbf_has_seed():
    model = make_svm(use_linear=False)
    assert model.random_state == SEED


def test_make_svm_seed_not_overridable():
    model = make_svm(use_linear=True, params={"random_state": 999})
    assert model.random_state == SEED


def test_make_svm_rbf_class_weight():
    model = make_svm(use_linear=False)
    assert model.class_weight == "balanced"


# ==============================================================================
# MLP
# ==============================================================================

def test_make_mlp_returns_correct_type():
    model = make_mlp()
    assert isinstance(model, MLPClassifier)


def test_make_mlp_has_seed():
    model = make_mlp()
    assert model.random_state == SEED


def test_make_mlp_early_stopping_enabled():
    model = make_mlp()
    assert model.early_stopping is True


def test_make_mlp_custom_hidden_layers():
    model = make_mlp({"hidden_layer_sizes": (64, 32)})
    assert model.hidden_layer_sizes == (64, 32)


def test_make_mlp_seed_not_overridable():
    model = make_mlp({"random_state": 999})
    assert model.random_state == SEED


# ==============================================================================
# Model registry
# ==============================================================================

def test_model_factories_registry_has_all_models():
    expected = {"logreg", "rf", "xgboost", "svm", "mlp"}
    assert set(MODEL_FACTORIES.keys()) == expected


def test_all_factories_callable():
    for name, factory in MODEL_FACTORIES.items():
        assert callable(factory), f"{name} factory is not callable"
