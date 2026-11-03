import json
import pickle
import sys
from pathlib import Path
import numpy as np
import pandas as pd
import pytest
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import Pipeline

SRC_DIR = Path(__file__).resolve().parent.parent / "src"
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

from config import ARTIFACTS_DIR
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20


def _artifacts_missing() -> bool:
    return not (ARTIFACTS_DIR / "purchase_champion_pipeline.pkl").exists()


@pytest.fixture(scope="session", autouse=True)
def synthetic_artifacts():
    """
    When real artifacts are absent (CI), generate minimal but structurally
    identical synthetic ones so interface-contract tests can run.
    Skipped entirely when real artifacts exist (local dev).
    """
    if not _artifacts_missing():
        return
    ARTIFACTS_DIR.mkdir(parents=True, exist_ok=True)
    for domain, cols in [
        ("purchase", PURCHASE_COLUMNS_29),
        ("transfer", TRANSFER_COLUMNS_20),
    ]:
        X = pd.DataFrame(np.random.randn(250, len(cols)), columns=cols)
        y = np.random.randint(0, 2, 250)
        pipe = Pipeline([("clf", LogisticRegression(max_iter=200))])
        pipe.fit(X, y)
        with open(ARTIFACTS_DIR / f"{domain}_champion_pipeline.pkl", "wb") as f:
            pickle.dump(pipe, f)
        with open(ARTIFACTS_DIR / f"golden_rows_{domain}.json", "w") as f:
            json.dump(X.to_dict(orient="records"), f)
