"""
main.py — FastAPI Application for Member 2 (ML Serving).

Milestone 1 Implementation:
- Lifespan management with non-blocking background model loading.
- Fast, non-blocking /health/live probe (HTTP 200).
- /health/ready probe returning HTTP 503 until model is ready, then HTTP 200.
- /health/startup probe returning HTTP 503 until initial model loading is complete, then HTTP 200.
- Prometheus /metrics integration with fintech_ml_prediction_uncertainty gauge.
"""

from contextlib import asynccontextmanager
import logging
from typing import AsyncGenerator, Dict

from fastapi import BackgroundTasks, Depends, FastAPI, Response, status
from fastapi.responses import JSONResponse
from prometheus_client import Counter, Gauge
from prometheus_fastapi_instrumentator import Instrumentator

from auth import verify_admin_auth
from config import get_settings
from model_loader import (
    acquire_reload_guard,
    get_loaded_model_version,
    get_model_registry,
    is_model_loaded,
    is_startup_complete,
    release_reload_guard,
    reload_family_model,
    start_background_model_loader,
    stop_background_model_loader,
)
from schemas import (
    ConflictError,
    ERROR_CODE_INFERENCE_ERROR,
    ERROR_CODE_RELOAD_IN_PROGRESS,
    ERROR_CODE_UNAUTHORIZED,
    ERROR_CODE_VALIDATION_ERROR,
    HealthResponse,
    ModelMetadata,
    ModelsListResponse,
    ReloadResponse,
    ServingException,
    UnauthorizedError,
    ValidationErrorItem,
    register_error_handlers,
)

logger = logging.getLogger("ml_serving.main")

# Prometheus metrics required by Contract v1.0 and Design 2 Section 15.5
uncertainty_metric = Gauge(
    "fintech_ml_prediction_uncertainty",
    "Uncertainty score of the ML prediction",
    ["model_version"],
)

model_load_failures_metric = Counter(
    "model_load_failures_total",
    "Total count of model loading and reloading failures",
    ["model_family"],
)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Lifespan context manager: spawns non-blocking model loader thread on startup."""
    settings = get_settings()
    logger.info(f"Starting {settings.APP_NAME} in environment '{settings.APP_ENV}'...")
    start_background_model_loader()
    yield
    logger.info(f"Shutting down {settings.APP_NAME}...")
    stop_background_model_loader()


settings = get_settings()
app = FastAPI(
    title="Vigilia ML Serving API",
    version=settings.APP_VERSION,
    lifespan=lifespan,
)

# Instrument Prometheus metrics endpoint at /metrics
Instrumentator().instrument(app).expose(app, endpoint="/metrics")

# Register RFC 7807 standardized problem detail exception handlers
register_error_handlers(app)


@app.get("/health/live", response_model=HealthResponse, tags=["Health"])
def liveness() -> HealthResponse:
    """
    Kubernetes liveness probe.
    Returns HTTP 200 immediately to verify process is alive without blocking on model load.
    """
    current_settings = get_settings()
    return HealthResponse(
        status="UP",
        service=current_settings.APP_NAME,
        environment=current_settings.APP_ENV,
        model_loaded=is_model_loaded(),
        model_name=current_settings.ML_MODEL_NAME if is_model_loaded() else None,
        model_version=get_loaded_model_version(),
        detail="Process is healthy.",
    )


@app.get("/health/ready", response_model=HealthResponse, tags=["Health"])
def readiness(response: Response) -> HealthResponse:
    """
    Kubernetes readiness probe.
    Returns HTTP 200 if model is cached and ready to serve traffic;
    Returns HTTP 503 Service Unavailable if model is still loading or MLflow is unreachable.
    """
    current_settings = get_settings()
    loaded = is_model_loaded()

    if not loaded:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(
            status="DOWN",
            service=current_settings.APP_NAME,
            environment=current_settings.APP_ENV,
            model_loaded=False,
            detail="Model is not loaded. Download/background retry in progress.",
        )

    return HealthResponse(
        status="UP",
        service=current_settings.APP_NAME,
        environment=current_settings.APP_ENV,
        model_loaded=True,
        model_name=current_settings.ML_MODEL_NAME,
        model_version=get_loaded_model_version(),
        detail="Model is loaded and ready for inference.",
    )


@app.get("/health/startup", response_model=HealthResponse, tags=["Health"])
def startup(response: Response) -> HealthResponse:
    """
    Kubernetes startup probe (Design 2 Section 15.5).
    Returns HTTP 200 only once initial startup sequence has completed for all model families
    (alias resolution, artifact loading, signature validation, and warm-up);
    Returns HTTP 503 Service Unavailable while startup initialization is still in progress
    or if any startup step has failed.
    """
    current_settings = get_settings()
    started = is_startup_complete()
    loaded = is_model_loaded()

    if not started:
        response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        return HealthResponse(
            status="DOWN",
            service=current_settings.APP_NAME,
            environment=current_settings.APP_ENV,
            model_loaded=loaded,
            detail="Startup initialization in progress. Required models not yet fully loaded and warmed up.",
        )

    return HealthResponse(
        status="UP",
        service=current_settings.APP_NAME,
        environment=current_settings.APP_ENV,
        model_loaded=True,
        model_name=current_settings.ML_MODEL_NAME,
        model_version=get_loaded_model_version(),
        detail="Service startup complete. Required models are loaded and warmed up.",
    )


@app.get("/models", response_model=ModelsListResponse, tags=["Models"])
def get_models() -> ModelsListResponse:
    """
    Exposes loaded model names, versions, aliases, feature specification versions,
    load timestamps, and readiness status for all supported model families (Design 2 Section 15.2).
    """
    current_settings = get_settings()
    registry = get_model_registry()
    models_metadata: Dict[str, ModelMetadata] = {}

    for family in registry.get_supported_families:
        entry = registry.get_entry(family)
        status_str = registry.get_loading_status(family)
        default_name = current_settings.get_model_name_for_family(family)
        models_metadata[family] = ModelMetadata.from_model_entry(
            entry=entry,
            family=family,
            status=status_str,
            default_model_name=default_name,
        )

    return ModelsListResponse(
        models=models_metadata,
        all_loaded=registry.are_all_models_loaded(),
    )


def _background_reload_worker(target_family: str) -> None:
    """Worker task executed in background thread for async reloads."""
    clean_target_family = str(target_family).replace("\r", "").replace("\n", "")
    try:
        reload_family_model(target_family)
        logger.info("Background reload for family '%s' completed successfully.", clean_target_family)
    except Exception as exc:
        model_load_failures_metric.labels(model_family=target_family).inc()
        logger.error(
            "Background reload for family '%s' failed (%s). Active model preserved.",
            clean_target_family,
            exc.__class__.__name__,
        )
    finally:
        release_reload_guard(target_family)


@app.post(
    "/admin/reload/{family}",
    response_model=ReloadResponse,
    responses={
        200: {"description": "Model reloaded synchronously and active in registry"},
        202: {"description": "Model reload scheduled in background"},
        401: {"description": "Missing or invalid administrative credentials"},
        409: {"description": "Model reload already in progress for this family"},
        422: {"description": "Invalid model family"},
        500: {"description": "Model reload failed; active model preserved"},
    },
    tags=["Admin"],
)
def reload_model_family(
    family: str,
    background: bool = False,
    background_tasks: BackgroundTasks = BackgroundTasks(),
    _auth: None = Depends(verify_admin_auth),
) -> Response:
    """
    Administrative endpoint to reload the champion model for a specific family (Design 2 Section 15.5).

    Supported families: 'purchase', 'transfer'.

    Security & Access Control:
    - Protected by `verify_admin_auth` dependency using runtime `ADMIN_API_KEY` (or `ML_ADMIN_API_KEY`).
    - Protected by `ReloadGuard` preventing duplicate concurrent reloads of the same family (HTTP 409).
    - Preserves currently active serving model if replacement download or validation fails (HTTP 500).

    Behavior:
    - If background=False (default): Reloads synchronously. On success, swaps atomically and returns HTTP 200
      with updated ModelMetadata. On failure, preserves active model, increments failure counter, and raises
      RFC 7807 HTTP 500 error.
    - If background=True: Schedules reload in background thread and immediately returns HTTP 202 ACCEPTED.
    """
    registry = get_model_registry()
    current_settings = get_settings()

    # 1. Validate family parameter
    if family not in registry.get_supported_families:
        raise ServingException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            code=ERROR_CODE_VALIDATION_ERROR,
            title="Invalid model family",
            detail=f"Unknown model family '{family}'. Supported families: {list(registry.get_supported_families)}",
            errors=[ValidationErrorItem(field="family", message=f"Must be one of {list(registry.get_supported_families)}")],
        )

    # 2. Acquire per-family concurrency guard to prevent duplicate concurrent reloads
    if not acquire_reload_guard(family):
        raise ConflictError(
            detail=f"A model reload for family '{family}' is already in progress. Duplicate requests are rejected.",
            code=ERROR_CODE_RELOAD_IN_PROGRESS,
        )

    # 3. Asynchronous background reload mode
    if background:
        background_tasks.add_task(_background_reload_worker, family)
        return JSONResponse(
            status_code=status.HTTP_202_ACCEPTED,
            content=ReloadResponse(
                status="ACCEPTED",
                family=family,  # type: ignore[arg-type]
                message=f"Model reload for family '{family}' accepted and scheduled in background.",
                model=None,
            ).model_dump(),
        )

    # 4. Synchronous reload mode
    try:
        reload_family_model(family)
        entry = registry.get_entry(family)
        model_meta = ModelMetadata.from_model_entry(
            entry=entry,
            family=family,
            status=registry.get_loading_status(family),
            default_model_name=current_settings.get_model_name_for_family(family),
        )
        return JSONResponse(
            status_code=status.HTTP_200_OK,
            content=ReloadResponse(
                status="COMPLETED",
                family=family,  # type: ignore[arg-type]
                message=f"Model family '{family}' successfully reloaded.",
                model=model_meta,
            ).model_dump(),
        )
    except Exception as exc:
        model_load_failures_metric.labels(model_family=family).inc()
        clean_family = str(family).replace("\r", "").replace("\n", "")
        logger.error(
            "Synchronous reload for family '%s' failed (%s). Active model preserved.",
            clean_family,
            exc.__class__.__name__,
        )
        raise ServingException(
            status_code=status.HTTP_500_INTERNAL_SERVER_ERROR,
            code=ERROR_CODE_INFERENCE_ERROR,
            title="Model reload failed",
            detail=f"Model reload failed for family '{family}'. Active model preserved.",
        )
    finally:
        release_reload_guard(family)
