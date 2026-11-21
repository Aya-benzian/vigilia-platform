"""
routers/visualization.py — REST endpoints for Data Visualization (PCA, t-SNE).

Design 2 / Final v4.2:
- GET /visualization/purchase/pca
- GET /visualization/purchase/tsne
- GET /visualization/transfer/pca
- GET /visualization/transfer/tsne
- Flexible dispatcher GET /visualization/{family}/{method}
- In-memory pagination and filtering.
- RFC 7807 compliant error responses.
"""

from typing import Literal, Optional

from fastapi import APIRouter, Depends, Query, status

from visualization_loader import VisualizationManager, get_visualization_manager
from visualization_schemas import (
    ErrorType,
    ProblemDetail,
    VisualizationResponse,
)

router = APIRouter(
    prefix="/visualization",
    tags=["Visualization"],
    responses={
        404: {"description": "Visualization artifact or method not found", "model": ProblemDetail},
        422: {"description": "Query parameter validation error", "model": ProblemDetail},
        503: {"description": "Visualization artifact unreadable or corrupted", "model": ProblemDetail},
    },
)


def _handle_visualization(
    family: str,
    method: str,
    page: int,
    page_size: int,
    true_label: Optional[str],
    predicted_label: Optional[str],
    error_type: Optional[str],
    is_ambiguous: Optional[bool],
    is_outlier: Optional[bool],
    cluster_id: Optional[int],
    manager: VisualizationManager,
) -> VisualizationResponse:
    """Common internal dispatch helper for all visualization endpoints."""
    return manager.filter_and_paginate(
        family=family,
        method=method,
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
    )


# ==============================================================================
# Explicit Domain Endpoints (OpenAPI Contract)
# ==============================================================================

@router.get(
    "/purchase/pca",
    response_model=VisualizationResponse,
    summary="Get PCA 2D projections for Purchase transactions",
    description="Returns paginated 2D PCA coordinates and evaluation metadata for the Purchase domain (Sparkov).",
)
def get_purchase_pca(
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(500, ge=10, le=2000, description="Page size (10 to 2000)"),
    true_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by true label"),
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by predicted label"),
    error_type: Optional[ErrorType] = Query(None, description="Filter by error quadrant (TP, TN, FP, FN)"),
    is_ambiguous: Optional[bool] = Query(None, description="Filter by conformal ambiguity"),
    is_outlier: Optional[bool] = Query(None, description="Filter by outlier flag"),
    cluster_id: Optional[int] = Query(None, description="Filter by cluster ID"),
    manager: VisualizationManager = Depends(get_visualization_manager),
) -> VisualizationResponse:
    return _handle_visualization(
        family="purchase",
        method="pca",
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type.value if error_type else None,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
        manager=manager,
    )


@router.get(
    "/purchase/tsne",
    response_model=VisualizationResponse,
    summary="Get t-SNE 2D projections for Purchase transactions",
    description="Returns paginated 2D t-SNE coordinates computed offline for the Purchase domain (Sparkov).",
)
def get_purchase_tsne(
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(500, ge=10, le=2000, description="Page size (10 to 2000)"),
    true_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by true label"),
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by predicted label"),
    error_type: Optional[ErrorType] = Query(None, description="Filter by error quadrant (TP, TN, FP, FN)"),
    is_ambiguous: Optional[bool] = Query(None, description="Filter by conformal ambiguity"),
    is_outlier: Optional[bool] = Query(None, description="Filter by outlier flag"),
    cluster_id: Optional[int] = Query(None, description="Filter by cluster ID"),
    manager: VisualizationManager = Depends(get_visualization_manager),
) -> VisualizationResponse:
    return _handle_visualization(
        family="purchase",
        method="tsne",
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type.value if error_type else None,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
        manager=manager,
    )


@router.get(
    "/transfer/pca",
    response_model=VisualizationResponse,
    summary="Get PCA 2D projections for Transfer transactions",
    description="Returns paginated 2D PCA coordinates and evaluation metadata for the Transfer domain (PaySim).",
)
def get_transfer_pca(
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(500, ge=10, le=2000, description="Page size (10 to 2000)"),
    true_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by true label"),
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by predicted label"),
    error_type: Optional[ErrorType] = Query(None, description="Filter by error quadrant (TP, TN, FP, FN)"),
    is_ambiguous: Optional[bool] = Query(None, description="Filter by conformal ambiguity"),
    is_outlier: Optional[bool] = Query(None, description="Filter by outlier flag"),
    cluster_id: Optional[int] = Query(None, description="Filter by cluster ID"),
    manager: VisualizationManager = Depends(get_visualization_manager),
) -> VisualizationResponse:
    return _handle_visualization(
        family="transfer",
        method="pca",
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type.value if error_type else None,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
        manager=manager,
    )


@router.get(
    "/transfer/tsne",
    response_model=VisualizationResponse,
    summary="Get t-SNE 2D projections for Transfer transactions",
    description="Returns paginated 2D t-SNE coordinates computed offline for the Transfer domain (PaySim).",
)
def get_transfer_tsne(
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(500, ge=10, le=2000, description="Page size (10 to 2000)"),
    true_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by true label"),
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by predicted label"),
    error_type: Optional[ErrorType] = Query(None, description="Filter by error quadrant (TP, TN, FP, FN)"),
    is_ambiguous: Optional[bool] = Query(None, description="Filter by conformal ambiguity"),
    is_outlier: Optional[bool] = Query(None, description="Filter by outlier flag"),
    cluster_id: Optional[int] = Query(None, description="Filter by cluster ID"),
    manager: VisualizationManager = Depends(get_visualization_manager),
) -> VisualizationResponse:
    return _handle_visualization(
        family="transfer",
        method="tsne",
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type.value if error_type else None,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
        manager=manager,
    )


@router.get(
    "/{family}/{method}",
    response_model=VisualizationResponse,
    summary="Generic visualization dispatcher",
    description="Generic endpoint for 2D visualization projections across families ('purchase', 'transfer') and methods ('pca', 'tsne').",
)
def get_visualization_generic(
    family: str,
    method: str,
    page: int = Query(1, ge=1, description="Page number"),
    page_size: int = Query(500, ge=10, le=2000, description="Page size (10 to 2000)"),
    true_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by true label"),
    predicted_label: Optional[Literal["legitimate", "fraud"]] = Query(None, description="Filter by predicted label"),
    error_type: Optional[ErrorType] = Query(None, description="Filter by error quadrant (TP, TN, FP, FN)"),
    is_ambiguous: Optional[bool] = Query(None, description="Filter by conformal ambiguity"),
    is_outlier: Optional[bool] = Query(None, description="Filter by outlier flag"),
    cluster_id: Optional[int] = Query(None, description="Filter by cluster ID"),
    manager: VisualizationManager = Depends(get_visualization_manager),
) -> VisualizationResponse:
    return _handle_visualization(
        family=family,
        method=method,
        page=page,
        page_size=page_size,
        true_label=true_label,
        predicted_label=predicted_label,
        error_type=error_type.value if error_type else None,
        is_ambiguous=is_ambiguous,
        is_outlier=is_outlier,
        cluster_id=cluster_id,
        manager=manager,
    )
