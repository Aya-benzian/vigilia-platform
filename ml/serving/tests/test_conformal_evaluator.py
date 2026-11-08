"""
Unit tests for the Split Conformal Prediction Evaluator (ml/serving/conformal_evaluator.py).

Validates:
    - All mathematical cases specified in Section 7 of the design document.
    - Boundary behavior at exact threshold crossings.
    - Strict input validation for fraud probabilities.
    - Strict input validation for quantiles (including +inf, rejecting -inf, NaN, invalid types).
    - Deterministic ordering of prediction sets (LEGITIMATE first, then FRAUD).
    - Independence of evaluator instances.
"""

from __future__ import annotations

import math
from typing import Any, List

import pytest

from ml.serving.conformal_evaluator import (
    ConformalEvaluationResult,
    ConformalEvaluator,
    ConformalStatus,
)


@pytest.fixture
def evaluator() -> ConformalEvaluator:
    """Provides a fresh, stateless ConformalEvaluator instance."""
    return ConformalEvaluator()


# =====================================================================
# 1. Required Mathematical Test Matrix (Section 7)
# =====================================================================

@pytest.mark.parametrize(
    "quantile,fraud_probability,expected_set,expected_status,expected_ambiguous",
    [
        (0.54, 0.10, ["LEGITIMATE"], ConformalStatus.SINGLETON, False),
        (0.54, 0.90, ["FRAUD"], ConformalStatus.SINGLETON, False),
        (0.54, 0.50, ["LEGITIMATE", "FRAUD"], ConformalStatus.AMBIGUOUS, True),
        (0.42, 0.50, [], ConformalStatus.EMPTY, True),
        (0.00, 0.00, ["LEGITIMATE"], ConformalStatus.SINGLETON, False),
        (0.00, 1.00, ["FRAUD"], ConformalStatus.SINGLETON, False),
        (0.00, 0.50, [], ConformalStatus.EMPTY, True),
        (1.00, 0.50, ["LEGITIMATE", "FRAUD"], ConformalStatus.AMBIGUOUS, True),
        (float("inf"), 0.50, ["LEGITIMATE", "FRAUD"], ConformalStatus.AMBIGUOUS, True),
    ],
)
def test_required_mathematical_cases(
    evaluator: ConformalEvaluator,
    quantile: float,
    fraud_probability: float,
    expected_set: List[str],
    expected_status: ConformalStatus,
    expected_ambiguous: bool,
) -> None:
    """Verifies all mandatory test cases from the specification matrix."""
    result = evaluator.evaluate(fraud_probability=fraud_probability, quantile=quantile)

    assert result.prediction_set == expected_set
    assert result.status == expected_status
    assert result.is_ambiguous is expected_ambiguous
    assert math.isclose(result.fraud_probability, fraud_probability) if not math.isinf(fraud_probability) else True
    if math.isinf(quantile):
        assert math.isinf(result.quantile) and result.quantile > 0
    else:
        assert math.isclose(result.quantile, quantile)


# =====================================================================
# 2. Boundary and Edge Cases
# =====================================================================

def test_extreme_probabilities_with_infinite_quantile(evaluator: ConformalEvaluator) -> None:
    """With q = +inf, all inputs yield [LEGITIMATE, FRAUD] regardless of p."""
    for p in (0.0, 0.01, 0.5, 0.99, 1.0):
        result = evaluator.evaluate(fraud_probability=p, quantile=float("inf"))
        assert result.prediction_set == ["LEGITIMATE", "FRAUD"]
        assert result.status == ConformalStatus.AMBIGUOUS
        assert result.is_ambiguous is True


def test_exact_threshold_boundary_legitimate(evaluator: ConformalEvaluator) -> None:
    """
    When s(LEGITIMATE) == p == q, LEGITIMATE must be included.
    When p = 0.54, q = 0.54:
        s_legit = 0.54 <= 0.54 -> Included
        s_fraud = 0.46 <= 0.54 -> Included
    """
    result = evaluator.evaluate(fraud_probability=0.54, quantile=0.54)
    assert result.prediction_set == ["LEGITIMATE", "FRAUD"]
    assert result.status == ConformalStatus.AMBIGUOUS


def test_exact_threshold_boundary_fraud(evaluator: ConformalEvaluator) -> None:
    """
    When s(FRAUD) == 1 - p == q, FRAUD must be included.
    When p = 0.46, q = 0.54:
        s_legit = 0.46 <= 0.54 -> Included
        s_fraud = 0.54 <= 0.54 -> Included
    """
    result = evaluator.evaluate(fraud_probability=0.46, quantile=0.54)
    assert result.prediction_set == ["LEGITIMATE", "FRAUD"]
    assert result.status == ConformalStatus.AMBIGUOUS


def test_floating_point_imprecision_boundary(evaluator: ConformalEvaluator) -> None:
    """
    Tests floating-point edge case where 1.0 - 0.58 = 0.42000000000000004.
    Mathematically 1.0 - 0.58 == 0.42, so with q = 0.42, FRAUD must be included.
    """
    result = evaluator.evaluate(fraud_probability=0.58, quantile=0.42)
    # s_legit = 0.58 > 0.42 (excluded)
    # s_fraud = 0.42000000000000004 ~ 0.42 (included)
    assert result.prediction_set == ["FRAUD"]
    assert result.status == ConformalStatus.SINGLETON
    assert result.is_ambiguous is False


def test_deterministic_ordering(evaluator: ConformalEvaluator) -> None:
    """Guarantees that prediction sets always order LEGITIMATE before FRAUD."""
    # AMBIGUOUS case
    result = evaluator.evaluate(fraud_probability=0.50, quantile=0.60)
    assert result.prediction_set == ["LEGITIMATE", "FRAUD"]
    assert result.prediction_set != ["FRAUD", "LEGITIMATE"]


def test_result_structure_and_scores(evaluator: ConformalEvaluator) -> None:
    """Verifies all fields of ConformalEvaluationResult."""
    result = evaluator.evaluate(fraud_probability=0.30, quantile=0.50)
    assert isinstance(result, ConformalEvaluationResult)
    assert result.fraud_probability == 0.30
    assert result.quantile == 0.50
    assert result.nonconformity_scores["LEGITIMATE"] == 0.30
    assert math.isclose(result.nonconformity_scores["FRAUD"], 0.70)
    assert result.prediction_set == ["LEGITIMATE"]
    assert result.status == ConformalStatus.SINGLETON
    assert result.is_ambiguous is False


# =====================================================================
# 3. Fraud Probability Input Validation Tests
# =====================================================================

@pytest.mark.parametrize(
    "invalid_p,expected_error",
    [
        (-0.0001, ValueError),
        (-1.0, ValueError),
        (1.0001, ValueError),
        (2.0, ValueError),
        (float("nan"), ValueError),
        (float("inf"), ValueError),
        (float("-inf"), ValueError),
        (True, TypeError),          # Booleans must be explicitly rejected
        (False, TypeError),
        ("0.5", TypeError),
        (None, TypeError),
        ([0.5], TypeError),
        ({"p": 0.5}, TypeError),
        (complex(0.5, 0.0), TypeError),
    ],
)
def test_fraud_probability_rejections(
    evaluator: ConformalEvaluator,
    invalid_p: Any,
    expected_error: type[Exception],
) -> None:
    """Verifies that invalid, infinite, NaN, or non-numeric fraud probabilities are rejected."""
    with pytest.raises(expected_error):
        evaluator.evaluate(fraud_probability=invalid_p, quantile=0.50)


# =====================================================================
# 4. Quantile Input Validation Tests
# =====================================================================

@pytest.mark.parametrize(
    "invalid_q,expected_error",
    [
        (-0.0001, ValueError),
        (-1.0, ValueError),
        (1.0001, ValueError),
        (2.0, ValueError),
        (float("-inf"), ValueError),  # Negative infinity is rejected
        (float("nan"), ValueError),
        (True, TypeError),           # Booleans must be rejected
        (False, TypeError),
        ("0.5", TypeError),
        ("inf", TypeError),
        (None, TypeError),
        ([0.5], TypeError),
        ({"q": 0.5}, TypeError),
    ],
)
def test_quantile_rejections(
    evaluator: ConformalEvaluator,
    invalid_q: Any,
    expected_error: type[Exception],
) -> None:
    """Verifies that invalid, negative, out-of-range, NaN, or non-numeric quantiles are rejected."""
    # Test validator directly
    with pytest.raises(expected_error):
        evaluator.validate_quantile(invalid_q)

    # Test evaluate() rejection
    if invalid_q is not None:
        with pytest.raises(expected_error):
            evaluator.evaluate(fraud_probability=0.50, quantile=invalid_q)


def test_missing_quantile_error(evaluator: ConformalEvaluator) -> None:
    """Calling evaluate() without a quantile when no default was set raises ValueError."""
    with pytest.raises(ValueError, match="Conformal quantile must be provided"):
        evaluator.evaluate(fraud_probability=0.50)


# =====================================================================
# 5. Independent Evaluator Instances and Stateful Defaults
# =====================================================================

def test_independent_evaluator_instances() -> None:
    """Verifies that independent evaluator instances maintain isolated configurations."""
    eval_purchase = ConformalEvaluator(default_quantile=0.54)
    eval_transfer = ConformalEvaluator(default_quantile=0.42)

    assert eval_purchase.default_quantile == 0.54
    assert eval_transfer.default_quantile == 0.42

    # Using default quantiles
    res_purchase = eval_purchase.evaluate(fraud_probability=0.50)
    res_transfer = eval_transfer.evaluate(fraud_probability=0.50)

    # For p = 0.50:
    # Purchase (q=0.54) -> AMBIGUOUS [LEGITIMATE, FRAUD]
    # Transfer (q=0.42) -> EMPTY []
    assert res_purchase.status == ConformalStatus.AMBIGUOUS
    assert res_purchase.prediction_set == ["LEGITIMATE", "FRAUD"]

    assert res_transfer.status == ConformalStatus.EMPTY
    assert res_transfer.prediction_set == []


def test_override_default_quantile() -> None:
    """Explicit quantile passed to evaluate() overrides the instance default."""
    evaluator = ConformalEvaluator(default_quantile=0.54)

    # Override with q = 0.42
    result = evaluator.evaluate(fraud_probability=0.50, quantile=0.42)
    assert result.quantile == 0.42
    assert result.status == ConformalStatus.EMPTY
    assert result.prediction_set == []

    # Original default quantile remains untouched
    assert evaluator.default_quantile == 0.54
