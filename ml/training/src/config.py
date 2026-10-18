"""
config.py — Central configuration for Member 1 ML training pipeline.

All seeds, paths, cost matrix, CV settings, tuning settings, and
experiment names live here. Changing a value here changes it everywhere.

Never import project-specific modules here (no circular imports).
"""

from pathlib import Path

# ==============================================================================
# Reproducibility
# ==============================================================================
SEED = 42  # Used everywhere: train/test split, CV, Optuna, model init

# ==============================================================================
# Paths
# ==============================================================================
ROOT_DIR       = Path(__file__).resolve().parent.parent  # ml/training/
DATA_RAW_DIR   = ROOT_DIR / "data" / "raw"
DATA_INT_DIR   = ROOT_DIR / "data" / "interim"
ARTIFACTS_DIR  = ROOT_DIR / "artifacts"
REPORTS_DIR    = ROOT_DIR / "reports"
FIGURES_DIR    = REPORTS_DIR / "figures"
TABLES_DIR     = REPORTS_DIR / "tables"

TRAIN_FILE = DATA_RAW_DIR / "fraudTrain.csv"
TEST_FILE  = DATA_RAW_DIR / "fraudTest.csv"
ULB_FILE   = DATA_RAW_DIR / "creditcard.csv"   # [NICE] optional

# ==============================================================================
# Data splits — consecutive in time (§4 of design v5)
# ==============================================================================
# fraudTrain → A (80%) | B (10%) | C (10%)
SPLIT_A = 0.80  # development: tuning + comparison + final model fit
SPLIT_B = 0.10  # calibration: fit calibrator only
SPLIT_C = 0.10  # validation: choose threshold on calibrated probabilities
# fraudTest is the final test — used ONCE at the very end

# ==============================================================================
# Subsampling for compute-heavy stages (§4 — compute reality)
# ==============================================================================
# Comparison subsample: drawn from A, used for repeated CV
COMPARE_SUBSAMPLE_N = 150_000   # 100k–200k rows as in design v5
# Tuning subsample: disjoint from comparison subsample, used by Optuna
TUNE_SUBSAMPLE_N    = 100_000
# SVM subsample: RBF SVM is quadratic/cubic in N; 10k rows is fast (~3 min for 30 trials)
SVM_SUBSAMPLE_N     = 10_000
# Verify that each subsample retains at least a few hundred frauds after split

# ==============================================================================
# Cross-validation (§9 of design v5)
# ==============================================================================
CV_N_SPLITS  = 5
CV_N_REPEATS = 10   # 5 × 10 = 50 paired scores per model
# Primary metric for model selection and Optuna objective
CV_PRIMARY_METRIC = "average_precision"  # PR-AUC = sklearn average_precision_score
CV_SECONDARY_METRICS = ["f1", "matthews_corrcoef"]

# ==============================================================================
# Optuna tuning (§6 of design v5)
# ==============================================================================
OPTUNA_N_TRIALS = 30       # same budget for all models where feasible
OPTUNA_TIMEOUT  = None     # set seconds if equal-time budget needed for SVM/MLP
OPTUNA_METRIC   = "average_precision"
OPTUNA_DIRECTION = "maximize"

import os
import socket

def _resolve_tracking_uri() -> str:
    env_uri = os.getenv("MLFLOW_TRACKING_URI")
    if env_uri:
        return env_uri
    for host in ("localhost", "mlflow"):
        try:
            with socket.create_connection((host, 5000), timeout=0.5):
                return f"http://{host}:5000"
        except (OSError, socket.error):
            pass
    local_db = (ROOT_DIR / "mlflow.db").resolve()
    return f"sqlite:///{local_db.as_posix()}"

# MLflow experiments
MLFLOW_TRACKING_URI     = _resolve_tracking_uri()
MLFLOW_EXP_TUNING       = "fraud-sparkov-tuning"
MLFLOW_EXP_COMPARE      = "fraud-sparkov-compare"
MLFLOW_EXP_FINAL        = "fraud-final"
MLFLOW_EXP_ULB          = "fraud-ulb-benchmark"  # [NICE]
MLFLOW_MODEL_NAME       = "fraud-model"           # registered model name
MLFLOW_MODEL_ALIAS      = "champion"              # Contract 10
MLFLOW_CHAMPION_ALIAS   = "champion"

# ==============================================================================
# Cost matrix for cost-based evaluation (§8 of design v5)
# These are ASSUMPTIONS — must be stated clearly in the report.
# ==============================================================================
# cost_fn = False Negative cost = missed fraud (e.g. full transaction amount)
# cost_fp = False Positive cost = unnecessary human review (fixed cost)
COST_FP = 5.0      # assumed fixed review cost per false alarm (USD)
COST_FN = 100.0    # assumed average loss per missed fraud (USD) for fixed-cost calculation
COST_TN = 0.0      # no cost for true negative
COST_TP = 0.0      # no cost for true positive
# cost_fn is dynamic (= transaction amount) — computed per transaction in cost_review.py

# ==============================================================================
# Identifiers to drop (never features)
# ==============================================================================
DROP_COLS = ["trans_num", "first", "last", "street"]
GROUP_COL = "cc_num"   # used as group key in StratifiedGroupKFold, not a feature
TARGET_COL = "is_fraud"

# ==============================================================================
# Statistical tests (§9 of design v5)
# ==============================================================================
ALPHA = 0.05   # significance level for Holm correction
# Practical margin: a model must beat another by at least this PR-AUC difference
# to be considered practically better (used in selection rule — see select_model.py)
PRACTICAL_MARGIN = 0.005

# ==============================================================================
# Selection rule tiebreaker (§7 — also encoded in select_model.py)
# 1. Mean PR-AUC (primary)
# 2. Holm-adjusted p >= ALPHA → tied set
# 3. Lowest Brier after calibration
# 4. Lowest p95 inference time
# 5. Explainability (tree-based preferred for Member 2 SHAP)
# ==============================================================================
TIEBREAKER_ORDER = ["brier_calibrated", "inference_p95_ms", "is_tree_based"]
