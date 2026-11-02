"""
model_loader.py — Multi-Model Registry & Background Loader for Member 2 (ML Serving).

Design 2 Architecture:
1. Thread-safe ModelRegistry tracking independent model families:
   - purchase -> purchase-fraud-model@champion
   - transfer -> transfer-fraud-model@champion
2. Independent loading state, versioning, and error tracking per family.
3. Isolated failure handling: failure in one family never corrupts or unloads the other family.
4. Active model preservation: if a replacement load fails, the existing active model remains serving.
5. In-memory dummy models for both families (strictly permitted only in 'local' and 'test').
6. Non-blocking background loader with bounded exponential backoff.
7. Backward-compatible helper functions for legacy single-model callers.
"""

from dataclasses import dataclass, field
import logging
import os
import threading
import time
from typing import Any, Dict, List, Optional, Tuple

import mlflow
import mlflow.pyfunc
import mlflow.sklearn
from mlflow.tracking import MlflowClient

from config import get_settings

logger = logging.getLogger("ml_serving.model_loader")


@dataclass
class ModelEntry:
    """Represents an active, loaded model family entry in memory."""
    family: str                          # "purchase" or "transfer"
    model_name: str                      # e.g. "purchase-fraud-model"
    model_version: str                   # resolved immutable version, e.g. "1" or "dummy-purchase-v1"
    model_alias: str                     # e.g. "champion"
    model: Any                           # model / pipeline instance
    loaded_at: float = field(default_factory=time.time)
    feature_spec_version: Optional[str] = None


class DummyFraudModel:
    """Base in-memory stub model for local development and test suites."""

    def __init__(self, version: str = "dummy-v1", family: str = "generic") -> None:
        self.version = version
        self.family = family

    def predict_proba(self, X: Any) -> Any:
        import numpy as np
        # Returns [P(legitimate)=0.85, P(fraud)=0.15]
        n_samples = len(X) if hasattr(X, "__len__") else 1
        return np.tile([0.85, 0.15], (n_samples, 1))

    def predict(self, X: Any) -> Any:
        import numpy as np
        n_samples = len(X) if hasattr(X, "__len__") else 1
        return np.zeros(n_samples, dtype=int)


class DummyPurchaseModel(DummyFraudModel):
    """Dummy model for the purchase fraud detection family."""

    def __init__(self, version: str = "dummy-purchase-v1") -> None:
        super().__init__(version=version, family="purchase")


class DummyTransferModel(DummyFraudModel):
    """Dummy model for the transfer fraud detection family."""

    def __init__(self, version: str = "dummy-transfer-v1") -> None:
        super().__init__(version=version, family="transfer")


def create_dummy_model(family: str, version: Optional[str] = None) -> DummyFraudModel:
    """Creates a family-specific dummy model (strictly allowed only in 'local' and 'test' environments)."""
    settings = get_settings()
    if settings.APP_ENV not in ("local", "test"):
        raise RuntimeError(
            f"Security violation: Dummy models are forbidden in '{settings.APP_ENV}'. "
            "Dummy models are strictly restricted to 'local' and 'test' environments."
        )
    if family == "purchase":
        return DummyPurchaseModel(version=version or "dummy-purchase-v1")
    elif family == "transfer":
        return DummyTransferModel(version=version or "dummy-transfer-v1")
    return DummyFraudModel(version=version or f"dummy-{family}-v1", family=family)


# Canonical sample feature payloads for model warm-up (Design 2 Section 15.5)
SAMPLE_PURCHASE_FEATURES: Dict[str, Any] = {
    "amount": 120.0,
    "log_amount": 4.79,
    "merchant_category": "shopping_net",
    "hour_of_day": 23,
    "distance_home_merchant_km": 812.4,
    "txn_count_24h": 6,
}

SAMPLE_TRANSFER_FEATURES: Dict[str, Any] = {
    "amount": 500.0,
    "log_amount": 6.21,
    "dest_account_type": "individual",
    "hour_of_day": 14,
    "transfer_freq_24h": 2,
    "new_recipient": 0,
}


def get_sample_features_for_family(family: str) -> Dict[str, Any]:
    """Returns a canonical sample feature dict for warm-up of the specified family."""
    if family == "purchase":
        return dict(SAMPLE_PURCHASE_FEATURES)
    elif family == "transfer":
        return dict(SAMPLE_TRANSFER_FEATURES)
    return {"amount": 100.0, "hour_of_day": 12}


def validate_model_signature(model: Any, family: str) -> bool:
    """
    Validates model signature and structural interface (Design 2 Section 15.5).
    Verifies that the model artifact is non-null and exposes valid inference callable interfaces.
    If the model artifact contains MLflow signature metadata, validates its structural definition.
    """
    if model is None:
        raise ValueError(f"Model artifact for family '{family}' is None.")

    # Ensure model artifact exposes expected inference callable
    if not hasattr(model, "predict_proba") and not hasattr(model, "predict"):
        raise ValueError(
            f"Model artifact for family '{family}' does not expose a valid prediction interface ('predict_proba' or 'predict')."
        )

    # If the artifact carries MLflow signature metadata, verify it has input specifications
    signature = getattr(model, "metadata", None)
    if signature is not None:
        sig_obj = getattr(signature, "signature", None)
        if sig_obj is not None and hasattr(sig_obj, "inputs"):
            inputs = sig_obj.inputs
            if inputs is not None and hasattr(inputs, "__len__") and len(inputs) == 0:
                raise ValueError(f"Model signature for family '{family}' contains empty input schema.")

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Model signature validation passed for family '%s'.", clean_family)
    return True


def warmup_model(model: Any, family: str) -> None:
    """
    Executes a warm-up inference request on the loaded model using a sample feature payload (Design 2 Section 15.5).
    Ensures graph compilation, memory allocation, and JIT/C-extensions are primed before accepting live traffic.
    """
    if model is None:
        raise ValueError(f"Cannot warm up None model for family '{family}'.")

    sample_features = get_sample_features_for_family(family)
    try:
        import pandas as pd
        payload = pd.DataFrame([sample_features])
    except Exception:
        payload = [sample_features]

    warmed = False
    last_err: Optional[Exception] = None

    if hasattr(model, "predict_proba"):
        try:
            model.predict_proba(payload)
            warmed = True
        except Exception as exc:
            last_err = exc
            try:
                model.predict_proba([sample_features])
                warmed = True
            except Exception:
                pass

    if not warmed and hasattr(model, "predict"):
        try:
            model.predict(payload)
            warmed = True
        except Exception as exc:
            last_err = exc
            try:
                model.predict([sample_features])
                warmed = True
            except Exception:
                pass

    if not warmed:
        if last_err is not None:
            raise RuntimeError(
                f"Warm-up inference failed for model family '{family}': {last_err}"
            ) from last_err
        raise RuntimeError(
            f"Model artifact for family '{family}' does not expose callable inference methods for warm-up."
        )

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Model warm-up completed successfully for family '%s'.", clean_family)


class ModelRegistry:
    """
    Thread-safe registry managing independent model families.
    Tracks state, version, and active entry per family.
    """

    SUPPORTED_FAMILIES: Tuple[str, ...] = ("purchase", "transfer")

    def __init__(self) -> None:
        self._lock = threading.RLock()
        self._entries: Dict[str, Optional[ModelEntry]] = {
            fam: None for fam in self.SUPPORTED_FAMILIES
        }
        self._loading_status: Dict[str, str] = {
            fam: "UNLOADED" for fam in self.SUPPORTED_FAMILIES
        }
        self._last_errors: Dict[str, Optional[str]] = {
            fam: None for fam in self.SUPPORTED_FAMILIES
        }
        self._load_attempts: Dict[str, int] = {
            fam: 0 for fam in self.SUPPORTED_FAMILIES
        }
        # Startup initialization lifecycle tracking (Design 2 Section 15.5)
        self._warmed_up: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }
        self._signature_validated: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }
        self._startup_complete: Dict[str, bool] = {
            fam: False for fam in self.SUPPORTED_FAMILIES
        }

    @property
    def get_supported_families(self) -> Tuple[str, ...]:
        return self.SUPPORTED_FAMILIES

    def _validate_family(self, family: str) -> None:
        if family not in self.SUPPORTED_FAMILIES:
            raise ValueError(
                f"Unknown model family '{family}'. Supported families: {list(self.SUPPORTED_FAMILIES)}"
            )

    def get_entry(self, family: str) -> Optional[ModelEntry]:
        """Returns the current ModelEntry for a family, or None if unloaded."""
        self._validate_family(family)
        with self._lock:
            return self._entries.get(family)

    def get_model(self, family: str) -> Optional[Any]:
        """Returns the active model instance for a family, or None."""
        entry = self.get_entry(family)
        return entry.model if entry else None

    def get_version(self, family: str) -> Optional[str]:
        """Returns the active model version string for a family, or None."""
        entry = self.get_entry(family)
        return entry.model_version if entry else None

    def is_family_loaded(self, family: str) -> bool:
        """Returns True if the specified family is currently loaded and ready."""
        self._validate_family(family)
        with self._lock:
            return self._entries.get(family) is not None

    def are_all_models_loaded(self) -> bool:
        """Returns True only when all supported families are loaded and ready."""
        with self._lock:
            return all(self._entries.get(fam) is not None for fam in self.SUPPORTED_FAMILIES)

    def is_family_warmed_up(self, family: str) -> bool:
        """Returns True if the family has been successfully warmed up with a sample request."""
        self._validate_family(family)
        with self._lock:
            return self._warmed_up.get(family, False)

    def is_family_signature_validated(self, family: str) -> bool:
        """Returns True if the family's model signature has been validated."""
        self._validate_family(family)
        with self._lock:
            return self._signature_validated.get(family, False)

    def is_family_startup_complete(self, family: str) -> bool:
        """
        Returns True only when the family has completed the full 4-step startup sequence:
        1. Alias resolution, 2. Artifact download/load, 3. Signature validation, 4. Warm-up.
        """
        self._validate_family(family)
        with self._lock:
            return (
                self.is_family_loaded(family)
                and self._signature_validated.get(family, False)
                and self._warmed_up.get(family, False)
                and self._startup_complete.get(family, False)
            )

    def are_all_models_started(self) -> bool:
        """Returns True only when all supported families have completed the full startup sequence."""
        with self._lock:
            return all(self.is_family_startup_complete(fam) for fam in self.SUPPORTED_FAMILIES)

    def mark_family_warmed_up(self, family: str, warmed: bool = True) -> None:
        """Sets the warm-up completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._warmed_up[family] = warmed
            if warmed and self._signature_validated.get(family, False) and self.is_family_loaded(family):
                self._startup_complete[family] = True
            elif not warmed:
                self._startup_complete[family] = False

    def mark_family_signature_validated(self, family: str, validated: bool = True) -> None:
        """Sets the signature validation completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._signature_validated[family] = validated
            if validated and self._warmed_up.get(family, False) and self.is_family_loaded(family):
                self._startup_complete[family] = True
            elif not validated:
                self._startup_complete[family] = False

    def mark_startup_complete(self, family: str, complete: bool = True) -> None:
        """Sets the full startup sequence completion state for a family."""
        self._validate_family(family)
        with self._lock:
            self._startup_complete[family] = complete
            self._signature_validated[family] = complete
            self._warmed_up[family] = complete

    def set_entry(self, family: str, entry: ModelEntry) -> None:
        """Atomically updates the model entry for a family."""
        self._validate_family(family)
        if entry.family != family:
            raise ValueError(f"Entry family '{entry.family}' does not match target family '{family}'.")
        with self._lock:
            self._entries[family] = entry
            self._loading_status[family] = "READY"
            self._last_errors[family] = None

    def set_model(
        self,
        family: str,
        model: Any,
        version: str,
        model_name: Optional[str] = None,
        model_alias: Optional[str] = None,
        feature_spec_version: Optional[str] = None,
    ) -> ModelEntry:
        """Convenience method to set a model in the registry."""
        self._validate_family(family)
        settings = get_settings()
        name = model_name or settings.get_model_name_for_family(family)
        alias = model_alias or settings.get_model_alias_for_family(family)
        entry = ModelEntry(
            family=family,
            model_name=name,
            model_version=version,
            model_alias=alias,
            model=model,
            loaded_at=time.time(),
            feature_spec_version=feature_spec_version,
        )
        self.set_entry(family, entry)
        return entry

    def record_failure(self, family: str, error_message: str) -> None:
        """
        Records a loading failure for a family without corrupting any active entry.
        Preserves the currently active model if one was already serving.
        """
        self._validate_family(family)
        with self._lock:
            self._load_attempts[family] += 1
            self._last_errors[family] = error_message
            if self._entries.get(family) is None:
                self._loading_status[family] = "FAILED"
                self._startup_complete[family] = False
                self._warmed_up[family] = False
                self._signature_validated[family] = False
            # If an active entry exists, active model is preserved and not cleared!

    def get_loading_status(self, family: str) -> str:
        """Returns the loading status ('UNLOADED', 'READY', 'FAILED')."""
        self._validate_family(family)
        with self._lock:
            return self._loading_status[family]

    def get_last_error(self, family: str) -> Optional[str]:
        """Returns the last recorded error for a family, or None."""
        self._validate_family(family)
        with self._lock:
            return self._last_errors[family]

    def get_load_attempts(self, family: str) -> int:
        """Returns the number of load attempts recorded for a family."""
        self._validate_family(family)
        with self._lock:
            return self._load_attempts[family]

    def reset(self, family: Optional[str] = None) -> None:
        """Resets registry state for one or all families."""
        with self._lock:
            targets = (family,) if family is not None else self.SUPPORTED_FAMILIES
            for fam in targets:
                self._validate_family(fam)
                self._entries[fam] = None
                self._loading_status[fam] = "UNLOADED"
                self._last_errors[fam] = None
                self._load_attempts[fam] = 0
                self._warmed_up[fam] = False
                self._signature_validated[fam] = False
                self._startup_complete[fam] = False

    def get_status_summary(self) -> Dict[str, Any]:
        """Returns a snapshot of the registry's state for health/monitoring."""
        with self._lock:
            return {
                fam: {
                    "loaded": self._entries.get(fam) is not None,
                    "model_name": self._entries[fam].model_name if self._entries.get(fam) else None,
                    "model_version": self._entries[fam].model_version if self._entries.get(fam) else None,
                    "status": self._loading_status[fam],
                    "attempts": self._load_attempts[fam],
                    "last_error": self._last_errors[fam],
                    "warmed_up": self._warmed_up[fam],
                    "signature_validated": self._signature_validated[fam],
                    "startup_complete": self.is_family_startup_complete(fam),
                }
                for fam in self.SUPPORTED_FAMILIES
            }


# Singleton registry instance
_REGISTRY_INSTANCE: Optional[ModelRegistry] = None
_REGISTRY_LOCK = threading.Lock()


class ReloadGuard:
    """
    Thread-safe guard managing in-progress model reload operations per family.
    Enforces concurrency bounds, prevents duplicate parallel reloads for the same family,
    and permits independent concurrent reloads across different families.
    """

    def __init__(self, supported_families: Tuple[str, ...]) -> None:
        self._supported_families = supported_families
        self._state_lock = threading.Lock()
        self._in_progress: Dict[str, bool] = {fam: False for fam in supported_families}

    def acquire(self, family: str) -> bool:
        """
        Attempts to acquire the reload guard for the specified family without blocking.
        Returns True if acquired (no reload in progress).
        Returns False if a reload is already in progress for this family.
        """
        with self._state_lock:
            if self._in_progress.get(family, False):
                return False
            self._in_progress[family] = True
            return True

    def release(self, family: str) -> None:
        """
        Releases the reload guard for the specified family.
        Always called in a finally block after reload success or failure.
        """
        with self._state_lock:
            self._in_progress[family] = False

    def is_reloading(self, family: str) -> bool:
        """Checks if a reload operation is currently active for the given family."""
        with self._state_lock:
            return self._in_progress.get(family, False)

    def reset(self) -> None:
        """Resets reload guards for all families (used primarily in test cleanup)."""
        with self._state_lock:
            for fam in self._supported_families:
                self._in_progress[fam] = False


_RELOAD_GUARD = ReloadGuard(ModelRegistry.SUPPORTED_FAMILIES)


def acquire_reload_guard(family: str) -> bool:
    """Attempts non-blocking acquisition of the reload guard for the specified family."""
    return _RELOAD_GUARD.acquire(family)


def release_reload_guard(family: str) -> None:
    """Releases the reload guard for the specified family."""
    _RELOAD_GUARD.release(family)


def is_family_reloading(family: str) -> bool:
    """Checks if a reload operation is in progress for the specified family."""
    return _RELOAD_GUARD.is_reloading(family)


def reset_reload_guards() -> None:
    """Resets all reload guards (test teardown helper)."""
    _RELOAD_GUARD.reset()


def get_model_registry() -> ModelRegistry:
    """Returns the singleton ModelRegistry instance."""
    global _REGISTRY_INSTANCE
    if _REGISTRY_INSTANCE is None:
        with _REGISTRY_LOCK:
            if _REGISTRY_INSTANCE is None:
                _REGISTRY_INSTANCE = ModelRegistry()
    return _REGISTRY_INSTANCE


# Background thread control
_STOP_EVENT = threading.Event()
_LOADER_THREAD: Optional[threading.Thread] = None


def get_tracking_uri() -> str:
    """Returns the MLflow tracking URI from configuration."""
    settings = get_settings()
    return settings.MLFLOW_TRACKING_URI


def load_family_model(
    family: str,
    tracking_uri: Optional[str] = None,
) -> Tuple[Any, str]:
    """
    Retrieves and loads the champion model for the specified family (Design 2 Section 15.5).
    Full startup sequence executed per family:
      1. Resolve 'champion' alias to immutable model version.
      2. Download and load model artifact.
      3. Validate model signature.
      4. Warm up model with sample request.
    Updates ModelRegistry atomically on success.
    Preserves active model entry if loading fails.
    """
    registry = get_model_registry()
    settings = get_settings()

    target_name = settings.get_model_name_for_family(family)
    target_alias = settings.get_model_alias_for_family(family)
    uri = tracking_uri or settings.MLFLOW_TRACKING_URI

    # Dummy model fallback for local/test
    if settings.USE_DUMMY_MODEL:
        try:
            clean_family = str(family).replace("\r", "").replace("\n", "")
            logger.info("USE_DUMMY_MODEL is enabled. Initializing in-memory dummy model for family '%s'.", clean_family)
            dummy = create_dummy_model(family)

            # Step 3: Validate model signature
            validate_model_signature(dummy, family)
            registry.mark_family_signature_validated(family, True)

            # Step 4: Warm up model with sample request
            warmup_model(dummy, family)
            registry.mark_family_warmed_up(family, True)

            registry.set_model(
                family=family,
                model=dummy,
                version=dummy.version,
                model_name=target_name,
                model_alias=target_alias,
            )
            registry.mark_startup_complete(family, True)
            return dummy, dummy.version
        except Exception as exc:
            registry.record_failure(family, f"{exc.__class__.__name__}: {str(exc)}")
            raise

    clean_family = str(family).replace("\r", "").replace("\n", "")
    logger.info("Connecting to MLflow Tracking Server at %s for family '%s'...", uri, clean_family)
    mlflow.set_tracking_uri(uri)
    client = MlflowClient(tracking_uri=uri)

    try:
        # Step 1: Resolve alias to immutable version
        model_version_meta = client.get_model_version_by_alias(name=target_name, alias=target_alias)
        version = str(model_version_meta.version)
        clean_family = str(family).replace("\r", "").replace("\n", "")
        logger.info(
            "Resolved model '%s@%s' to version %s for family '%s'",
            target_name,
            target_alias,
            version,
            clean_family,
        )

        # Step 2: Download and load model artifact via proxy
        model_uri = f"models:/{target_name}@{target_alias}"
        logger.info(f"Downloading model bundle from '{model_uri}'...")
        try:
            loaded_model = mlflow.sklearn.load_model(model_uri)
        except Exception:
            # Fallback to pyfunc if sklearn loader encounters differences
            loaded_model = mlflow.pyfunc.load_model(model_uri)

        if loaded_model is None:
            raise ValueError(f"Model artifact downloaded for family '{family}' is None.")

        if not version or not str(version).strip():
            raise ValueError(f"Resolved model version for family '{family}' is empty.")

        # Step 3: Validate model signature
        validate_model_signature(loaded_model, family)
        registry.mark_family_signature_validated(family, True)

        # Step 4: Warm up model with sample request
        warmup_model(loaded_model, family)
        registry.mark_family_warmed_up(family, True)

        # Update registry atomically
        registry.set_model(
            family=family,
            model=loaded_model,
            version=version,
            model_name=target_name,
            model_alias=target_alias,
        )
        registry.mark_startup_complete(family, True)
        return loaded_model, version

    except Exception as exc:
        registry.record_failure(family, f"{exc.__class__.__name__}: {str(exc)}")
        raise


def reload_family_model(
    family: str,
    tracking_uri: Optional[str] = None,
) -> Tuple[Any, str]:
    """
    Triggers an atomic reload of the champion model for the specified family.
    Re-downloads and loads the model into ModelRegistry on success.
    Preserves any active serving model if reloading fails.
    """
    return load_family_model(family=family, tracking_uri=tracking_uri)


def load_all_models(tracking_uri: Optional[str] = None) -> Dict[str, Tuple[Any, str]]:
    """Attempts to load all supported model families. Returns loaded models per family."""
    registry = get_model_registry()
    results = {}
    for family in registry.get_supported_families:
        try:
            model, version = load_family_model(family=family, tracking_uri=tracking_uri)
            results[family] = (model, version)
        except Exception as exc:
            clean_family = str(family).replace("\r", "").replace("\n", "")
            logger.warning("Failed to load model family '%s' (%s).", clean_family, exc.__class__.__name__)
    return results


def _background_loader_loop() -> None:
    """Background worker thread that attempts to load missing models with exponential backoff."""
    settings = get_settings()
    registry = get_model_registry()
    attempt = 0

    while not _STOP_EVENT.is_set():
        for family in registry.get_supported_families:
            if not registry.is_family_startup_complete(family):
                if not acquire_reload_guard(family):
                    continue
                try:
                    load_family_model(family)
                    clean_family = str(family).replace("\r", "").replace("\n", "")
                    logger.info("Model family '%s' successfully loaded and warmed up by background loader.", clean_family)
                except Exception as exc:
                    clean_family = str(family).replace("\r", "").replace("\n", "")
                    logger.warning(
                        "Model load attempt for family '%s' failed (%s).",
                        clean_family,
                        exc.__class__.__name__,
                    )
                finally:
                    release_reload_guard(family)

        if registry.are_all_models_started():
            logger.info("All model families successfully loaded into memory and warmed up by background loader.")
            break

        attempt += 1
        backoff = min(
            settings.MODEL_LOAD_MAX_BACKOFF_SEC,
            settings.MODEL_LOAD_INITIAL_BACKOFF_SEC * (2 ** attempt),
        )
        logger.info(f"Background loader retrying in {backoff:.1f}s (attempt {attempt})...")
        if _STOP_EVENT.wait(timeout=backoff):
            logger.info("Background loader loop stopped.")
            break


def start_background_model_loader() -> None:
    """Spawns the model loader daemon thread during application startup."""
    global _LOADER_THREAD
    _STOP_EVENT.clear()
    registry = get_model_registry()
    if registry.are_all_models_started():
        logger.info("All models already loaded and started. Skipping background loader.")
        return

    _LOADER_THREAD = threading.Thread(
        target=_background_loader_loop,
        name="ModelLoaderThread",
        daemon=True,
    )
    _LOADER_THREAD.start()
    logger.info("Background model loader thread started.")


def stop_background_model_loader(timeout: float = 2.0) -> None:
    """Signals the background model loader thread to terminate."""
    global _LOADER_THREAD
    _STOP_EVENT.set()
    if _LOADER_THREAD and _LOADER_THREAD.is_alive():
        _LOADER_THREAD.join(timeout=timeout)
        logger.info("Background model loader thread terminated.")
    _LOADER_THREAD = None


# Backward-compatible helper functions for legacy single-model callers
def is_model_loaded(family: Optional[str] = None) -> bool:
    """
    Returns True if models are ready in memory.
    If family is specified, checks that family.
    If family is None, returns True only if ALL families are loaded in memory.
    """
    registry = get_model_registry()
    if family is not None:
        return registry.is_family_loaded(family)
    return registry.are_all_models_loaded()


def is_startup_complete(family: Optional[str] = None) -> bool:
    """
    Returns True if startup sequence (alias resolution, load, signature validation, warmup) is complete.
    If family is specified, checks that family.
    If family is None, returns True only if ALL supported families have completed startup.
    """
    registry = get_model_registry()
    if family is not None:
        return registry.is_family_startup_complete(family)
    return registry.are_all_models_started()


def get_loaded_model(family: str = "purchase") -> Optional[Any]:
    """Returns the cached model instance for a family (defaults to 'purchase')."""
    return get_model_registry().get_model(family)


def get_loaded_model_version(family: Optional[str] = None) -> Optional[str]:
    """Returns the version of the specified family, or purchase model version by default."""
    registry = get_model_registry()
    if family is not None:
        return registry.get_version(family)
    return registry.get_version("purchase")


def reset_model_cache(family: Optional[str] = None) -> None:
    """Resets cached models in memory and resets reload guards."""
    get_model_registry().reset(family)
    reset_reload_guards()


def set_cached_model(
    model: Any,
    version: str,
    family: Optional[str] = None,
    mark_started: bool = True,
) -> None:
    """Manually sets the in-memory cached model (used by test fixtures)."""
    registry = get_model_registry()
    if family is not None:
        registry.set_model(family, model, version)
        if mark_started:
            registry.mark_startup_complete(family, True)
    else:
        # Legacy: sets for all supported families
        for fam in registry.get_supported_families:
            registry.set_model(fam, model, version)
            if mark_started:
                registry.mark_startup_complete(fam, True)


def load_champion_model(
    model_name: Optional[str] = None,
    alias: Optional[str] = None,
    tracking_uri: Optional[str] = None,
    family: str = "purchase",
) -> Tuple[Any, str]:
    """Legacy helper loading a champion model (defaults to purchase family)."""
    return load_family_model(family=family, tracking_uri=tracking_uri)
