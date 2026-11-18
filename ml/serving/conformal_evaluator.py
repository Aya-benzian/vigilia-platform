"""
Split Conformal Prediction Evaluator for Vigilia ML Serving (M2).

This module implements the isolated online evaluation step of Split Conformal
Prediction for binary fraud classification (Design 2 / Final v4.2).

Mathematical Formulation:
    Binary classes: {"LEGITIMATE", "FRAUD"}
    Given calibrated fraud probability: p = P(FRAUD | x) in [0.0, 1.0]
    Nonconformity scores:
        s(FRAUD) = 1.0 - p
        s(LEGITIMATE) = p
    Given conformal quantile q in [0.0, 1.0] U {+infinity}:
        Gamma(x) = { y in {"LEGITIMATE", "FRAUD"} : s(x, y) <= q }
        - Include LEGITIMATE if p <= q
        - Include FRAUD if 1.0 - p <= q

Ordering Guarantee:
    Deterministic ordering: ["LEGITIMATE", "FRAUD"].
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import math
from typing import Any, Dict, List, Optional


class ConformalStatus(str, Enum):
    """
    Categorical outcome of the conformal prediction set construction.

    SINGLETON: Exactly one class is retained (certain prediction).
    AMBIGUOUS: Both classes are retained (statistical ambiguity / undecidable).
    EMPTY: Neither class is retained (borderline / unsupported by strict threshold).
    """
    SINGLETON = "SINGLETON"
    AMBIGUOUS = "AMBIGUOUS"
    EMPTY = "EMPTY"


@dataclass(frozen=True)
class ConformalEvaluationResult:
    """
    Immutable structured result of a Split Conformal evaluation.

    Attributes:
        prediction_set: Deterministically ordered list of accepted classes
                        (["LEGITIMATE"], ["FRAUD"], ["LEGITIMATE", "FRAUD"], or []).
        status: Categorical status (SINGLETON, AMBIGUOUS, EMPTY).
        is_ambiguous: True if len(prediction_set) != 1.
        fraud_probability: Validated calibrated fraud probability p in [0.0, 1.0].
        quantile: Validated conformal quantile q in [0.0, 1.0] U {+inf}.
        nonconformity_scores: Mapping of class names to their nonconformity scores.
    """
    prediction_set: List[str]
    status: ConformalStatus
    is_ambiguous: bool
    fraud_probability: float
    quantile: float
    nonconformity_scores: Dict[str, float]


def _is_score_conformal(score: float, quantile: float, tolerance: float = 1e-9) -> bool:
    """
    Tests whether a nonconformity score is within the conformal threshold.

    Accounts for standard floating-point boundary representation artifacts
    (e.g. 1.0 - 0.58 = 0.42000000000000004 vs 0.42).
    """
    if score <= quantile:
        return True
    if math.isinf(quantile):
        return False
    return math.isclose(score, quantile, rel_tol=tolerance, abs_tol=tolerance)


class ConformalEvaluator:
    """
    Isolated Split Conformal Prediction Evaluator for binary fraud classification.

    Thread-safe and stateless by default. Can optionally be initialized with
    a pinned default quantile for repeated evaluations.
    """

    CLASS_LEGITIMATE: str = "LEGITIMATE"
    CLASS_FRAUD: str = "FRAUD"

    def __init__(self, default_quantile: Optional[float] = None) -> None:
        """
        Initializes the evaluator with an optional pinned default quantile.

        Args:
            default_quantile: Optional fallback quantile if not passed to evaluate().
        """
        self._default_quantile: Optional[float] = None
        if default_quantile is not None:
            self._default_quantile = self.validate_quantile(default_quantile)

    @property
    def default_quantile(self) -> Optional[float]:
        """Returns the configured default quantile, if any."""
        return self._default_quantile

    @staticmethod
    def validate_fraud_probability(fraud_probability: Any) -> float:
        """
        Strictly validates that fraud_probability is a finite float in [0.0, 1.0].

        Raises:
            TypeError: If fraud_probability is a boolean, string, None, or non-numeric type.
            ValueError: If fraud_probability is NaN, infinite, or outside [0.0, 1.0].
        """
        if isinstance(fraud_probability, bool):
            raise TypeError(
                f"fraud_probability must be a numeric float, got bool: {fraud_probability!r}"
            )
        if not isinstance(fraud_probability, (int, float)):
            raise TypeError(
                f"fraud_probability must be a numeric float, got {type(fraud_probability).__name__}: {fraud_probability!r}"
            )
        if math.isnan(fraud_probability) or math.isinf(fraud_probability):
            raise ValueError(
                f"fraud_probability must be a finite numeric value, got: {fraud_probability}"
            )
        if fraud_probability < 0.0 or fraud_probability > 1.0:
            raise ValueError(
                f"fraud_probability must be in range [0.0, 1.0], got: {fraud_probability}"
            )
        return float(fraud_probability)

    @staticmethod
    def validate_quantile(quantile: Any) -> float:
        """
        Strictly validates that quantile is in [0.0, 1.0] U {+infinity}.

        Raises:
            TypeError: If quantile is a boolean, string, None, or non-numeric type.
            ValueError: If quantile is NaN, negative infinity, negative, or > 1.0 (unless +inf).
        """
        if isinstance(quantile, bool):
            raise TypeError(
                f"quantile must be a numeric float, got bool: {quantile!r}"
            )
        if not isinstance(quantile, (int, float)):
            raise TypeError(
                f"quantile must be a numeric float, got {type(quantile).__name__}: {quantile!r}"
            )
        if math.isnan(quantile):
            raise ValueError("quantile cannot be NaN")
        if math.isinf(quantile):
            if quantile < 0:
                raise ValueError("quantile cannot be negative infinity")
            return float("inf")
        if quantile < 0.0:
            raise ValueError(f"quantile cannot be negative, got: {quantile}")
        if quantile > 1.0:
            raise ValueError(
                f"quantile cannot be greater than 1.0 unless it is +infinity, got: {quantile}"
            )
        return float(quantile)

    def evaluate(
        self,
        fraud_probability: float,
        quantile: Optional[float] = None,
    ) -> ConformalEvaluationResult:
        """
        Evaluates prediction set membership using Split Conformal nonconformity scores.

        Args:
            fraud_probability: Calibrated probability of fraud in [0.0, 1.0].
            quantile: Conformal quantile q in [0.0, 1.0] U {+infinity}.
                      If omitted, uses self.default_quantile.

        Returns:
            ConformalEvaluationResult containing the prediction set and metadata.

        Raises:
            ValueError: If quantile is not provided and no default was set.
            TypeError: If inputs fail type validation.
            ValueError: If inputs fail numerical domain validation.
        """
        # 1. Resolve and validate quantile
        effective_q_raw = quantile if quantile is not None else self._default_quantile
        if effective_q_raw is None:
            raise ValueError("Conformal quantile must be provided either in evaluate() or at initialization.")
        q = self.validate_quantile(effective_q_raw)

        # 2. Validate fraud probability
        p = self.validate_fraud_probability(fraud_probability)

        # 3. Calculate nonconformity scores
        s_legit = p
        s_fraud = float(1.0 - p)
        scores = {
            self.CLASS_LEGITIMATE: s_legit,
            self.CLASS_FRAUD: s_fraud,
        }

        # 4. Construct prediction set with deterministic ordering (LEGITIMATE first, then FRAUD)
        prediction_set: List[str] = []
        if _is_score_conformal(s_legit, q):
            prediction_set.append(self.CLASS_LEGITIMATE)
        if _is_score_conformal(s_fraud, q):
            prediction_set.append(self.CLASS_FRAUD)

        # 5. Determine categorical status and ambiguity
        set_size = len(prediction_set)
        if set_size == 1:
            status = ConformalStatus.SINGLETON
            is_ambiguous = False
        elif set_size == 2:
            status = ConformalStatus.AMBIGUOUS
            is_ambiguous = True
        else:
            status = ConformalStatus.EMPTY
            is_ambiguous = True

        return ConformalEvaluationResult(
            prediction_set=prediction_set,
            status=status,
            is_ambiguous=is_ambiguous,
            fraud_probability=p,
            quantile=q,
            nonconformity_scores=scores,
        )
