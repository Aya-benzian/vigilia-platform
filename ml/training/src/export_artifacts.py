"""
export_artifacts.py — Hand-off artifacts for Members 2, 3, and 4 (§11 of design v5).

This module produces everything that other members need from Member 1.
It is run at the end of the pipeline, after model selection and evaluation.

Artifacts produced:
    1. artifacts/test_predictions.parquet
       Columns: all features, is_fraud, calibrated_score, prediction, for fraudTest.
       Member 2 uses this to run PCA / t-SNE / UMAP visualizations.

    2. artifacts/feature_spec.json
       Machine-readable Contract 1 — raw input field names, types, ranges, order.
       Member 2 imports this to validate incoming requests.

    3. artifacts/model_card.json
       Full documentation of the final model (§15 of design v5).

    4. artifacts/scores_part_b.parquet
       Calibrated scores on part B. For Member 2's conformal prediction.

    5. artifacts/scores_part_c.parquet
       Calibrated scores on part C + threshold used. For Member 2 and Member 3.

All artifacts are saved to ARTIFACTS_DIR.
"""

import json
import logging
from pathlib import Path
from typing import Any, Dict, Optional

import pandas as pd

from config import ARTIFACTS_DIR, TARGET_COL

logger = logging.getLogger(__name__)


def export_test_predictions(
    calibrated_pipeline: Any,
    X_test: pd.DataFrame,
    y_test: pd.Series,
    threshold: float,
) -> Path:
    """
    Produce test_predictions.parquet for Member 2.

    Columns:
        All original feature columns from X_test
        is_fraud (true label)
        calibrated_score (predicted probability)
        prediction (0 or 1, using threshold)
    """
    logger.info(f"Generating test predictions on {len(X_test):,} samples...")
    probs = calibrated_pipeline.predict_proba(X_test)[:, 1]
    preds = (probs >= threshold).astype(int)

    df_out = X_test.copy()
    df_out["is_fraud"] = y_test.values
    df_out["calibrated_score"] = probs
    df_out["prediction"] = preds

    out_path = ARTIFACTS_DIR / "test_predictions.parquet"
    df_out.to_parquet(out_path, index=False)
    logger.info(f"Exported test predictions to {out_path} ({len(df_out):,} rows)")
    return out_path


def export_calibration_scores(
    calibrated_pipeline: Any,
    X_B: pd.DataFrame,
    y_B: pd.Series,
    X_C: pd.DataFrame,
    y_C: pd.Series,
    threshold: float,
) -> tuple[Path, Path]:
    """
    Export calibrated scores on parts B and C for Member 2.

    Part B scores: for conformal prediction calibration (Member 2).
    Part C scores: for threshold studies (Members 2 and 3).
    """
    logger.info("Generating calibration scores on parts B and C...")
    probs_b = calibrated_pipeline.predict_proba(X_B)[:, 1]
    df_b = X_B.copy()
    df_b["is_fraud"] = y_B.values
    df_b["calibrated_score"] = probs_b
    out_b = ARTIFACTS_DIR / "scores_part_b.parquet"
    df_b.to_parquet(out_b, index=False)

    probs_c = calibrated_pipeline.predict_proba(X_C)[:, 1]
    preds_c = (probs_c >= threshold).astype(int)
    df_c = X_C.copy()
    df_c["is_fraud"] = y_C.values
    df_c["calibrated_score"] = probs_c
    df_c["threshold"] = threshold
    df_c["prediction"] = preds_c
    out_c = ARTIFACTS_DIR / "scores_part_c.parquet"
    df_c.to_parquet(out_c, index=False)

    logger.info(f"Exported calibration scores: {out_b} and {out_c}")
    return out_b, out_c


def build_model_card(
    selected_model: str,
    threshold: Optional[float] = None,
) -> Dict:
    """Build the model_card.json summary."""
    from register import build_model_card as reg_build_card
    metrics_file = ARTIFACTS_DIR / "test_metrics.json"
    if metrics_file.exists():
        with open(metrics_file) as f:
            metrics = json.load(f)
    else:
        metrics = {}

    if threshold is None:
        thr_file = ARTIFACTS_DIR / "threshold.txt"
        threshold = float(thr_file.read_text().strip()) if thr_file.exists() else 0.5

    return reg_build_card(metrics, threshold, selected_model)


def export_all_artifacts(
    calibrated_pipeline: Any = None,
    X_test: Optional[pd.DataFrame] = None,
    y_test: Optional[pd.Series] = None,
    X_B: Optional[pd.DataFrame] = None,
    y_B: Optional[pd.Series] = None,
    X_C: Optional[pd.DataFrame] = None,
    y_C: Optional[pd.Series] = None,
    threshold: Optional[float] = None,
    selected_model: Optional[str] = None,
) -> Dict[str, Path]:
    """
    Run all export functions in sequence and return paths to all artifacts.
    """
    import pickle
    from data_io import load_train_raw, split_train_abc, load_test_raw
    from preprocess import export_feature_spec

    logger.info("=== EXPORTING ALL HAND-OFF ARTIFACTS ===")
    if calibrated_pipeline is None:
        with open(ARTIFACTS_DIR / "calibrated_pipeline.pkl", "rb") as f:
            calibrated_pipeline = pickle.load(f)

    if threshold is None:
        threshold = float((ARTIFACTS_DIR / "threshold.txt").read_text().strip())

    if selected_model is None:
        selected_model = (ARTIFACTS_DIR / "selected_model.txt").read_text().strip()

    if X_test is None or y_test is None:
        df_test = load_test_raw()
        X_test = df_test.drop(columns=[TARGET_COL], errors="ignore")
        y_test = df_test[TARGET_COL]

    if X_B is None or y_B is None or X_C is None or y_C is None:
        df_train = load_train_raw()
        _, part_b, part_c = split_train_abc(df_train)
        X_B = part_b.drop(columns=[TARGET_COL], errors="ignore")
        y_B = part_b[TARGET_COL]
        X_C = part_c.drop(columns=[TARGET_COL], errors="ignore")
        y_C = part_c[TARGET_COL]

    out_paths = {}
    out_paths["test_predictions"] = export_test_predictions(calibrated_pipeline, X_test, y_test, threshold)
    out_b, out_c = export_calibration_scores(calibrated_pipeline, X_B, y_B, X_C, y_C, threshold)
    out_paths["scores_part_b"] = out_b
    out_paths["scores_part_c"] = out_c
    out_paths["feature_spec"] = export_feature_spec()
    build_model_card(selected_model, threshold=threshold)
    out_paths["model_card"] = ARTIFACTS_DIR / "model_card.json"

    logger.info("=== ALL ARTIFACTS SUCCESSFULLY EXPORTED ===")
    for name, path in out_paths.items():
        logger.info(f"  - {name}: {path}")

    return out_paths


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO, format="%(asctime)s | %(levelname)s | %(name)s | %(message)s")
    export_all_artifacts()
