"""
calibrated_wrapper.py — Independent wrapper for calibrated classifiers.

Defined in its own module to allow clean pickling and unpickling across
different runtime contexts, pytest suites, and ML serving containers
without depending on __main__.
"""

import numpy as np
from sklearn.base import BaseEstimator, ClassifierMixin


class CalibratedModelWrapper(BaseEstimator, ClassifierMixin):
    """Combines a base classifier and an isotonic probability calibrator."""

    def __init__(self, base_model=None, calibrator=None):
        self.base_model = base_model
        self.calibrator = calibrator
        self.classes_ = np.array([0, 1])

    def fit(self, X=None, y=None):
        """Fit stub to satisfy sklearn Pipeline contract."""
        return self

    def __sklearn_is_fitted__(self):
        """Signal to sklearn check_is_fitted that wrapper is ready for inference."""
        return self.base_model is not None and self.calibrator is not None

    def predict_proba(self, X):
        if hasattr(self.base_model, "predict_proba"):
            raw_p = self.base_model.predict_proba(X)[:, 1]
        else:
            df = self.base_model.decision_function(X)
            raw_p = 1.0 / (1.0 + np.exp(-df))
        cal_p = np.clip(self.calibrator.predict(raw_p), 0.0, 1.0)
        return np.column_stack([1.0 - cal_p, cal_p])

    def predict(self, X, threshold=0.5):
        return (self.predict_proba(X)[:, 1] >= threshold).astype(int)
