"""
Vigilia Platform — Model Loader Helper for Member 2 (ML Serving)

This module demonstrates the recommended pattern for retrieving registered models
from the MLflow Model Registry via the '@champion' alias.

Key Design Principles:
1. Startup Cache: The model is fetched from MLflow and loaded into memory ONCE during application
   startup (e.g. in FastAPI lifespan/startup event). Individual /predict requests evaluate the
   in-memory model with zero network latency.
2. Artifact Proxy Decoupling: Communicates purely over HTTP with MLflow Tracking Server (http://mlflow:5000).
   The serving container does not need direct access to the database or storage volume.
3. Graceful Fallback: If MLflow is unreachable or no champion model exists, handles errors cleanly
   so the service can either fail readiness (/health/ready -> 503) or fall back to safe rules.
"""

import os
import logging
from typing import Optional, Any
import mlflow
from mlflow.tracking import MlflowClient

logger = logging.getLogger("ml_serving.model_loader")

_CACHED_MODEL: Optional[Any] = None
_CACHED_MODEL_VERSION: Optional[str] = None


def get_tracking_uri() -> str:
    return os.getenv("MLFLOW_TRACKING_URI", "http://mlflow:5000")


def load_champion_model(
    model_name: str = "smoke_test_champion_model",
    alias: str = "champion",
    tracking_uri: Optional[str] = None
) -> tuple[Any, str]:
    """
    Retrieves the model registered under the given alias from MLflow Model Registry.
    
    Returns:
        tuple[Any, str]: (loaded_model, resolved_version)
    """
    global _CACHED_MODEL, _CACHED_MODEL_VERSION

    uri = tracking_uri or get_tracking_uri()
    logger.info(f"Connecting to MLflow Tracking Server at {uri}...")
    mlflow.set_tracking_uri(uri)
    client = MlflowClient(tracking_uri=uri)

    try:
        # Resolve alias to verify metadata
        model_version_meta = client.get_model_version_by_alias(name=model_name, alias=alias)
        version = model_version_meta.version
        logger.info(f"Resolved model '{model_name}@{alias}' to version {version}")

        # Load model using pyfunc
        model_uri = f"models:/{model_name}@{alias}"
        logger.info(f"Downloading and loading model bundle from '{model_uri}'...")
        loaded_model = mlflow.pyfunc.load_model(model_uri)

        _CACHED_MODEL = loaded_model
        _CACHED_MODEL_VERSION = version
        return loaded_model, version

    except Exception as e:
        logger.error(f"Failed to load champion model '{model_name}@{alias}' from MLflow: {e}")
        raise


def get_loaded_model() -> Optional[Any]:
    """Returns the in-memory cached model for inference."""
    return _CACHED_MODEL


def get_loaded_model_version() -> Optional[str]:
    """Returns the version of the currently cached model."""
    return _CACHED_MODEL_VERSION


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    print("=== Testing Standalone Champion Model Retrieval (M2) ===")
    target_model = os.getenv("ML_MODEL_NAME", "smoke_test_champion_model")
    target_uri = os.getenv("MLFLOW_TRACKING_URI", "http://localhost:5000")

    try:
        model, ver = load_champion_model(model_name=target_model, tracking_uri=target_uri)
        print(f"[+] Successfully loaded champion model '{target_model}' (version: {ver})")
        # Quick dummy inference test
        import numpy as np
        test_input = np.array([[1.0], [2.0]])
        output = model.predict(test_input)
        print(f"[+] Verification prediction output: {output}")
        print("=== Champion Model Retrieval Test Passed! ===")
    except Exception as exc:
        print(f"[!] Model retrieval test failed: {exc}")
        import sys
        sys.exit(1)
