"""
test_select_model.py — Unit tests for select_model.py (Member 1, §14 of design v5).

Tests the pre-committed selection rule in isolation using synthetic score data.
No real model training is needed — only the logic of the selection rule is tested.

Tests:
    1. Clear winner (one model dominates all) → selected correctly.
    2. All models tied → best by Brier tiebreaker selected.
    3. Practical margin exclusion works correctly.
    4. Sensitivity agreement function returns correct status.
    5. Explainability tiebreaker: xgboost preferred over rf when Brier is equal.

Run with: pytest tests/test_select_model.py -v
"""

import pandas as pd
import pytest

from src.select_model import (
    compute_mean_pr_auc,
    build_tied_set,
    apply_tiebreaker,
    check_sensitivity_agreement,
    EXPLAINABILITY_RANK,
)


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _make_cv_scores(model_means: dict, n_folds: int = 50) -> pd.DataFrame:
    """
    Create synthetic cv_scores long DataFrame with given per-model mean PR-AUC.
    Each model gets n_folds scores uniformly distributed around its mean.
    """
    rows = []
    for model, mean in model_means.items():
        for i in range(n_folds):
            rows.append({"model": model, "cv_type": "stratified", "pr_auc": mean})
    return pd.DataFrame(rows)


def _make_holm_df(
    models: list,
    best: str,
    reject_worse: list,
    mean_diffs: dict,
) -> pd.DataFrame:
    """
    Create synthetic Holm result DataFrame.
    reject_worse: models that are statistically worse than best.
    """
    rows = []
    for model in models:
        if model == best:
            continue
        rows.append({
            "model_a": best,
            "model_b": model,
            "p_value_holm": 0.01 if model in reject_worse else 0.50,
            "reject": model in reject_worse,
            "mean_diff": mean_diffs.get(model, 0.001),
        })
    return pd.DataFrame(rows)


# ---------------------------------------------------------------------------
# Test 1 — Clear winner
# ---------------------------------------------------------------------------

def test_clear_winner_selected():
    """When one model is clearly best and all others are Holm-rejected."""
    models = ["logreg", "rf", "xgboost", "svm", "mlp"]
    means = {"logreg": 0.70, "rf": 0.72, "xgboost": 0.80, "svm": 0.65, "mlp": 0.68}
    cv_scores = _make_cv_scores(means)
    holm_df = _make_holm_df(
        models, best="xgboost",
        reject_worse=["logreg", "rf", "svm", "mlp"],
        mean_diffs={m: 0.10 for m in ["logreg", "rf", "svm", "mlp"]},
    )

    mean_scores = compute_mean_pr_auc(cv_scores)
    tied_set = build_tied_set(mean_scores, holm_df)
    assert tied_set == ["xgboost"], f"Expected ['xgboost'], got {tied_set}"

    selected = apply_tiebreaker(
        tied_set,
        brier_scores={"xgboost": 0.05},
        inference_p95_ms={"xgboost": 10.0},
    )
    assert selected == "xgboost"


# ---------------------------------------------------------------------------
# Test 2 — All models tied → Brier tiebreaker
# ---------------------------------------------------------------------------

def test_brier_tiebreaker_applied():
    """When all models are in the tied set, lowest Brier wins."""
    models = ["logreg", "rf", "xgboost", "svm", "mlp"]
    means = {m: 0.75 for m in models}
    cv_scores = _make_cv_scores(means)
    holm_df = _make_holm_df(
        models, best="logreg",
        reject_worse=[],  # no model rejected — all tied
        mean_diffs={m: 0.0 for m in models},
    )

    mean_scores = compute_mean_pr_auc(cv_scores)
    tied_set = build_tied_set(mean_scores, holm_df)
    assert set(tied_set) == set(models)

    brier = {"logreg": 0.10, "rf": 0.08, "xgboost": 0.06, "svm": 0.12, "mlp": 0.09}
    selected = apply_tiebreaker(tied_set, brier_scores=brier, inference_p95_ms={m: 5.0 for m in models})
    assert selected == "xgboost", f"Expected 'xgboost' (lowest Brier), got {selected}"


# ---------------------------------------------------------------------------
# Test 3 — Practical margin exclusion
# ---------------------------------------------------------------------------

def test_practical_margin_excludes_model():
    """
    A model is excluded from the tied set only if BOTH:
        p_holm < ALPHA (statistically worse)
        mean_diff > PRACTICAL_MARGIN (practically worse)
    """
    models = ["logreg", "rf", "xgboost"]
    means = {"xgboost": 0.80, "rf": 0.74, "logreg": 0.70}
    cv_scores = _make_cv_scores(means)

    holm_df = _make_holm_df(
        models, best="xgboost",
        reject_worse=["logreg"],  # logreg is statistically worse
        mean_diffs={"rf": 0.001, "logreg": 0.10},  # rf below margin, logreg above
    )

    mean_scores = compute_mean_pr_auc(cv_scores)
    tied_set = build_tied_set(mean_scores, holm_df, use_practical_margin=True)

    # logreg excluded (statistically AND practically worse)
    assert "logreg" not in tied_set
    # rf included (statistically not worse OR not practically worse)
    assert "rf" in tied_set
    assert "xgboost" in tied_set


# ---------------------------------------------------------------------------
# Test 4 — Sensitivity agreement
# ---------------------------------------------------------------------------

def test_sensitivity_agreement():
    selected, status = check_sensitivity_agreement(
        selected_stratified="xgboost",
        selected_grouped="xgboost",
        tied_set_stratified=["xgboost", "rf"],
        tied_set_grouped=["xgboost"],
    )
    assert selected == "xgboost"
    assert "agreement" in status


def test_sensitivity_disagreement_resolved():
    selected, status = check_sensitivity_agreement(
        selected_stratified="xgboost",
        selected_grouped="rf",
        tied_set_stratified=["xgboost", "rf"],
        tied_set_grouped=["rf", "xgboost"],
    )
    # Both are in both tied sets — pick xgboost (first in stratified tied set)
    assert selected in {"xgboost", "rf"}
    assert "disagreement" in status


def test_sensitivity_conflict_escalated():
    selected, status = check_sensitivity_agreement(
        selected_stratified="xgboost",
        selected_grouped="svm",
        tied_set_stratified=["xgboost"],  # rf not in stratified tied set
        tied_set_grouped=["svm"],         # xgboost not in grouped tied set
    )
    assert selected == "xgboost"  # fallback to stratified winner
    assert "conflict" in status


# ---------------------------------------------------------------------------
# Test 5 — Explainability tiebreaker
# ---------------------------------------------------------------------------

def test_explainability_tiebreaker():
    """When Brier and inference time are identical, xgboost beats rf."""
    tied_set = ["rf", "xgboost"]
    brier = {"rf": 0.07, "xgboost": 0.07}
    timing = {"rf": 5.0, "xgboost": 5.0}

    selected = apply_tiebreaker(tied_set, brier_scores=brier, inference_p95_ms=timing)
    assert selected == "xgboost", (
        f"Expected 'xgboost' (higher explainability rank), got {selected}"
    )
