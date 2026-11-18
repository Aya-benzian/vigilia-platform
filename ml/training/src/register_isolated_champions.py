"""
register_isolated_champions.py — Register isolated domain champion models into MLflow.

Supervisor Directive: strict domain isolation (zero cross-domain features).
    - purchase-fraud-model : 29 pure purchase features (Sparkov)
    - transfer-fraud-model : 20 pure transfer features (PaySim)
    - fraud-model          : backward compatibility alias pointing to purchase champion

Models are registered with @champion alias so that ml/serving can load them via:
    mlflow.sklearn.load_model("models:/purchase-fraud-model@champion")
    mlflow.sklearn.load_model("models:/transfer-fraud-model@champion")
    mlflow.sklearn.load_model("models:/fraud-model@champion")
"""

import json
import logging
import pickle
import sys
import tempfile
import shutil
from pathlib import Path

# Fix console encoding on Windows to avoid UnicodeEncodeError with MLflow emojis
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass
if hasattr(sys.stderr, "reconfigure"):
    try:
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

SRC_DIR = Path(__file__).resolve().parent
if str(SRC_DIR) not in sys.path:
    sys.path.insert(0, str(SRC_DIR))

import mlflow
import mlflow.sklearn
from mlflow import MlflowClient

from config import MLFLOW_TRACKING_URI, ARTIFACTS_DIR, SEED
from cross_domain_features import PURCHASE_COLUMNS_29, TRANSFER_COLUMNS_20

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s | %(message)s",
)
logger = logging.getLogger("register_isolated")

PURCHASE_MODEL_NAME = "purchase-fraud-model"
TRANSFER_MODEL_NAME = "transfer-fraud-model"
LEGACY_MODEL_NAME   = "fraud-model"
CHAMPION_ALIAS      = "champion"

PURCHASE_PKL   = ARTIFACTS_DIR / "purchase_champion_pipeline.pkl"
TRANSFER_PKL   = ARTIFACTS_DIR / "transfer_champion_pipeline.pkl"
PURCHASE_BENCH = ARTIFACTS_DIR / "purchase_benchmark_summary.json"
TRANSFER_BENCH = ARTIFACTS_DIR / "transfer_benchmark_summary.json"
FEATURE_SPEC   = ARTIFACTS_DIR / "feature_spec.json"


def _load_pkl(path: Path):
    if not path.exists():
        raise FileNotFoundError(f"Pipeline not found: {path}")
    with open(path, "rb") as fh:
        return pickle.load(fh)


def _load_bench(path: Path) -> dict:
    if not path.exists():
        return {}
    with open(path, "r", encoding="utf-8") as fh:
        return json.load(fh)


def _register_pipeline(
    pipeline,
    model_name: str,
    metrics: dict,
    feature_cols: list,
    domain: str,
) -> str:
    client = MlflowClient()

    card = {
        "schema_version": "1.0",
        "domain": domain,
        "registered_name": model_name,
        "champion_alias": CHAMPION_ALIAS,
        "feature_count": len(feature_cols),
        "feature_columns": feature_cols,
        "performance": {
            k: v for k, v in metrics.items()
            if isinstance(v, (int, float))
        },
        "dataset": "Sparkov" if domain == "purchase" else "PaySim",
        "seed": SEED,
        "note": "Domain-isolated — zero cross-domain features (supervisor directive).",
    }

    card_path = ARTIFACTS_DIR / f"{domain}_model_card.json"
    with open(card_path, "w", encoding="utf-8") as fh:
        json.dump(card, fh, indent=2)

    with mlflow.start_run(run_name=f"register_{model_name}") as run:
        for k, v in metrics.items():
            if isinstance(v, (int, float)):
                try:
                    mlflow.log_metric(k, float(v))
                except Exception:
                    pass

        mlflow.log_param("domain", domain)
        mlflow.log_param("model_name", model_name)
        mlflow.log_param("feature_count", len(feature_cols))
        mlflow.log_param("seed", SEED)

        tmp_dir = tempfile.mkdtemp()
        model_local_path = Path(tmp_dir) / "model"
        try:
            if card_path.exists():
                mlflow.log_artifact(str(card_path), artifact_path="metadata")
            if FEATURE_SPEC.exists():
                mlflow.log_artifact(str(FEATURE_SPEC), artifact_path="metadata")

            conf_file = ARTIFACTS_DIR / f"{domain}_conformal_config.json"
            if conf_file.exists():
                tmp_conf = Path(tmp_dir) / "conformal_config.json"
                shutil.copyfile(conf_file, tmp_conf)
                mlflow.log_artifact(str(tmp_conf), artifact_path="metadata")

            mlflow.sklearn.save_model(
                sk_model=pipeline,
                path=str(model_local_path),
                serialization_format=mlflow.sklearn.SERIALIZATION_FORMAT_CLOUDPICKLE,
            )
            mlflow.log_artifacts(str(model_local_path), artifact_path="model")
        finally:
            shutil.rmtree(tmp_dir, ignore_errors=True)

        run_id = run.info.run_id

    model_version_obj = mlflow.register_model(
        model_uri=f"runs:/{run_id}/model",
        name=model_name,
    )
    version = str(model_version_obj.version)

    client.set_registered_model_alias(
        name=model_name,
        alias=CHAMPION_ALIAS,
        version=version,
    )

    logger.info(
        f"Registered '{model_name}' v{version} with alias '@{CHAMPION_ALIAS}'"
    )
    return version


def main():
    logger.info(f"Target MLflow Tracking URI: {MLFLOW_TRACKING_URI}")
    mlflow.set_tracking_uri(MLFLOW_TRACKING_URI)

    # 1. Purchase model (29 features)
    logger.info("Registering PURCHASE champion (29 features)...")
    p_pipe = _load_pkl(PURCHASE_PKL)
    p_metrics = _load_bench(PURCHASE_BENCH)
    _register_pipeline(
        pipeline=p_pipe,
        model_name=PURCHASE_MODEL_NAME,
        metrics=p_metrics,
        feature_cols=PURCHASE_COLUMNS_29,
        domain="purchase",
    )

    # 2. Transfer model (20 features)
    logger.info("Registering TRANSFER champion (20 features)...")
    t_pipe = _load_pkl(TRANSFER_PKL)
    t_metrics = _load_bench(TRANSFER_BENCH)
    _register_pipeline(
        pipeline=t_pipe,
        model_name=TRANSFER_MODEL_NAME,
        metrics=t_metrics,
        feature_cols=TRANSFER_COLUMNS_20,
        domain="transfer",
    )

    # 3. Legacy fraud-model alias
    logger.info("Registering legacy 'fraud-model' alias for compatibility...")
    _register_pipeline(
        pipeline=p_pipe,
        model_name=LEGACY_MODEL_NAME,
        metrics=p_metrics,
        feature_cols=PURCHASE_COLUMNS_29,
        domain="purchase",
    )

    logger.info("=" * 60)
    logger.info("All models registered successfully in MLflow!")
    logger.info(f"  Purchase: models:/{PURCHASE_MODEL_NAME}@{CHAMPION_ALIAS}")
    logger.info(f"  Transfer: models:/{TRANSFER_MODEL_NAME}@{CHAMPION_ALIAS}")
    logger.info(f"  Legacy:   models:/{LEGACY_MODEL_NAME}@{CHAMPION_ALIAS}")
    logger.info("=" * 60)


if __name__ == "__main__":
    main()

