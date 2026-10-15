"""
io.py — Data loading and integrity utilities for Member 1.

Responsibilities:
- Load fraudTrain.csv and fraudTest.csv from data/raw/
- Compute SHA-256 hash of each file (logged in MLflow for traceability)
- Split fraudTrain into parts A / B / C (consecutive in time)
- Return raw DataFrames; all preprocessing happens inside the Pipeline

Rules:
- Raw data is NEVER modified here. This module is read-only.
- The split is always temporal (first 80% / next 10% / last 10% of fraudTrain).
- Seed is not needed for the time-based split.
"""

import hashlib
import logging
import os
from pathlib import Path

import pandas as pd
import numpy as np

from config import (
    TRAIN_FILE,
    TEST_FILE,
    ULB_FILE,
    SPLIT_A,
    SPLIT_B,
    TARGET_COL,
    GROUP_COL,
    COMPARE_SUBSAMPLE_N,
    TUNE_SUBSAMPLE_N,
    SEED,
)

logger = logging.getLogger(__name__)

# Column name for the transaction datetime in the Sparkov CSV
DATETIME_COL = "trans_date_trans_time"


# ==============================================================================
# File integrity
# ==============================================================================

def file_sha256(path: Path, chunk_size: int = 1 << 20) -> str:
    """
    Compute the SHA-256 hash of a file in chunks (1 MB default).

    Used to verify data integrity and log a reproducible data fingerprint
    to MLflow so every experiment is traceable to an exact data version.

    Args:
        path: Absolute path to the file.
        chunk_size: Read chunk size in bytes (default 1 MB).

    Returns:
        Lowercase hex SHA-256 digest string.
    """
    h = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(chunk_size)
            if not chunk:
                break
            h.update(chunk)
    digest = h.hexdigest()
    logger.info(f"SHA-256({path.name}) = {digest}")
    return digest


# ==============================================================================
# Raw data loading
# ==============================================================================

def _parse_datetime(df: pd.DataFrame) -> pd.DataFrame:
    """Parse the transaction datetime column to pandas datetime."""
    if DATETIME_COL in df.columns:
        df[DATETIME_COL] = pd.to_datetime(df[DATETIME_COL], infer_datetime_format=True)
    return df


def load_train_raw() -> pd.DataFrame:
    """
    Load fraudTrain.csv from data/raw/ and return the raw DataFrame
    sorted ascending by transaction datetime.

    Returns:
        Raw fraudTrain DataFrame.

    Raises:
        FileNotFoundError: If fraudTrain.csv is not found in data/raw/.
    """
    if not TRAIN_FILE.exists():
        raise FileNotFoundError(
            f"fraudTrain.csv not found at {TRAIN_FILE}.\n"
            "Place the file in ml/training/data/raw/ first."
        )
    logger.info(f"Loading {TRAIN_FILE} ...")
    df = pd.read_csv(TRAIN_FILE, low_memory=False)
    df = _parse_datetime(df)
    df = df.sort_values(DATETIME_COL).reset_index(drop=True)
    logger.info(f"fraudTrain loaded: {df.shape[0]:,} rows, {df.shape[1]} columns")
    return df


def load_test_raw() -> pd.DataFrame:
    """
    Load fraudTest.csv from data/raw/ and return the raw DataFrame
    sorted ascending by transaction datetime.

    Returns:
        Raw fraudTest DataFrame.

    Raises:
        FileNotFoundError: If fraudTest.csv is not found in data/raw/.
    """
    if not TEST_FILE.exists():
        raise FileNotFoundError(
            f"fraudTest.csv not found at {TEST_FILE}.\n"
            "Place the file in ml/training/data/raw/ first."
        )
    logger.info(f"Loading {TEST_FILE} ...")
    df = pd.read_csv(TEST_FILE, low_memory=False)
    df = _parse_datetime(df)
    df = df.sort_values(DATETIME_COL).reset_index(drop=True)
    logger.info(f"fraudTest loaded: {df.shape[0]:,} rows, {df.shape[1]} columns")
    return df


def load_ulb_raw() -> pd.DataFrame:
    """
    [NICE] Load creditcard.csv (ULB dataset) from data/raw/.

    Used ONLY for the optional ranking robustness benchmark.
    Never merged with Sparkov. Never used for final model selection.

    Returns:
        Raw ULB DataFrame.

    Raises:
        FileNotFoundError: If creditcard.csv is not found in data/raw/.
    """
    if not ULB_FILE.exists():
        raise FileNotFoundError(
            f"creditcard.csv not found at {ULB_FILE}."
        )
    logger.info(f"Loading {ULB_FILE} ...")
    df = pd.read_csv(ULB_FILE)
    logger.info(f"ULB loaded: {df.shape[0]:,} rows, {df.shape[1]} columns")
    return df


# ==============================================================================
# Temporal train split: A / B / C
# ==============================================================================

def split_train_abc(
    df_train: pd.DataFrame,
) -> tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    """
    Split fraudTrain into three consecutive temporal parts.

    fraudTrain must already be sorted by transaction date (load_train_raw does this).

    Parts:
        A — first 80%  : tuning subsample + comparison subsample + final model fit
        B — next 10%   : calibrator fit only
        C — last 10%   : threshold selection on calibrated probabilities

    After splitting, verify on day 1:
        - Part B has enough frauds (print part_b[TARGET_COL].sum())
        - Part C has enough frauds (print part_c[TARGET_COL].sum())

    Args:
        df_train: Raw fraudTrain DataFrame sorted ascending by transaction datetime.

    Returns:
        Tuple (part_A, part_B, part_C) — non-overlapping consecutive slices.
    """
    n = len(df_train)
    n_a = int(n * SPLIT_A)
    n_b = int(n * SPLIT_B)
    # part C gets whatever remains to avoid rounding losing rows
    part_a = df_train.iloc[:n_a].copy()
    part_b = df_train.iloc[n_a : n_a + n_b].copy()
    part_c = df_train.iloc[n_a + n_b :].copy()

    logger.info(
        f"Split A/B/C: A={len(part_a):,} rows ({part_a[TARGET_COL].sum()} frauds) | "
        f"B={len(part_b):,} rows ({part_b[TARGET_COL].sum()} frauds) | "
        f"C={len(part_c):,} rows ({part_c[TARGET_COL].sum()} frauds)"
    )

    # Sanity check — B and C must each have at least some frauds
    if part_b[TARGET_COL].sum() < 50:
        logger.warning(
            f"Part B only has {part_b[TARGET_COL].sum()} frauds. "
            "Calibration may be unreliable. Check SPLIT_B in config.py."
        )
    if part_c[TARGET_COL].sum() < 50:
        logger.warning(
            f"Part C only has {part_c[TARGET_COL].sum()} frauds. "
            "Threshold selection may be unreliable. Check SPLIT_C in config.py."
        )

    return part_a, part_b, part_c


# ==============================================================================
# Subsampling helpers
# ==============================================================================

def stratified_subsample(
    df: pd.DataFrame,
    n: int,
    target_col: str = TARGET_COL,
    random_state: int = SEED,
) -> pd.DataFrame:
    """
    Draw a stratified subsample of n rows from df, preserving the fraud rate.

    If df has fewer rows than n, returns the full df (with a warning).

    Args:
        df: Source DataFrame.
        n: Target number of rows.
        target_col: Binary target column name.
        random_state: Random seed.

    Returns:
        Stratified subsample DataFrame of size min(n, len(df)).
    """
    if len(df) <= n:
        logger.warning(
            f"Requested subsample of {n:,} but df only has {len(df):,} rows. "
            "Returning full df."
        )
        return df.copy()

    fraud_rate = df[target_col].mean()
    n_fraud = max(1, int(n * fraud_rate))
    n_legit = n - n_fraud

    fraud_df = df[df[target_col] == 1].sample(n=n_fraud, random_state=random_state)
    legit_df = df[df[target_col] == 0].sample(n=n_legit, random_state=random_state)

    result = pd.concat([fraud_df, legit_df]).sample(frac=1, random_state=random_state)
    result = result.reset_index(drop=True)

    logger.info(
        f"Stratified subsample: {len(result):,} rows, "
        f"fraud rate={result[target_col].mean():.4f} "
        f"(original={fraud_rate:.4f})"
    )
    return result


def make_tune_and_compare_subsamples(
    part_a: pd.DataFrame,
) -> tuple[pd.DataFrame, pd.DataFrame]:
    """
    Create two DISJOINT stratified subsamples from part A:
        - Tuning subsample    : used by Optuna (TUNE_SUBSAMPLE_N rows)
        - Comparison subsample: used by repeated CV (COMPARE_SUBSAMPLE_N rows)

    Disjoint means no row appears in both. This prevents leakage between the
    tuning stage and the model comparison stage.

    Strategy:
        1. Shuffle part A with seed SEED.
        2. Take first TUNE_SUBSAMPLE_N rows as tuning set.
        3. Take next COMPARE_SUBSAMPLE_N rows as comparison set.
        4. Both are then stratified-balanced to match the fraud rate.

    Args:
        part_a: Part A DataFrame.

    Returns:
        Tuple (tune_df, compare_df).
    """
    total_needed = TUNE_SUBSAMPLE_N + COMPARE_SUBSAMPLE_N
    if len(part_a) < total_needed:
        logger.warning(
            f"Part A has {len(part_a):,} rows but we need {total_needed:,} "
            "for disjoint subsamples. Reducing subsample sizes proportionally."
        )

    # Shuffle once with fixed seed to ensure reproducibility
    shuffled = part_a.sample(frac=1, random_state=SEED).reset_index(drop=True)

    # Split disjoint slices
    tune_pool = shuffled.iloc[:TUNE_SUBSAMPLE_N * 2]      # take extra to allow stratification
    compare_pool = shuffled.iloc[TUNE_SUBSAMPLE_N * 2:]    # completely disjoint

    tune_df    = stratified_subsample(tune_pool,    TUNE_SUBSAMPLE_N,    random_state=SEED)
    compare_df = stratified_subsample(compare_pool, COMPARE_SUBSAMPLE_N, random_state=SEED + 1)

    logger.info(
        f"Tuning subsample: {len(tune_df):,} rows | "
        f"Comparison subsample: {len(compare_df):,} rows | "
        "Disjoint: YES"
    )
    return tune_df, compare_df


# ==============================================================================
# Quick data report (run on day 1 — copy output into DATASET.md)
# ==============================================================================

def print_data_report(df_train: pd.DataFrame, df_test: pd.DataFrame) -> None:
    """
    Print key statistics to stdout on day 1.

    Copy the output into DATASET.md to fill all (verify) placeholders.
    """
    sep = "=" * 60

    print(sep)
    print("FRAUD TRAIN")
    print(sep)
    print(f"Shape         : {df_train.shape}")
    print(f"Columns       : {df_train.columns.tolist()}")
    print(f"Fraud rate    : {df_train[TARGET_COL].mean():.6f} ({df_train[TARGET_COL].sum()} frauds)")
    print(f"Date range    : {df_train[DATETIME_COL].min()} → {df_train[DATETIME_COL].max()}")
    print(f"Missing values:\n{df_train.isnull().sum()[df_train.isnull().sum() > 0]}")
    print(f"SHA-256       : {file_sha256(TRAIN_FILE)}")

    print()
    print(sep)
    print("FRAUD TEST")
    print(sep)
    print(f"Shape         : {df_test.shape}")
    print(f"Fraud rate    : {df_test[TARGET_COL].mean():.6f} ({df_test[TARGET_COL].sum()} frauds)")
    print(f"Date range    : {df_test[DATETIME_COL].min()} → {df_test[DATETIME_COL].max()}")
    print(f"Missing values:\n{df_test.isnull().sum()[df_test.isnull().sum() > 0]}")
    print(f"SHA-256       : {file_sha256(TEST_FILE)}")

    print()
    print(sep)
    print("A / B / C SPLITS")
    print(sep)
    part_a, part_b, part_c = split_train_abc(df_train)
    for name, part in [("A", part_a), ("B", part_b), ("C", part_c)]:
        print(
            f"Part {name}: {len(part):,} rows | "
            f"{part[TARGET_COL].sum()} frauds | "
            f"fraud rate = {part[TARGET_COL].mean():.6f}"
        )

    print()
    print(sep)
    print("UNIQUE CARDHOLDERS (cc_num)")
    print(sep)
    print(f"Train: {df_train[GROUP_COL].nunique():,} unique cardholders")
    print(f"Test : {df_test[GROUP_COL].nunique():,} unique cardholders")
    print(sep)
