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
from typing import AsyncGenerator, Dict, List, Optional

from fastapi import BackgroundTasks, Depends, FastAPI, Response, status
from fastapi.responses import JSONResponse
from prometheus_client import Counter, Gauge
from prometheus_fastapi_instrumentator import Instrumentator

from auth import verify_admin_auth
from config import get_settings
from model_loader import (
    acquire_reload_guard,
    explain_transaction,
    get_loaded_model_version,
    get_model_registry,
    is_model_loaded,
    is_startup_complete,
    predict_fraud_probability,
    prepare_inference_input,
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
    ExplanationResponse,
    FeatureSpecMismatchError,
    FeatureSpecValidator,
    FeatureValidationError,
    HealthResponse,
    InferenceError,
    ModelMetadata,
    ModelNotReadyError,
    ModelsListResponse,
    PredictionRequest,
    PredictionResponse,
    ReloadResponse,
    ServingException,
    UnauthorizedError,
    ValidationErrorItem,
    register_error_handlers,
    verify_feature_spec_version,
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


def _handle_prediction(family: str, request: PredictionRequest) -> PredictionResponse:
    """
    Core prediction dispatcher for model families ('purchase', 'transfer').
    Enforces independent model retrieval, feature validation, inference, and error handling.
    """
    registry = get_model_registry()

    # 1. Retrieve active model entry
    entry = registry.get_entry(family)
    if entry is None or entry.model is None or not registry.is_family_loaded(family):
        raise ModelNotReadyError(family=family)

    # 2. Spec version verification against active model if model carries version metadata
    if entry.feature_spec_version is not None:
        verify_feature_spec_version(
            family=family,
            requested_spec_version=request.feature_spec_version,
            active_model_spec_version=entry.feature_spec_version,
        )

    # 3. Input preparation & feature validation via Phase 1 validator
    # Validates full feature vector (29 for purchase, 20 for transfer).
    # Raises FeatureValidationError (422) or FeatureSpecMismatchError (409).
    try:
        input_df = prepare_inference_input(
            family=family,
            features=request.features,
            feature_spec_version=request.feature_spec_version,
        )
    except (FeatureValidationError, FeatureSpecMismatchError):
        raise
    except Exception as exc:
        clean_fam = str(family).replace("\r", "").replace("\n", "")
        logger.error("Feature preparation failed for family '%s': %s", clean_fam, exc)
        raise FeatureValidationError(detail=f"Feature validation failed for family '{clean_fam}': {exc}")

    # 4. Model inference execution
    try:
        fraud_prob, inference_ms = predict_fraud_probability(
            model=entry.model,
            input_df=input_df,
            family=family,
        )
    except ValueError as val_err:
        clean_fam = str(family).replace("\r", "").replace("\n", "")
        logger.error("Invalid model output for family '%s': %s", clean_fam, val_err)
        raise InferenceError(detail=f"Model output validation failed for family '{clean_fam}'.")
    except Exception as exc:
        clean_fam = str(family).replace("\r", "").replace("\n", "")
        logger.error("Inference execution error for family '%s': %s", clean_fam, exc)
        raise InferenceError(detail=f"Inference execution failed for family '{clean_fam}'.")

    # 5. Split Conformal Prediction Evaluation (Online nonconformity check)
    prediction_set: Optional[List[str]] = None
    conformal_status: Optional[str] = None
    is_ambiguous: Optional[bool] = None

    if entry.conformal_evaluator is not None:
        try:
            conf_result = entry.conformal_evaluator.evaluate(fraud_prob)
            prediction_set = conf_result.prediction_set
            conformal_status = conf_result.status.value
            is_ambiguous = conf_result.is_ambiguous
        except Exception as exc:
            clean_fam = str(family).replace("\r", "").replace("\n", "")
            logger.warning(
                "Conformal evaluation failed for family '%s': %s. Returning prediction without conformal set.",
                clean_fam,
                exc,
            )
            prediction_set = None
            conformal_status = None
            is_ambiguous = None

    # 6. Uncertainty & Threshold decisions:
    # Do NOT assume 0.5 threshold, do NOT reuse 0.1948 legacy threshold, do NOT invent uncertainty formula.
    # Unresolved values are safely kept as None (reported as blockers for full decisioning).
    label: Optional[str] = None
    uncertainty: Optional[float] = None

    val = FeatureSpecValidator()
    canonical_version = val.get_canonical_version(family)

    return PredictionResponse(
        request_id=request.request_id,
        label=label,
        fraud_probability=fraud_prob,
        uncertainty=uncertainty,
        model_name=entry.model_name,
        model_version=entry.model_version,
        feature_spec_version=canonical_version,
        inference_ms=inference_ms,
        prediction_set=prediction_set,
        conformal_status=conformal_status,
        is_ambiguous=is_ambiguous,
    )


@app.post(
    "/predict/purchase",
    response_model=PredictionResponse,
    responses={
        200: {"description": "Fraud prediction successfully computed for purchase transaction"},
        409: {"description": "Feature specification version mismatch"},
        422: {"description": "Feature validation error or malformed request payload"},
        503: {"description": "Purchase model not loaded or not ready"},
        500: {"description": "Inference execution error"},
    },
    tags=["Prediction"],
)
def predict_purchase(request: PredictionRequest, response: Response) -> PredictionResponse:
    """
    Executes fraud prediction for card/merchant purchase transactions (Design 2 Section 15.3).
    Validates complete 29-feature vector (purchase-v2) and invokes active purchase model.
    """
    response.headers["X-Request-Id"] = request.request_id
    return _handle_prediction(family="purchase", request=request)


@app.post(
    "/predict/transfer",
    response_model=PredictionResponse,
    responses={
        200: {"description": "Fraud prediction successfully computed for transfer transaction"},
        409: {"description": "Feature specification version mismatch"},
        422: {"description": "Feature validation error or malformed request payload"},
        503: {"description": "Transfer model not loaded or not ready"},
        500: {"description": "Inference execution error"},
    },
    tags=["Prediction"],
)
def predict_transfer(request: PredictionRequest, response: Response) -> PredictionResponse:
    """
    Executes fraud prediction for peer/account transfer transactions (Design 2 Section 15.3).
    Validates complete 20-feature vector (transfer-v2) and invokes active transfer model.
    """
    response.headers["X-Request-Id"] = request.request_id
    return _handle_prediction(family="transfer", request=request)


def _handle_explanation(family: str, request: PredictionRequest) -> ExplanationResponse:
    """
    Common handler for explanation endpoints (Design 2 Section 15.3).
    Validates features, verifies explainer readiness, computes SHAP attributions,
    and returns ExplanationResponse.
    """
    clean_fam = str(family).replace("\r", "").replace("\n", "")

    try:
        entry, base_val, contributions = explain_transaction(
            family=family,
            features=request.features,
            feature_spec_version=request.feature_spec_version,
        )
    except (FeatureValidationError, FeatureSpecMismatchError, ModelNotReadyError):
        raise
    except ValueError as val_err:
        logger.error("Explanation validation failed for family '%s': %s", clean_fam, val_err)
        raise InferenceError(detail=f"Explanation validation failed for family '{clean_fam}': {val_err}")
    except Exception as exc:
        logger.error("Explanation computation error for family '%s': %s", clean_fam, exc)
        raise InferenceError(detail=f"Explanation computation failed for family '{clean_fam}'.")

    return ExplanationResponse(
        request_id=request.request_id,
        model_name=entry.model_name,
        model_version=entry.model_version,
        base_value=base_val,
        contributions=contributions,
    )


@app.post(
    "/explain/purchase",
    response_model=ExplanationResponse,
    responses={
        200: {"description": "SHAP feature contributions successfully computed for purchase transaction"},
        409: {"description": "Feature specification version mismatch"},
        422: {"description": "Feature validation error or malformed request payload"},
        503: {"description": "Purchase model not loaded or not explainable"},
        500: {"description": "Explanation computation error"},
    },
    tags=["Explainability"],
)
def explain_purchase(request: PredictionRequest, response: Response) -> ExplanationResponse:
    """
    Computes SHAP feature attributions for card/merchant purchase transactions (Design 2 Section 15.3).
    Validates complete 29-feature vector (purchase-v2), aggregates OneHotEncoder dummy columns
    belonging to merchant_category, and returns contributions ordered by absolute importance.
    """
    response.headers["X-Request-Id"] = request.request_id
    return _handle_explanation(family="purchase", request=request)


@app.post(
    "/explain/transfer",
    response_model=ExplanationResponse,
    responses={
        200: {"description": "SHAP feature contributions successfully computed for transfer transaction"},
        409: {"description": "Feature specification version mismatch"},
        422: {"description": "Feature validation error or malformed request payload"},
        503: {"description": "Transfer model not loaded or not explainable"},
        500: {"description": "Explanation computation error"},
    },
    tags=["Explainability"],
)
def explain_transfer(request: PredictionRequest, response: Response) -> ExplanationResponse:
    """
    Computes SHAP feature attributions for peer/account transfer transactions (Design 2 Section 15.3).
    Validates complete 20-feature vector (transfer-v2) and returns contributions ordered by absolute importance.
    """
    response.headers["X-Request-Id"] = request.request_id
    return _handle_explanation(family="transfer", request=request)


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
