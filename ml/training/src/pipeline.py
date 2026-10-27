"""
pipeline.py — Full training pipeline orchestrator (Member 1).

CLI usage (via Makefile or directly):
    python -m src.pipeline data_check
    python -m src.pipeline tune
    python -m src.pipeline compare
    python -m src.pipeline stats
    python -m src.pipeline select
    python -m src.pipeline calibrate
    python -m src.pipeline evaluate
    python -m src.pipeline register
    python -m src.pipeline all       <- runs all stages in order

Each stage is idempotent and logs to MLflow.
"""

import argparse
import logging
import sys
from pathlib import Path

# Ensure src directory is in sys.path so config and sibling modules resolve cleanly
_SRC_DIR = Path(__file__).resolve().parent
if str(_SRC_DIR) not in sys.path:
    sys.path.insert(0, str(_SRC_DIR))

import mlflow
import numpy as np
import pandas as pd

from config import (
    MLFLOW_TRACKING_URI,
    MLFLOW_EXP_COMPARE,
    TARGET_COL,
    GROUP_COL,
    ALPHA,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s | %(levelname)s | %(name)s | %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("pipeline")


# ==============================================================================
# Stages
# ==============================================================================

def stage_data_check():
    """Load data, verify shapes, print day-1 report. Fill DATASET.md after this."""
    from data_io import load_train_raw, load_test_raw, print_data_report, split_train_abc
    logger.info("=== STAGE: data_check ===")
    df_train = load_train_raw()
    df_test  = load_test_raw()
    print_data_report(df_train, df_test)
    logger.info("data_check complete. Fill DATASET.md with the printed values.")


def stage_tune():
    """Optuna tuning on tune subsample of part A. Returns and saves best params."""
    from data_io import load_train_raw, split_train_abc, make_tune_and_compare_subsamples
    from tune import run_all_tuning
    import json

    logger.info("=== STAGE: tune ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    df_train         = load_train_raw()
    part_a, _, _     = split_train_abc(df_train)
    X_tune, _        = make_tune_and_compare_subsamples(part_a)
    y_tune           = X_tune.pop(TARGET_COL) if TARGET_COL in X_tune.columns else part_a.loc[X_tune.index, TARGET_COL]

    # Re-attach target correctly
    X_tune_feat = part_a.loc[X_tune.index].drop(columns=[TARGET_COL], errors="ignore")
    y_tune_vals = part_a.loc[X_tune.index, TARGET_COL]

    n_neg = (y_tune_vals == 0).sum()
    n_pos = (y_tune_vals == 1).sum()
    spw   = float(n_neg / max(n_pos, 1))

    best_params = run_all_tuning(X_tune_feat, y_tune_vals, scale_pos_weight=spw)

    from config import ARTIFACTS_DIR
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
    params_path = ARTIFACTS_DIR / "best_params.json"
    with open(params_path, "w") as f:
        # Convert non-serializable values (e.g. tuples)
        serializable = {
            k: {pk: (list(pv) if isinstance(pv, tuple) else pv)
                for pk, pv in v.items()}
            for k, v in best_params.items()
        }
        json.dump(serializable, f, indent=2)
    logger.info(f"Best params saved to {params_path}")
    return best_params


def stage_compare(best_params: dict = None):
    """Repeated CV comparison on comparison subsample of part A."""
    from data_io import load_train_raw, split_train_abc, make_tune_and_compare_subsamples
    from cv_compare import compare_models, compare_models_group_cv
    import json, pickle

    logger.info("=== STAGE: compare ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    if best_params is None:
        from config import ARTIFACTS_DIR
        params_path = ARTIFACTS_DIR / "best_params.json"
        with open(params_path) as f:
            best_params = json.load(f)

    df_train    = load_train_raw()
    part_a, _, _= split_train_abc(df_train)
    _, X_cmp    = make_tune_and_compare_subsamples(part_a)
    X_cmp_feat  = part_a.loc[X_cmp.index].drop(columns=[TARGET_COL], errors="ignore")
    y_cmp       = part_a.loc[X_cmp.index, TARGET_COL]
    groups      = part_a.loc[X_cmp.index, GROUP_COL]

    results       = compare_models(X_cmp_feat, y_cmp, best_params)
    results_group = compare_models_group_cv(X_cmp_feat, y_cmp, groups, best_params)

    from config import ARTIFACTS_DIR
    with open(ARTIFACTS_DIR / "cv_results.pkl", "wb") as f:
        pickle.dump({"repeated": results, "group": results_group}, f)
    logger.info("CV results saved.")
    return results, results_group


def stage_stats(results: dict = None):
    """Statistical tests on CV results."""
    from stats_tests import run_stats_pipeline
    import pickle

    logger.info("=== STAGE: stats ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    if results is None:
        from config import ARTIFACTS_DIR
        with open(ARTIFACTS_DIR / "cv_results.pkl", "rb") as f:
            data = pickle.load(f)
        results = data["repeated"]

    # Best model = highest mean PR-AUC
    best_model = max(results, key=lambda m: results[m].mean())
    logger.info(f"Best candidate by mean PR-AUC: {best_model}")

    stats_out = run_stats_pipeline(results, best_model)
    return stats_out, best_model


def stage_select(results: dict = None, best_model_candidate: str = None):
    """Apply the pre-committed selection rule."""
    from select_model import select_model
    import pickle

    logger.info("=== STAGE: select ===")

    if results is None:
        from config import ARTIFACTS_DIR
        with open(ARTIFACTS_DIR / "cv_results.pkl", "rb") as f:
            data = pickle.load(f)
        results = data["repeated"]

    # Build scores_dict for select_model
    scores_dict = {m: {"mean": float(s.mean()), "std": float(s.std())}
                   for m, s in results.items()}

    ret = select_model(scores_dict)
    selected = ret[0] if isinstance(ret, tuple) else ret
    logger.info(f"Selected champion: {selected}")

    from config import ARTIFACTS_DIR
    (ARTIFACTS_DIR / "selected_model.txt").write_text(selected)
    return selected


def stage_calibrate(selected_model: str = None, best_params: dict = None):
    """Train final model on full part A, calibrate on B, select threshold on C."""
    from data_io import load_train_raw, split_train_abc
    from preprocess import build_pipeline
    from models import make_logreg, make_random_forest, make_xgboost, make_svm, make_mlp
    from calibrate import run_calibration
    import json, pickle

    logger.info("=== STAGE: calibrate ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    from config import ARTIFACTS_DIR
    if selected_model is None:
        selected_model = (ARTIFACTS_DIR / "selected_model.txt").read_text().strip()
    if best_params is None:
        with open(ARTIFACTS_DIR / "best_params.json") as f:
            best_params = json.load(f)

    df_train           = load_train_raw()
    part_a, part_b, part_c = split_train_abc(df_train)

    X_a = part_a.drop(columns=[TARGET_COL], errors="ignore")
    y_a = part_a[TARGET_COL]
    X_b = part_b.drop(columns=[TARGET_COL], errors="ignore")
    y_b = part_b[TARGET_COL]
    X_c = part_c.drop(columns=[TARGET_COL], errors="ignore")
    y_c = part_c[TARGET_COL]

    # Build and fit final model on full part A
    factory_map = {
        "logreg":  (make_logreg,         "logreg"),
        "rf":      (make_random_forest,   "rf"),
        "xgboost": (make_xgboost,         "xgboost"),
        "svm":     (make_svm,             "svm"),
        "mlp":     (make_mlp,             "mlp"),
    }
    make_fn, model_type = factory_map[selected_model]
    params = best_params.get(selected_model, {})

    if selected_model == "xgboost":
        spw = float((y_a == 0).sum() / max((y_a == 1).sum(), 1))
        model = make_fn(params, scale_pos_weight=spw)
    else:
        model = make_fn(params)

    pipe = build_pipeline(model, model_type=model_type)
    logger.info(f"Fitting {selected_model} on full part A ({len(X_a):,} rows)...")
    pipe.fit(X_a, y_a)

    # Calibrate + threshold
    cal_pipe, threshold = run_calibration(pipe, X_b, y_b, X_c, y_c)

    # Save
    with open(ARTIFACTS_DIR / "calibrated_pipeline.pkl", "wb") as f:
        pickle.dump(cal_pipe, f)
    with open(ARTIFACTS_DIR / "threshold.txt", "w") as f:
        f.write(str(threshold))

    logger.info(f"Calibrated pipeline saved. Threshold={threshold:.4f}")
    return cal_pipe, threshold


def stage_evaluate(cal_pipe=None, threshold: float = None, selected_model: str = None):
    """FINAL evaluation on fraudTest. Run ONCE."""
    from data_io import load_test_raw
    from evaluate import evaluate_on_test
    import pickle

    logger.info("=== STAGE: evaluate (ONE TIME ONLY) ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)
    mlflow.set_experiment(MLFLOW_EXP_COMPARE)

    from config import ARTIFACTS_DIR
    if cal_pipe is None:
        with open(ARTIFACTS_DIR / "calibrated_pipeline.pkl", "rb") as f:
            cal_pipe = pickle.load(f)
    if threshold is None:
        threshold = float((ARTIFACTS_DIR / "threshold.txt").read_text().strip())
    if selected_model is None:
        selected_model = (ARTIFACTS_DIR / "selected_model.txt").read_text().strip()

    df_test = load_test_raw()
    X_test  = df_test.drop(columns=[TARGET_COL], errors="ignore")
    y_test  = df_test[TARGET_COL]

    metrics = evaluate_on_test(cal_pipe, X_test, y_test, threshold, selected_model)

    import json
    with open(ARTIFACTS_DIR / "test_metrics.json", "w") as f:
        json.dump({k: v for k, v in metrics.items() if not isinstance(v, bytes)}, f, indent=2)

    return metrics


def stage_register(cal_pipe=None, metrics: dict = None, threshold: float = None, selected_model: str = None):
    """Register champion in MLflow Model Registry with @champion alias."""
    from register import register_champion
    import json, pickle

    logger.info("=== STAGE: register ===")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    from config import ARTIFACTS_DIR
    if cal_pipe is None:
        with open(ARTIFACTS_DIR / "calibrated_pipeline.pkl", "rb") as f:
            cal_pipe = pickle.load(f)
    if metrics is None:
        with open(ARTIFACTS_DIR / "test_metrics.json") as f:
            metrics = json.load(f)
    if threshold is None:
        threshold = float((ARTIFACTS_DIR / "threshold.txt").read_text().strip())
    if selected_model is None:
        selected_model = (ARTIFACTS_DIR / "selected_model.txt").read_text().strip()

    version = register_champion(cal_pipe, metrics, threshold, selected_model)
    logger.info(f"Champion registered as version {version} with alias @champion")
    return version


# ==============================================================================
# CLI
# ==============================================================================

def main():
    parser = argparse.ArgumentParser(description="Vigilia ML Training Pipeline")
    parser.add_argument(
        "stage",
        choices=["data_check", "tune", "compare", "stats", "select",
                 "calibrate", "evaluate", "register", "all"],
        help="Pipeline stage to run",
    )
    args = parser.parse_args()

    if args.stage == "data_check":
        stage_data_check()

    elif args.stage == "tune":
        stage_tune()

    elif args.stage == "compare":
        stage_compare()

    elif args.stage == "stats":
        stage_stats()

    elif args.stage == "select":
        stage_select()

    elif args.stage == "calibrate":
        stage_calibrate()

    elif args.stage == "evaluate":
        stage_evaluate()

    elif args.stage == "register":
        stage_register()

    elif args.stage == "all":
        logger.info("=== RUNNING ALL STAGES ===")
        best_params = stage_tune()
        results, _  = stage_compare(best_params)
        _,  best    = stage_stats(results)
        selected    = stage_select(results, best)
        cal, thr    = stage_calibrate(selected, best_params)
        metrics     = stage_evaluate(cal, thr, selected)
        stage_register(cal, metrics, thr, selected)
        logger.info("=== ALL STAGES COMPLETE ===")


if __name__ == "__main__":
    main()
