"""
visualization_loader.py — Thread-safe VisualizationManager and in-memory cache for Data Visualization.

Design 2 / Final v4.2:
- Fully independent of ModelRegistry and prediction path locks.
- Thread-safe in-memory cache with atomic cache update.
- Path Traversal prevention (safe path resolution, no user-supplied filenames).
- In-memory filtering (true_label, predicted_label, error_type, is_ambiguous, is_outlier, cluster_id)
  and pagination.
- RFC 7807 compliant error raising (VisualizationNotFoundError, VisualizationCorruptedError).
"""

import json
import logging
import math
import os
from pathlib import Path
import threading
from typing import Any, Dict, List, Optional, Set, Tuple

from pydantic import ValidationError

from visualization_schemas import (
    ErrorType,
    VALID_FAMILIES,
    VisualizationArtifact,
    VisualizationCorruptedError,
    VisualizationMetadata,
    VisualizationMethod,
    VisualizationMethodNotSupportedError,
    VisualizationNotFoundError,
    VisualizationPoint,
    VisualizationResponse,
    VisualizationValidationError,
)

logger = logging.getLogger("ml_serving.visualization_loader")


class VisualizationManager:
    """
    Singleton-capable manager for loading, validating, caching, filtering,
    and paginating 2D visualization artifacts.
    """

    SUPPORTED_FAMILIES: Set[str] = VALID_FAMILIES
    SUPPORTED_METHODS: Set[str] = {"pca", "tsne"}

    def __init__(self, search_paths: Optional[List[Path]] = None) -> None:
        self._lock = threading.RLock()
        self._cache: Dict[Tuple[str, str], VisualizationArtifact] = {}
        
        # Configure search paths for artifact JSON files
        if search_paths is not None:
            self._search_paths = [p.resolve() for p in search_paths]
        else:
            serving_dir = Path(__file__).resolve().parent
            repo_root = serving_dir.parent.parent
            custom_dir = os.getenv("VISUALIZATION_DATA_DIR") or os.getenv("VISUALIZATION_DIR")
            
            paths: List[Path] = []
            if custom_dir:
                paths.append(Path(custom_dir).resolve())
            paths.extend([
                serving_dir / "artifacts",
                serving_dir.parent / "training" / "artifacts",
                repo_root / "ml" / "training" / "artifacts",
                repo_root / "artifacts",
            ])
            self._search_paths = [p for p in paths if p.exists() or p.is_dir()]

    def clear_cache(self) -> None:
        """Clears all in-memory visualization caches. Thread-safe."""
        with self._lock:
            self._cache.clear()
            logger.info("Visualization cache cleared.")

    def is_cached(self, family: str, method: str) -> bool:
        """Returns True if the specified artifact is cached in memory."""
        with self._lock:
            return (family.strip().lower(), method.strip().lower()) in self._cache

    def set_artifact(self, family: str, method: str, artifact: VisualizationArtifact) -> None:
        """
        Injects a pre-validated artifact into memory (useful for tests and synthetic fixtures).
        Thread-safe and atomic.
        """
        clean_family = family.strip().lower()
        clean_method = method.strip().lower()
        self._validate_family_and_method(clean_family, clean_method)

        if artifact.method.value != clean_method:
            raise VisualizationCorruptedError(
                f"Artifact specifies method '{artifact.method.value}', but was injected under method '{clean_method}'."
            )
        if artifact.family != clean_family:
            raise VisualizationCorruptedError(
                f"Artifact specifies family '{artifact.family}', but was injected under family '{clean_family}'."
            )

        with self._lock:
            self._cache[(clean_family, clean_method)] = artifact
            logger.info("Injected visualization artifact into cache: (%s, %s)", clean_family, clean_method)

    def _validate_family_and_method(self, family: str, method: str) -> None:
        if family not in self.SUPPORTED_FAMILIES:
            raise VisualizationNotFoundError(
                f"Unknown model family '{family}'. Supported families: {sorted(list(self.SUPPORTED_FAMILIES))}."
            )
        if method == "umap":
            raise VisualizationMethodNotSupportedError(method="umap")
        if method not in self.SUPPORTED_METHODS:
            raise VisualizationMethodNotSupportedError(method=method)

    def _resolve_safe_artifact_path(self, family: str, method: str) -> Path:
        """
        Resolves the artifact file safely without accepting user-controlled path strings.
        Prevents Path Traversal by strictly formatting the filename from validated tokens.
        """
        # Strict candidate filenames
        candidate_names = [
            f"{family}_{method}_points.json",
            f"{family}_{method}_visualization.json",
            f"{family}_visualization_points.json",  # fallback if containing both
        ]

        for base_dir in self._search_paths:
            for name in candidate_names:
                candidate = (base_dir / name).resolve()
                # Verify candidate is safely located within base_dir (anti-traversal check)
                try:
                    candidate.relative_to(base_dir)
                except ValueError:
                    continue

                if candidate.is_file():
                    return candidate

        raise VisualizationNotFoundError(
            f"No visualization artifact found for family '{family}' and method '{method}'. "
            f"Searched candidate filenames: {candidate_names} in directories: {[str(p) for p in self._search_paths]}."
        )

    def get_artifact(self, family: str, method: str) -> VisualizationArtifact:
        """
        Retrieves a visualization artifact from in-memory cache or loads it lazily from disk.
        Thread-safe. Atomically updates cache only upon complete validation.
        """
        clean_family = family.strip().lower()
        clean_method = method.strip().lower()
        self._validate_family_and_method(clean_family, clean_method)

        key = (clean_family, clean_method)

        # 1. Fast read from cache
        with self._lock:
            if key in self._cache:
                return self._cache[key]

        # 2. Lazy load from disk (protected by lock to prevent duplicate concurrent disk reads)
        with self._lock:
            # Double check locking pattern
            if key in self._cache:
                return self._cache[key]

            file_path = self._resolve_safe_artifact_path(clean_family, clean_method)
            logger.info("Loading visualization artifact from disk: %s", file_path)

            try:
                with open(file_path, "r", encoding="utf-8") as f:
                    raw_data = json.load(f)
            except json.JSONDecodeError as json_err:
                logger.error("JSON decode error in visualization artifact '%s': %s", file_path, json_err)
                raise VisualizationCorruptedError(
                    f"Visualization artifact '{file_path.name}' contains invalid JSON: {json_err}"
                )
            except Exception as read_err:
                logger.error("Failed to read visualization artifact '%s': %s", file_path, read_err)
                raise VisualizationCorruptedError(
                    f"Failed to read visualization artifact '{file_path.name}': {read_err}"
                )

            # 3. Full Pydantic validation before caching
            try:
                artifact = VisualizationArtifact.model_validate(raw_data)
            except ValidationError as val_err:
                logger.error("Validation error in visualization artifact '%s': %s", file_path, val_err)
                raise VisualizationCorruptedError(
                    f"Visualization artifact '{file_path.name}' failed schema validation: {val_err}"
                )

            # 3b. Verify requested method matches artifact method
            if artifact.method.value != clean_method:
                logger.error(
                    "Method mismatch in artifact '%s': contains method '%s', but requested method is '%s'.",
                    file_path.name,
                    artifact.method.value,
                    clean_method,
                )
                raise VisualizationCorruptedError(
                    f"Artifact '{file_path.name}' contains method '{artifact.method.value}', "
                    f"but requested method was '{clean_method}'."
                )

            # 4. Atomic cache update
            self._cache[key] = artifact
            logger.info("Successfully loaded and cached visualization artifact for (%s, %s).", clean_family, clean_method)
            return artifact

    def filter_and_paginate(
        self,
        family: str,
        method: str,
        page: int = 1,
        page_size: int = 500,
        true_label: Optional[str] = None,
        predicted_label: Optional[str] = None,
        error_type: Optional[str] = None,
        is_ambiguous: Optional[bool] = None,
        is_outlier: Optional[bool] = None,
        cluster_id: Optional[int] = None,
    ) -> VisualizationResponse:
        """
        Retrieves the artifact, applies requested filters in-memory, computes pagination,
        and returns a VisualizationResponse.
        """
        artifact = self.get_artifact(family=family, method=method)

        # Validate pagination arguments
        if page < 1:
            raise VisualizationValidationError("page must be an integer >= 1.")
        if page_size < 10 or page_size > 2000:
            raise VisualizationValidationError("page_size must be between 10 and 2000.")

        # In-memory filtering
        filtered_points: List[VisualizationPoint] = []
        for p in artifact.points:
            if true_label is not None and p.true_label != true_label:
                continue
            if predicted_label is not None and p.predicted_label != predicted_label:
                continue
            if error_type is not None and (p.error_type is None or p.error_type.value != error_type):
                continue
            if is_ambiguous is not None and p.is_ambiguous != is_ambiguous:
                continue
            if is_outlier is not None and p.is_outlier != is_outlier:
                continue
            if cluster_id is not None and p.cluster_id != cluster_id:
                continue

            filtered_points.append(p)

        total_filtered = len(filtered_points)
        total_pages = math.ceil(total_filtered / page_size) if total_filtered > 0 else 0

        start_idx = (page - 1) * page_size
        end_idx = start_idx + page_size
        page_points = filtered_points[start_idx:end_idx] if start_idx < total_filtered else []

        metadata = VisualizationMetadata(
            schema_version=artifact.schema_version,
            family=artifact.family,
            method=artifact.method,
            dataset_name=artifact.dataset_name,
            dataset_split=artifact.dataset_split,
            sampling_strategy=artifact.sampling_strategy,
            model_name=artifact.model_name,
            model_version=artifact.model_version,
            feature_spec_version=artifact.feature_spec_version,
            preprocessing_version=artifact.preprocessing_version,
            evaluation_threshold=artifact.evaluation_threshold,
            threshold_source=artifact.threshold_source,
            generated_at=artifact.generated_at,
            total_points_available=len(artifact.points),
            filtered_points_count=total_filtered,
            page=page,
            page_size=page_size,
            total_pages=total_pages,
            explained_variance_ratio=artifact.explained_variance_ratio,
            cumulative_variance_explained=artifact.cumulative_variance_explained,
            fitted_split=artifact.fitted_split,
            perplexity=artifact.perplexity,
            learning_rate=artifact.learning_rate,
            max_iter=artifact.max_iter,
            random_state=artifact.random_state,
        )

        return VisualizationResponse(metadata=metadata, points=page_points)


# Global singleton instance
_visualization_manager_instance: Optional[VisualizationManager] = None
_instance_lock = threading.Lock()


def get_visualization_manager() -> VisualizationManager:
    """Returns the global VisualizationManager singleton instance."""
    global _visualization_manager_instance
    if _visualization_manager_instance is None:
        with _instance_lock:
            if _visualization_manager_instance is None:
                _visualization_manager_instance = VisualizationManager()
    return _visualization_manager_instance


def reset_visualization_cache() -> None:
    """Convenience helper for tests to reset the global cache."""
    mgr = get_visualization_manager()
    mgr.clear_cache()
