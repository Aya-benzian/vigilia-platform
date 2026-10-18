"""
select_model.py — Pre-committed model selection rule for Member 1 (§7 of design v5).

=============================================================================
IMPORTANT: This rule is committed to Git BEFORE any experiments are run.
The final model is chosen by this rule only — not by hand after seeing results.
=============================================================================

Selection rule (verbatim from design v5 §7):

1. PRIMARY METRIC
   Mean PR-AUC (average_precision_score) over all repeated CV folds.

2. TIED SET
   The best model (highest mean PR-AUC) plus every model that is NOT
   significantly worse after Holm correction (Holm-adjusted p >= ALPHA=0.05).
   Optionally: also require the mean difference to be above PRACTICAL_MARGIN=0.005.
   A model is only excluded from the tied set if it is both statistically AND
   practically inferior.

3. TIEBREAKER (inside the tied set, in order)
   a. Lowest Brier score AFTER calibration (most reliable probabilities)
   b. Lowest p95 inference time in milliseconds (full Pipeline, single row)
   c. Explainability: tree-based model preferred (XGBoost > RF > others)
      because Member 2 uses SHAP on the base model

4. SENSITIVITY ANALYSIS
   If StratifiedGroupKFold (grouped by cc_num) gives a DIFFERENT winner:
   - Report both winners.
   - Choose the model that is in the tied set of BOTH protocols.
   - If no such model exists, escalate to team discussion and document the decision.

5. GUARANTEE
   This function returns a string (model name) deterministically given the
   same input DataFrames. The same inputs always produce the same output.
"""

import logging
from typing import Any, Dict, List, Optional, Tuple

import numpy as np
import pandas as pd

from config import ALPHA, PRACTICAL_MARGIN, TIEBREAKER_ORDER

logger = logging.getLogger(__name__)

# Models ranked by explainability preference for Member 2 (SHAP)
EXPLAINABILITY_RANK: Dict[str, int] = {
    "xgboost": 1,   # best — tree SHAP, exact and fast
    "rf":       2,   # tree SHAP, approximate
    "logreg":   3,   # linear model, coefficients interpretable
    "svm":      4,   # limited explainability
    "mlp":      5,   # least interpretable
}


def compute_mean_pr_auc(
    cv_scores: Any,
    cv_type: str = "stratified",
) -> pd.Series:
    """
    Compute mean PR-AUC per model. Supports DataFrame or dict of arrays/scores.
    """
    if isinstance(cv_scores, dict):
        res = {}
        for m, v in cv_scores.items():
            if isinstance(v, dict) and "mean" in v:
                res[m] = float(v["mean"])
            else:
                res[m] = float(np.mean(v))
        return pd.Series(res).sort_values(ascending=False)

    if isinstance(cv_scores, pd.DataFrame):
        if "cv_type" in cv_scores.columns:
            filtered = cv_scores[cv_scores["cv_type"] == cv_type]
            return filtered.groupby("model")["pr_auc"].mean().sort_values(ascending=False)
        return cv_scores.mean().sort_values(ascending=False)

    raise ValueError(f"Unsupported cv_scores format: {type(cv_scores)}")


def build_tied_set(
    mean_scores: pd.Series,
    wilcoxon_holm_df: Optional[pd.DataFrame] = None,
    use_practical_margin: bool = True,
) -> List[str]:
    """
    Build the tied set: best model + all models not significantly worse.
    """
    best_model = mean_scores.index[0]
    tied_set = [best_model]

    if wilcoxon_holm_df is None or wilcoxon_holm_df.empty:
        return tied_set

    for model in mean_scores.index[1:]:
        p_holm = None
        mean_diff = float(abs(mean_scores[best_model] - mean_scores[model]))

        # Case 1: Square matrix format (index and columns have model names)
        if model in wilcoxon_holm_df.index and best_model in wilcoxon_holm_df.columns:
            p_holm = float(wilcoxon_holm_df.loc[model, best_model])

        # Case 2: Long format table
        elif "model_a" in wilcoxon_holm_df.columns and "model_b" in wilcoxon_holm_df.columns:
            row = wilcoxon_holm_df[
                (
                    (wilcoxon_holm_df["model_a"] == best_model) &
                    (wilcoxon_holm_df["model_b"] == model)
                ) | (
                    (wilcoxon_holm_df["model_a"] == model) &
                    (wilcoxon_holm_df["model_b"] == best_model)
                )
            ]
            if not row.empty:
                p_holm = float(row["p_value_holm"].iloc[0])
                mean_diff = float(abs(row.get("mean_diff", pd.Series([mean_diff])).iloc[0]))

        if p_holm is None:
            logger.warning(
                f"No Wilcoxon/Holm result found for ({best_model}, {model}). "
                f"Including {model} in tied set (conservative)."
            )
            tied_set.append(model)
            continue

        is_statistically_worse = p_holm < ALPHA
        is_practically_worse = (
            use_practical_margin and mean_diff > PRACTICAL_MARGIN
        )

        if is_statistically_worse and is_practically_worse:
            logger.info(
                f"{model} excluded from tied set: "
                f"Holm p={p_holm:.4e} < {ALPHA}, diff={mean_diff:.4f} > {PRACTICAL_MARGIN}"
            )
        else:
            tied_set.append(model)
            logger.info(
                f"{model} included in tied set: "
                f"Holm p={p_holm:.4e}, diff={mean_diff:.4f}"
            )

    return tied_set


def apply_tiebreaker(
    tied_set: List[str],
    brier_scores: Dict[str, float],
    inference_p95_ms: Dict[str, float],
) -> str:
    """
    Apply the three-step tiebreaker inside the tied set.

    Step a: Lowest Brier score after calibration.
    Step b: Lowest p95 inference time (ms), full Pipeline, single-row.
    Step c: Explainability preference (EXPLAINABILITY_RANK).

    In practice, step (a) almost always resolves the tie.
    Steps (b) and (c) are fallbacks for near-identical Brier scores.

    Args:
        tied_set: List of model names in the tied set.
        brier_scores: Dict mapping model name → Brier score after calibration.
                      Evaluated on fraudTest (one-time evaluation).
        inference_p95_ms: Dict mapping model name → p95 single-row latency (ms).

    Returns:
        Name of the selected model.
    """
    if len(tied_set) == 1:
        selected = tied_set[0]
        logger.info(f"Tied set has one model: {selected} selected.")
        return selected

    # Build a ranking DataFrame
    ranking = pd.DataFrame({
        "model": tied_set,
        "brier_calibrated": [brier_scores.get(m, float("inf")) for m in tied_set],
        "inference_p95_ms": [inference_p95_ms.get(m, float("inf")) for m in tied_set],
        "explainability_rank": [EXPLAINABILITY_RANK.get(m, 99) for m in tied_set],
    })

    # Sort by tiebreaker order (all ascending — lower is better)
    ranking = ranking.sort_values(
        by=["brier_calibrated", "inference_p95_ms", "explainability_rank"],
        ascending=True,
    ).reset_index(drop=True)

    selected = ranking.loc[0, "model"]
    logger.info(
        f"Tiebreaker result:\n{ranking.to_string(index=False)}\n"
        f"→ Selected: {selected}"
    )
    return selected


def select_model(
    cv_scores: Any,
    wilcoxon_holm_df: Optional[pd.DataFrame] = None,
    brier_scores: Optional[Dict[str, float]] = None,
    inference_p95_ms: Optional[Dict[str, float]] = None,
    cv_type: str = "stratified",
) -> Tuple[str, List[str], Dict]:
    if brier_scores is None:
        brier_scores = {}
    if inference_p95_ms is None:
        inference_p95_ms = {}
    if wilcoxon_holm_df is None:
        from config import REPORTS_DIR
        ph_path = REPORTS_DIR / "tables" / "wilcoxon_holm_pvalues.csv"
        if ph_path.exists():
            wilcoxon_holm_df = pd.read_csv(ph_path, index_col=0)
        else:
            wilcoxon_holm_df = pd.DataFrame()

    logger.info(f"=== Applying selection rule (cv_type={cv_type}) ===")

    # Step 1: Primary metric ranking
    mean_scores = compute_mean_pr_auc(cv_scores, cv_type=cv_type)
    logger.info(f"Mean PR-AUC ranking:\n{mean_scores.to_string()}")

    # Step 2: Build tied set
    tied_set = build_tied_set(mean_scores, wilcoxon_holm_df)
    logger.info(f"Tied set: {tied_set}")

    # Step 3: Tiebreaker
    selected_model = apply_tiebreaker(tied_set, brier_scores, inference_p95_ms)
    logger.info(f"=== Selected model: {selected_model} ===")

    # Build report dict for model_card.json
    report = {
        "rule_version": "v5",
        "rule_commit": "committed before experiments",
        "cv_type": cv_type,
        "primary_metric": "pr_auc",
        "alpha": ALPHA,
        "practical_margin": PRACTICAL_MARGIN,
        "best_model_by_metric": mean_scores.index[0],
        "mean_pr_auc": mean_scores.to_dict(),
        "tied_set": tied_set,
        "selected_model": selected_model,
        "tiebreaker_order": TIEBREAKER_ORDER,
        "brier_scores_calibrated": brier_scores,
        "inference_p95_ms": inference_p95_ms,
    }

    return selected_model, tied_set, report


def check_sensitivity_agreement(
    selected_stratified: str,
    selected_grouped: str,
    tied_set_stratified: List[str],
    tied_set_grouped: List[str],
) -> Tuple[str, str]:
    """
    Check whether the stratified and grouped CV protocols agree on the winner.

    If they agree: return (selected_stratified, "agreement").
    If they disagree: find a model in both tied sets and return it.
    If no intersection: return (selected_stratified, "conflict — escalate to team").

    This result is always reported in the final model card.

    Args:
        selected_stratified: Winner from stratified CV protocol.
        selected_grouped: Winner from grouped CV protocol.
        tied_set_stratified: Tied set from stratified CV.
        tied_set_grouped: Tied set from grouped CV.

    Returns:
        Tuple (final_model, status_message).
    """
    if selected_stratified == selected_grouped:
        logger.info(f"Both protocols agree: {selected_stratified}")
        return selected_stratified, "agreement"

    logger.warning(
        f"Protocol disagreement: stratified={selected_stratified}, "
        f"grouped={selected_grouped}"
    )

    # Find models in both tied sets
    intersection = [
        m for m in tied_set_stratified if m in tied_set_grouped
    ]

    if intersection:
        # Pick the one with best stratified mean score (already in ranked order)
        final_model = intersection[0]
        status = (
            f"disagreement — {final_model} chosen (in tied set of both protocols)"
        )
        logger.info(f"Resolution: {status}")
        return final_model, status
    else:
        status = (
            f"conflict — no model in both tied sets. "
            f"Escalate to team. Using stratified winner ({selected_stratified}) as default."
        )
        logger.error(status)
        return selected_stratified, status
