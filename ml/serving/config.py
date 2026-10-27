"""
config.py — Central configuration for Member 2 (ML Serving).

Configures:
- Environment (APP_ENV)
- Design 2 Dual Model coordinates:
  - Purchase: ML_PURCHASE_MODEL_NAME ("purchase-fraud-model"), ML_PURCHASE_MODEL_ALIAS ("champion")
  - Transfer: ML_TRANSFER_MODEL_NAME ("transfer-fraud-model"), ML_TRANSFER_MODEL_ALIAS ("champion")
- Backward-compatible single-model coordinates (ML_MODEL_NAME, ML_MODEL_ALIAS, ML_DECISION_THRESHOLD)
- Dummy model protection (strictly restricted to local and test)
- Fast-fail timeout and backoff policies
"""

import os
from typing import Optional, Tuple


class Settings:
    """Application settings loaded from environment variables."""

    # Supported model families in Design 2
    SUPPORTED_FAMILIES: Tuple[str, ...] = ("purchase", "transfer")

    def __init__(self) -> None:
        self.APP_NAME: str = os.getenv("APP_NAME", "ml-serving")
        self.APP_ENV: str = os.getenv("APP_ENV", "local").lower().strip()
        self.APP_VERSION: str = os.getenv("APP_VERSION", "1.0.0")
        self.PORT: int = int(os.getenv("PORT", "8000"))
        self.LOG_LEVEL: str = os.getenv("LOG_LEVEL", "INFO").upper().strip()

        # MLflow & Model Configuration
        # ASSUMPTION: In container networks this may be http://mlflow:5000. In local dev: http://localhost:5000 or http://127.0.0.1:5000.
        self.MLFLOW_TRACKING_URI: str = os.getenv("MLFLOW_TRACKING_URI", "http://mlflow:5000")

        # Design 2 Dual Model Configuration
        self.ML_PURCHASE_MODEL_NAME: str = os.getenv("ML_PURCHASE_MODEL_NAME", "purchase-fraud-model")
        self.ML_PURCHASE_MODEL_ALIAS: str = os.getenv("ML_PURCHASE_MODEL_ALIAS", "champion")

        self.ML_TRANSFER_MODEL_NAME: str = os.getenv("ML_TRANSFER_MODEL_NAME", "transfer-fraud-model")
        self.ML_TRANSFER_MODEL_ALIAS: str = os.getenv("ML_TRANSFER_MODEL_ALIAS", "champion")

        # Backward-compatible single-model fallback coordinates
        self.ML_MODEL_NAME: str = os.getenv("ML_MODEL_NAME", "fraud-model")
        self.ML_MODEL_ALIAS: str = os.getenv("ML_MODEL_ALIAS", "champion")
        self.ML_DECISION_THRESHOLD: float = float(os.getenv("ML_DECISION_THRESHOLD", "0.1948"))

        # MLflow Fast-Fail Configuration
        self.MLFLOW_HTTP_REQUEST_MAX_RETRIES: int = int(os.getenv("MLFLOW_HTTP_REQUEST_MAX_RETRIES", "1"))
        self.MLFLOW_HTTP_REQUEST_TIMEOUT: int = int(os.getenv("MLFLOW_HTTP_REQUEST_TIMEOUT", "3"))

        # Administrative Security & Authentication Configuration
        # Never hardcoded; supplied via secure runtime environment variable
        self.ADMIN_API_KEY: Optional[str] = (
            os.getenv("ADMIN_API_KEY") or os.getenv("ML_ADMIN_API_KEY") or None
        )

        # Model Loading Retry & Backoff Configuration
        self.MODEL_LOAD_INITIAL_BACKOFF_SEC: float = float(os.getenv("MODEL_LOAD_INITIAL_BACKOFF_SEC", "1.0"))
        self.MODEL_LOAD_MAX_BACKOFF_SEC: float = float(os.getenv("MODEL_LOAD_MAX_BACKOFF_SEC", "30.0"))

        # Dummy Model Flag
        raw_dummy = os.getenv("USE_DUMMY_MODEL", "false").strip().lower()
        self.USE_DUMMY_MODEL: bool = raw_dummy in ("true", "1", "yes", "on")

        # Security check: dummy models are strictly forbidden in production or staging
        if self.USE_DUMMY_MODEL and self.APP_ENV not in ("local", "test"):
            raise ValueError(
                f"Security violation: USE_DUMMY_MODEL cannot be enabled when APP_ENV is '{self.APP_ENV}'. "
                f"Dummy models are strictly restricted to 'local' and 'test' environments."
            )

        # Propagate fast-fail settings to environment so MLflow client libraries respect them
        os.environ["MLFLOW_HTTP_REQUEST_MAX_RETRIES"] = str(self.MLFLOW_HTTP_REQUEST_MAX_RETRIES)
        os.environ["MLFLOW_HTTP_REQUEST_TIMEOUT"] = str(self.MLFLOW_HTTP_REQUEST_TIMEOUT)

    def get_model_name_for_family(self, family: str) -> str:
        """Returns the registered MLflow model name for the given family."""
        if family == "purchase":
            return self.ML_PURCHASE_MODEL_NAME
        elif family == "transfer":
            return self.ML_TRANSFER_MODEL_NAME
        raise ValueError(
            f"Unknown model family '{family}'. Supported families: {list(self.SUPPORTED_FAMILIES)}"
        )

    def get_model_alias_for_family(self, family: str) -> str:
        """Returns the model alias for the given family."""
        if family == "purchase":
            return self.ML_PURCHASE_MODEL_ALIAS
        elif family == "transfer":
            return self.ML_TRANSFER_MODEL_ALIAS
        raise ValueError(
            f"Unknown model family '{family}'. Supported families: {list(self.SUPPORTED_FAMILIES)}"
        )


def get_settings() -> Settings:
    """Factory to create a new Settings instance."""
    return Settings()
