"""
pipeline.py — Reproducible end-to-end training pipeline for Member 1 (§13 of design v5).

This is the main orchestrator. It is called by:
    make all           → python -m src.pipeline all
    make tune          → python -m src.pipeline tune
    make compare       → python -m src.pipeline compare
    (etc.)

The spec requires this exact sequence:
    Training → Evaluation → Statistical Validation → Model Registry → Deployment

Implemented here as:
    1. data_check   → load data, print report, verify splits A/B/C
    2. tune         → Optuna tuning on tuning subsample
    3. compare      → Repeated CV on comparison subsample
    4. stats        → Friedman / Wilcoxon / Holm / CIs / effect sizes
    5. select       → Apply pre-committed selection rule (select_model.py)
    6. calibrate    → Calibrate all models on part B, threshold on part C
    7. evaluate     → ONE-TIME evaluation on fraudTest
    8. register     → Export artifacts, register champion in MLflow

Each stage reads its inputs from disk (parquet/json/pickle) and writes its
outputs to disk so stages can be re-run independently without re-running
all previous stages.
"""

import argparse
import logging
import os
import sys
from pathlib import Path

from config import SEED, MLFLOW_TRACKING_URI

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
)
logger = logging.getLogger(__name__)

STAGES = ["data_check", "tune", "compare", "stats", "select", "calibrate", "evaluate", "register", "all"]


def stage_data_check() -> None:
    """
    Stage 1: Load data, print report, verify A/B/C splits.

    On day 1:
        - Print shapes, column names, fraud rates, missing values, date ranges.
        - Verify part B and C each have >= a few hundred frauds.
        - Fill DATASET.md with real numbers.
        - Log data hashes to MLflow.
    """
    raise NotImplementedError


def stage_tune() -> None:
    """
    Stage 2: Optuna hyperparameter tuning.

    Input: data from disk (tuning subsample saved by data_check).
    Output: best_params.json written to artifacts/.
    MLflow: logged under MLFLOW_EXP_TUNING.
    """
    raise NotImplementedError


def stage_compare() -> None:
    """
    Stage 3: Repeated CV model comparison.

    Input: best_params.json, comparison subsample from disk.
    Output: cv_scores_long.parquet written to reports/tables/.
    MLflow: logged under MLFLOW_EXP_COMPARE.
    """
    raise NotImplementedError


def stage_stats() -> None:
    """
    Stage 4: Statistical validation.

    Input: cv_scores_long.parquet.
    Output: friedman.csv, wilcoxon_holm.csv, figures.
    MLflow: logged under MLFLOW_EXP_COMPARE.
    """
    raise NotImplementedError


def stage_select() -> None:
    """
    Stage 5: Apply the pre-committed selection rule.

    Input: cv_scores_long.parquet, wilcoxon_holm.csv, brier scores (placeholder
           until calibrate runs — use raw Brier on a held-out set).
    Output: selected_model.json written to artifacts/.

    Note: Brier-after-calibration scores are not available until after stage_calibrate().
    The selection rule is applied in two steps:
        Step 5a (here): Build tied set from Holm results + mean PR-AUC.
        Step 5b (after calibrate): Apply Brier tiebreaker.
    """
    raise NotImplementedError


def stage_calibrate() -> None:
    """
    Stage 6: Calibrate all models on part B, choose threshold on part C.

    Input: fitted pipelines from artifacts/, part B and C from disk.
    Output: calibrated pipelines, threshold, calibration report in artifacts/.
    MLflow: logged under MLFLOW_EXP_FINAL (as sub-run).
    """
    raise NotImplementedError


def stage_evaluate() -> None:
    """
    Stage 7: ONE-TIME evaluation on fraudTest.

    =============================================================================
    Run this stage exactly ONCE. fraudTest is never used again after this.
    =============================================================================

    Input: calibrated finalist pipeline, fraudTest from disk.
    Output: test_metrics.csv, inference_timing.json, cost/review figures.
    MLflow: logged under MLFLOW_EXP_FINAL.
    """
    raise NotImplementedError


def stage_register() -> None:
    """
    Stage 8: Export artifacts and register champion model in MLflow.

    Input: calibrated pipeline, base pipeline, all artifact files.
    Output: Registered model in MLflow with alias "champion".
    MLflow: final run under MLFLOW_EXP_FINAL.
    """
    raise NotImplementedError


def run_all() -> None:
    """Run all stages in sequence."""
    stage_data_check()
    stage_tune()
    stage_compare()
    stage_stats()
    stage_select()
    stage_calibrate()
    stage_evaluate()
    stage_register()
    logger.info("=== Full pipeline completed successfully ===")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Vigilia ML Training Pipeline (Member 1)"
    )
    parser.add_argument(
        "stage",
        choices=STAGES,
        help="Pipeline stage to run. Use 'all' to run the full pipeline.",
    )
    args = parser.parse_args()

    stage_fn = {
        "data_check": stage_data_check,
        "tune":       stage_tune,
        "compare":    stage_compare,
        "stats":      stage_stats,
        "select":     stage_select,
        "calibrate":  stage_calibrate,
        "evaluate":   stage_evaluate,
        "register":   stage_register,
        "all":        run_all,
    }

    logger.info(f"=== Starting stage: {args.stage} ===")
    stage_fn[args.stage]()


if __name__ == "__main__":
    main()
