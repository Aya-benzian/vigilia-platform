"""
stats_tests.py — Statistical validation for Member 1 (§9 of design v5).

Inputs: cv_scores_long.parquet from cv_compare.py
Outputs:
    tables/friedman.csv
    tables/wilcoxon_holm.csv
    figures/cv_boxplot.png
    figures/ranking_plot.png
    figures/pvalue_heatmap.png

Protocol:
1. Friedman test across all 5 models (scipy.stats.friedmanchisquare).
2. If Friedman is significant: 10 pairwise Wilcoxon signed-rank tests.
3. Holm correction on the 10 p-values (statsmodels multipletests method="holm").
4. 95% bootstrap CIs over fold scores for each model.
5. Effect sizes: mean paired difference + rank-biserial correlation (or Cliff's delta).
6. [NICE] Critical difference diagram via scikit-posthocs (Nemenyi).

Caveat (report this):
    CV fold scores are not independent (shared training data), so p-values
    are somewhat optimistic. This is a known limitation of CV-based testing.

All outputs are logged to MLflow as artifacts.
"""

import logging
from pathlib import Path
from typing import Dict, List, Tuple

import numpy as np
import pandas as pd
from scipy import stats
from statsmodels.stats.multitest import multipletests

from config import ALPHA, TABLES_DIR, FIGURES_DIR, MLFLOW_EXP_COMPARE

logger = logging.getLogger(__name__)


def load_cv_scores(cv_type: str = "stratified") -> pd.DataFrame:
    """
    Load cv_scores_long.parquet and filter to one cv_type.

    Args:
        cv_type: "stratified" or "grouped".

    Returns:
        Wide-format DataFrame: rows=folds, columns=model names,
        values=pr_auc scores. Shape: (n_folds * n_repeats) × n_models.
    """
    raise NotImplementedError


def friedman_test(scores_wide: pd.DataFrame) -> Tuple[float, float]:
    """
    Apply the Friedman test across all models.

    Uses scipy.stats.friedmanchisquare with one column per model.

    Args:
        scores_wide: Wide DataFrame — rows=observations, columns=models.

    Returns:
        Tuple (statistic, p_value).
        If p_value < ALPHA, proceed to pairwise Wilcoxon tests.
    """
    raise NotImplementedError


def pairwise_wilcoxon(
    scores_wide: pd.DataFrame,
) -> pd.DataFrame:
    """
    Run all 10 pairwise Wilcoxon signed-rank tests between 5 models.

    Uses scipy.stats.wilcoxon with zero_method="wilcox" (handles near-ceiling ties).
    Reports the raw p-value before Holm correction.

    Args:
        scores_wide: Wide DataFrame — rows=observations, columns=models.

    Returns:
        DataFrame with columns: model_a, model_b, statistic, p_value_raw.
        10 rows (C(5,2)=10 pairs).
    """
    raise NotImplementedError


def holm_correction(wilcoxon_df: pd.DataFrame) -> pd.DataFrame:
    """
    Apply Holm correction to pairwise Wilcoxon p-values.

    Uses statsmodels.stats.multitest.multipletests(method="holm").

    Args:
        wilcoxon_df: Output of pairwise_wilcoxon().

    Returns:
        Same DataFrame with added columns:
            p_value_holm  : Holm-corrected p-value
            reject        : True if the null hypothesis is rejected at ALPHA
        Written to TABLES_DIR / "wilcoxon_holm.csv".
    """
    raise NotImplementedError


def bootstrap_ci(
    scores_wide: pd.DataFrame,
    n_bootstrap: int = 1000,
    ci: float = 0.95,
) -> pd.DataFrame:
    """
    Compute bootstrap confidence intervals for each model's mean PR-AUC.

    Uses percentile bootstrap over the fold scores (not over the dataset).

    Args:
        scores_wide: Wide DataFrame.
        n_bootstrap: Number of bootstrap iterations.
        ci: Confidence level (default 0.95 → 95% CI).

    Returns:
        DataFrame with columns: model, mean, ci_low, ci_high.
    """
    raise NotImplementedError


def effect_sizes(scores_wide: pd.DataFrame) -> pd.DataFrame:
    """
    Compute paired mean difference and rank-biserial correlation for each pair.

    Rank-biserial correlation: r = 1 - (2 * wilcoxon_statistic) / (n * (n+1))
    where n = number of paired observations.

    Args:
        scores_wide: Wide DataFrame.

    Returns:
        DataFrame with columns: model_a, model_b, mean_diff, rank_biserial.
    """
    raise NotImplementedError


def plot_cv_boxplot(scores_wide: pd.DataFrame) -> Path:
    """
    Plot a boxplot of PR-AUC scores per model.

    Each box shows the distribution over all folds and repeats.
    Saved to FIGURES_DIR / "cv_boxplot.png".

    Returns:
        Path to the saved figure.
    """
    raise NotImplementedError


def plot_ranking_with_ci(ci_df: pd.DataFrame) -> Path:
    """
    Plot model ranking with 95% bootstrap CI error bars.

    This is Figure 1 of the 3-figure demo (§17 of design v5).
    Saved to FIGURES_DIR / "ranking_plot.png".

    Returns:
        Path to the saved figure.
    """
    raise NotImplementedError


def plot_pvalue_heatmap(wilcoxon_holm_df: pd.DataFrame) -> Path:
    """
    Plot a heatmap of Holm-corrected p-values for all 10 pairwise comparisons.

    Saved to FIGURES_DIR / "pvalue_heatmap.png".

    Returns:
        Path to the saved figure.
    """
    raise NotImplementedError


def run_all_stats(cv_type: str = "stratified") -> Dict:
    """
    Run the complete statistical validation protocol and return a results dict.

    Steps:
        1. Load CV scores
        2. Friedman test
        3. Pairwise Wilcoxon (if Friedman significant)
        4. Holm correction
        5. Bootstrap CIs
        6. Effect sizes
        7. Save all tables and figures
        8. Log to MLflow

    Args:
        cv_type: "stratified" or "grouped".

    Returns:
        Dict with keys: friedman, wilcoxon_holm, ci, effect_sizes.
    """
    raise NotImplementedError
