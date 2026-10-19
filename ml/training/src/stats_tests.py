"""
stats_tests.py — Statistical benchmarking for Member 1 (§8 of design v5).

Steps:
1. Friedman test (non-parametric omnibus) on repeated CV scores.
2. Post-hoc Wilcoxon pairwise tests (Holm correction).
3. Effect sizes (Cohen's d for each pair vs. best model).
4. 95% CI per model (bootstrap or t-based).
5. Save figures: CD diagram proxy, CI bar chart.

All results are saved to reports/tables/ and logged to MLflow.
"""

import logging
from pathlib import Path
from typing import Dict, List

import mlflow
import numpy as np
import pandas as pd
import os
os.environ.setdefault("MPLCONFIGDIR", "/tmp/matplotlib")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
from scipy import stats
from scipy.stats import wilcoxon, friedmanchisquare
import scikit_posthocs as sp

from config import ALPHA, ARTIFACTS_DIR, REPORTS_DIR

logger = logging.getLogger(__name__)

FIGURES_DIR = REPORTS_DIR / "figures"
TABLES_DIR  = REPORTS_DIR / "tables"


# ==============================================================================
# Helpers
# ==============================================================================

def _ensure_dirs():
    FIGURES_DIR.mkdir(parents=True, exist_ok=True)
    TABLES_DIR.mkdir(parents=True, exist_ok=True)


def _cohen_d(a: np.ndarray, b: np.ndarray) -> float:
    """Cohen's d between two 1-D arrays."""
    pooled_std = np.sqrt((a.std(ddof=1) ** 2 + b.std(ddof=1) ** 2) / 2)
    if pooled_std < 1e-12:
        return 0.0
    return float((a.mean() - b.mean()) / pooled_std)


def _bootstrap_ci(
    scores: np.ndarray,
    n_boot: int = 2000,
    alpha: float = 0.05,
    seed: int = 42,
) -> tuple[float, float]:
    """
    Bootstrap 95% CI for the mean of `scores`.
    Returns (lower, upper).
    """
    rng   = np.random.default_rng(seed)
    means = [rng.choice(scores, size=len(scores), replace=True).mean()
             for _ in range(n_boot)]
    lo = float(np.percentile(means, 100 * alpha / 2))
    hi = float(np.percentile(means, 100 * (1 - alpha / 2)))
    return lo, hi


# ==============================================================================
# Friedman test
# ==============================================================================

def run_friedman(results: Dict[str, np.ndarray]) -> dict:
    """
    Friedman test: H0 = all models have the same distribution of PR-AUC scores.

    Requires all score arrays to have the same length (guaranteed because they
    use identical folds from cv_compare.py).

    Args:
        results: Dict model_name -> scores ndarray (all same length).

    Returns:
        Dict with keys: statistic, p_value, significant.
    """
    model_names = list(results.keys())
    score_arrays = [results[m] for m in model_names]

    stat, p = friedmanchisquare(*score_arrays)
    significant = p < ALPHA

    out = dict(statistic=float(stat), p_value=float(p), significant=significant)
    logger.info(
        f"Friedman test: chi2={stat:.4f}, p={p:.4e}, "
        f"{'SIGNIFICANT' if significant else 'not significant'} at alpha={ALPHA}"
    )
    return out


# ==============================================================================
# Wilcoxon pairwise + Holm correction
# ==============================================================================

def run_wilcoxon_holm(
    results: Dict[str, np.ndarray],
) -> pd.DataFrame:
    """
    Pairwise Wilcoxon signed-rank tests with Holm correction.

    Uses scikit-posthocs which already applies Holm.

    Args:
        results: Dict model_name -> scores ndarray.

    Returns:
        DataFrame of adjusted p-values (models × models).
    """
    names = list(results.keys())
    arrays = [np.asarray(results[name]) for name in names]
    p_matrix = sp.posthoc_wilcoxon(arrays, p_adjust="holm")
    p_matrix.index = names
    p_matrix.columns = names
    return p_matrix


# ==============================================================================
# Confidence intervals + effect sizes
# ==============================================================================

def compute_ci_and_effect_sizes(
    results: Dict[str, np.ndarray],
    best_model: str,
) -> pd.DataFrame:
    """
    For each model compute:
        - mean PR-AUC
        - 95% bootstrap CI (lo, hi)
        - Cohen's d vs. best_model

    Args:
        results: Dict model_name -> scores ndarray.
        best_model: Name of the best model (e.g. "xgboost").

    Returns:
        DataFrame with columns: model, mean, ci_lo, ci_hi, cohen_d.
    """
    rows = []
    best_scores = results[best_model]

    for name, scores in results.items():
        mean       = float(scores.mean())
        ci_lo, ci_hi = _bootstrap_ci(scores)
        d          = _cohen_d(scores, best_scores)
        rows.append(dict(
            model=name,
            mean=mean,
            ci_lo=ci_lo,
            ci_hi=ci_hi,
            cohen_d=d,
        ))

    df = pd.DataFrame(rows).sort_values("mean", ascending=False).reset_index(drop=True)
    return df


# ==============================================================================
# Figures
# ==============================================================================

def plot_ci_barchart(
    ci_df: pd.DataFrame,
    title: str = "PR-AUC 95% CI per Model",
    filename: str = "ci_barchart.png",
) -> Path:
    """
    Bar chart with 95% CI error bars for each model.

    Args:
        ci_df: DataFrame from compute_ci_and_effect_sizes.
        title: Plot title.
        filename: Output filename in reports/figures/.

    Returns:
        Path to saved figure.
    """
    _ensure_dirs()

    fig, ax = plt.subplots(figsize=(8, 5))
    models = ci_df["model"].tolist()
    means  = ci_df["mean"].tolist()
    lowers = [m - lo for m, lo in zip(means, ci_df["ci_lo"])]
    uppers = [hi - m  for m, hi in zip(means, ci_df["ci_hi"])]

    colors = ["#2ecc71" if i == 0 else "#3498db" for i in range(len(models))]
    bars   = ax.barh(models[::-1], means[::-1], xerr=[lowers[::-1], uppers[::-1]],
                     color=colors[::-1], capsize=5, alpha=0.85)

    ax.set_xlabel("PR-AUC (average precision)", fontsize=11)
    ax.set_title(title, fontsize=13, fontweight="bold")
    ax.xaxis.set_major_formatter(mticker.FormatStrFormatter("%.3f"))
    ax.grid(axis="x", linestyle="--", alpha=0.5)
    plt.tight_layout()

    out = FIGURES_DIR / filename
    fig.savefig(out, dpi=150)
    plt.close(fig)
    logger.info(f"CI barchart saved to {out}")
    return out


def plot_score_distributions(
    results: Dict[str, np.ndarray],
    filename: str = "cv_distributions.png",
) -> Path:
    """
    Boxplot of CV score distributions for all models.

    Args:
        results: Dict model_name -> scores ndarray.
        filename: Output filename.

    Returns:
        Path to saved figure.
    """
    _ensure_dirs()

    df = pd.DataFrame(results)
    ordered = df.mean().sort_values(ascending=False).index.tolist()
    df = df[ordered]

    fig, ax = plt.subplots(figsize=(10, 5))
    df.boxplot(ax=ax, vert=False, patch_artist=True)
    ax.set_xlabel("PR-AUC (average precision)", fontsize=11)
    ax.set_title("CV Score Distributions per Model", fontsize=13, fontweight="bold")
    ax.grid(axis="x", linestyle="--", alpha=0.5)
    plt.tight_layout()

    out = FIGURES_DIR / filename
    fig.savefig(out, dpi=150)
    plt.close(fig)
    logger.info(f"Score distributions saved to {out}")
    return out


# ==============================================================================
# Full stats pipeline
# ==============================================================================

def run_stats_pipeline(
    results: Dict[str, np.ndarray],
    best_model: str,
    mlflow_run_name: str = "stats_tests",
) -> dict:
    """
    Run the full statistical testing pipeline and log to MLflow.

    Steps:
        1. Friedman test
        2. Wilcoxon pairwise + Holm correction
        3. CIs + effect sizes
        4. Figures saved to reports/figures/

    Args:
        results: Dict model_name -> scores ndarray from cv_compare.
        best_model: Name of the champion candidate (highest mean PR-AUC).
        mlflow_run_name: Name for the MLflow run.

    Returns:
        Dict with keys: friedman, wilcoxon_holm, ci_table, figure_paths.
    """
    _ensure_dirs()

    friedman  = run_friedman(results)
    p_matrix  = run_wilcoxon_holm(results)
    ci_df     = compute_ci_and_effect_sizes(results, best_model)

    # Save tables
    ci_path = TABLES_DIR / "ci_effect_sizes.csv"
    ci_df.to_csv(ci_path, index=False)

    ph_path = TABLES_DIR / "wilcoxon_holm_pvalues.csv"
    p_matrix.to_csv(ph_path)

    # Save figures
    ci_fig_path   = plot_ci_barchart(ci_df)
    dist_fig_path = plot_score_distributions(results)

    with mlflow.start_run(run_name=mlflow_run_name):
        mlflow.log_metric("friedman_statistic", friedman["statistic"])
        mlflow.log_metric("friedman_p_value",   friedman["p_value"])
        mlflow.log_param("friedman_significant", str(friedman["significant"]))
        mlflow.log_param("alpha", ALPHA)
        mlflow.log_param("best_model_candidate", best_model)

        for _, row in ci_df.iterrows():
            mlflow.log_metric(f"{row['model']}_mean_pr_auc", row["mean"])
            mlflow.log_metric(f"{row['model']}_ci_lo",       row["ci_lo"])
            mlflow.log_metric(f"{row['model']}_ci_hi",       row["ci_hi"])
            mlflow.log_metric(f"{row['model']}_cohen_d",     row["cohen_d"])

        mlflow.log_artifact(str(ci_path))
        mlflow.log_artifact(str(ph_path))
        mlflow.log_artifact(str(ci_fig_path))
        mlflow.log_artifact(str(dist_fig_path))

    logger.info("Statistical testing complete. Results logged to MLflow.")

    return dict(
        friedman=friedman,
        wilcoxon_holm=p_matrix,
        ci_table=ci_df,
        figure_paths=[ci_fig_path, dist_fig_path],
    )
