"""
conformal_loader.py — MLflow Conformal Loader & Adapter for Vigilia ML Serving (M2).

Architecture: Design 2 / Final v4.2
Role: Provides a decoupled adapter interface between MLflow model artifact storage
and the ConformalConfigValidator / ConformalEvaluator.

Key Principles:
1. Decoupled Extraction: Isolates MLflow artifact/tag access from domain logic.
2. Graceful Degradation: Conformal loading failure NEVER prevents active model loading
   or causes HTTP 500 prediction failures.
3. Strict Non-Invention: Does NOT fabricate or inject default synthetic quantiles in production.
4. Protocol-based Abstraction: Uses MLflowClientProtocol for easy testing with mock clients.
5. Comprehensive Statuses: Explicit status enums for missing artifacts, missing metadata,
   version mismatches, spec mismatches, invalid quantiles, and download failures.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import Enum
import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional, Protocol, Tuple, Union, runtime_checkable

from conformal_config import (
    ConformalCompatibilityError,
    ConformalConfig,
    ConformalConfigValidator,
    ConformalValidationError,
)
from conformal_evaluator import ConformalEvaluator

logger = logging.getLogger("ml_serving.conformal_loader")


class ConformalLoadStatus(str, Enum):
    """Categorical outcome of the Conformal calibration loading process."""
    LOADED = "LOADED"
    NOT_CONFIGURED = "NOT_CONFIGURED"
    ARTIFACT_NOT_FOUND = "ARTIFACT_NOT_FOUND"
    METADATA_MISSING = "METADATA_MISSING"
    INCOMPATIBLE_VERSION = "INCOMPATIBLE_VERSION"
    INCOMPATIBLE_SPEC = "INCOMPATIBLE_SPEC"
    INCOMPATIBLE_FAMILY = "INCOMPATIBLE_FAMILY"
    INVALID_QUANTILE = "INVALID_QUANTILE"
    INVALID_CONFIG = "INVALID_CONFIG"
    DOWNLOAD_FAILED = "DOWNLOAD_FAILED"


@dataclass
class ConformalLoadResult:
    """
    Structured outcome of a conformal loading and validation attempt.

    Attributes:
        status: Categorical status describing the load result.
        evaluator: Initialized ConformalEvaluator if successful, None otherwise.
        config: Validated ConformalConfig if successful, None otherwise.
        error_message: Explanatory error details if load failed or was skipped.
        source: Provenance indicator (e.g. 'artifact:conformal_config.json', 'tags').
    """
    status: ConformalLoadStatus
    evaluator: Optional[ConformalEvaluator] = None
    config: Optional[ConformalConfig] = None
    error_message: Optional[str] = None
    source: Optional[str] = None

    @property
    def is_success(self) -> bool:
        """Returns True if conformal evaluator was successfully loaded and initialized."""
        return self.status == ConformalLoadStatus.LOADED and self.evaluator is not None


@runtime_checkable
class MLflowClientProtocol(Protocol):
    """Structural protocol defining the MLflow client interface used by ConformalLoader."""

    def download_artifacts(self, run_id: str, path: str, dst_path: Optional[str] = None) -> str:
        """Downloads an artifact from a run and returns the local file path."""
        ...

    def get_model_version_by_alias(self, name: str, alias: str) -> Any:
        """Retrieves a registered ModelVersion object by registered alias."""
        ...


class MLflowConformalAdapter:
    """
    Isolated adapter connecting MLflow tracking/registry artifacts to Split Conformal evaluation.

    Encapsulates:
    - Downloading calibration artifacts (e.g. conformal_config.json).
    - Parsing fallback tags from ModelVersion or Run metadata.
    - Validating schema and mathematical bounds via ConformalConfigValidator.
    - Verifying compatibility with active model name, version, and FeatureSpec.
    - Initializing the ConformalEvaluator with the verified quantile.
    """

    DEFAULT_ARTIFACT_NAME: str = "conformal_config.json"
    DEFAULT_TAG_KEY: str = "conformal_config"

    def __init__(
        self,
        artifact_name: str = DEFAULT_ARTIFACT_NAME,
        allow_tags_fallback: bool = True,
        tag_key: str = DEFAULT_TAG_KEY,
    ) -> None:
        self.artifact_name = artifact_name
        self.allow_tags_fallback = allow_tags_fallback
        self.tag_key = tag_key

    def extract_from_artifact(
        self,
        client: Any,
        run_id: str,
        artifact_name: Optional[str] = None,
    ) -> Tuple[Optional[Dict[str, Any]], Optional[ConformalLoadStatus], Optional[str]]:
        """
        Attempts to download and parse the conformal configuration JSON artifact.

        Returns:
            Tuple of (raw_data, failure_status, error_message).
            If successful, raw_data is populated and failure_status is None.
        """
        target_name = artifact_name or self.artifact_name
        try:
            local_path = client.download_artifacts(run_id=run_id, path=target_name)
            if not local_path or not os.path.exists(local_path):
                return (
                    None,
                    ConformalLoadStatus.ARTIFACT_NOT_FOUND,
                    f"Artifact '{target_name}' not found for run '{run_id}' (path does not exist).",
                )

            # If local_path is a directory, look for target file inside
            if os.path.isdir(local_path):
                candidate = os.path.join(local_path, target_name)
                if os.path.exists(candidate):
                    local_path = candidate
                else:
                    return (
                        None,
                        ConformalLoadStatus.ARTIFACT_NOT_FOUND,
                        f"Artifact file '{target_name}' not found inside directory '{local_path}'.",
                    )

            with open(local_path, "r", encoding="utf-8") as f:
                content = f.read()

            if not content.strip():
                return (
                    None,
                    ConformalLoadStatus.METADATA_MISSING,
                    f"Artifact '{target_name}' in run '{run_id}' is empty.",
                )

            try:
                data = json.loads(content)
            except json.JSONDecodeError as decode_err:
                return (
                    None,
                    ConformalLoadStatus.INVALID_CONFIG,
                    f"Artifact '{target_name}' contains malformed JSON: {decode_err}",
                )

            if not isinstance(data, dict):
                return (
                    None,
                    ConformalLoadStatus.INVALID_CONFIG,
                    f"Artifact '{target_name}' JSON root must be an object, got: {type(data).__name__}",
                )

            return data, None, None

        except FileNotFoundError as fnf_err:
            return (
                None,
                ConformalLoadStatus.ARTIFACT_NOT_FOUND,
                f"Artifact '{target_name}' not found in run '{run_id}': {fnf_err}",
            )
        except Exception as exc:
            err_msg = str(exc)
            lower_msg = err_msg.lower()
            if any(
                token in lower_msg
                for token in ("does not exist", "not found", "no such file", "resource_does_not_exist")
            ):
                return (
                    None,
                    ConformalLoadStatus.ARTIFACT_NOT_FOUND,
                    f"Artifact '{target_name}' does not exist for run '{run_id}': {exc}",
                )
            return (
                None,
                ConformalLoadStatus.DOWNLOAD_FAILED,
                f"MLflow artifact download failed for '{target_name}' in run '{run_id}': {exc}",
            )

    def extract_from_tags(
        self,
        tags: Optional[Dict[str, Any]],
    ) -> Tuple[Optional[Dict[str, Any]], Optional[ConformalLoadStatus], Optional[str]]:
        """
        Attempts to parse conformal configuration from MLflow tags.
        Supports either a single serialized JSON tag or individual conformal_* keys.
        """
        if not tags or not isinstance(tags, dict):
            return None, None, None

        # Strategy A: Dedicated JSON tag
        if self.tag_key in tags:
            raw_val = tags[self.tag_key]
            if not raw_val or not str(raw_val).strip():
                return (
                    None,
                    ConformalLoadStatus.METADATA_MISSING,
                    f"Conformal tag '{self.tag_key}' is present but empty.",
                )
            try:
                parsed = json.loads(raw_val) if isinstance(raw_val, str) else raw_val
                if not isinstance(parsed, dict):
                    return (
                        None,
                        ConformalLoadStatus.INVALID_CONFIG,
                        f"Conformal tag '{self.tag_key}' JSON root must be an object.",
                    )
                return parsed, None, None
            except json.JSONDecodeError as decode_err:
                return (
                    None,
                    ConformalLoadStatus.INVALID_CONFIG,
                    f"Conformal tag '{self.tag_key}' contains invalid JSON: {decode_err}",
                )

        # Strategy B: Individual conformal_* tags
        prefix = "conformal_"
        has_prefixed = any(k.startswith(prefix) for k in tags.keys())
        has_bare_quantile = "quantile" in tags

        if has_prefixed or has_bare_quantile:
            data: Dict[str, Any] = {}
            for field_name in (
                "family",
                "model_name",
                "model_version",
                "feature_spec_version",
                "method",
                "alpha",
                "quantile",
                "n",
                "k",
                "calibration_dataset_id",
            ):
                val = tags.get(f"{prefix}{field_name}", tags.get(field_name))
                if val is not None:
                    if field_name in ("n", "k"):
                        try:
                            data[field_name] = int(val)
                        except (ValueError, TypeError):
                            data[field_name] = val
                    elif field_name == "alpha":
                        try:
                            data[field_name] = float(val)
                        except (ValueError, TypeError):
                            data[field_name] = val
                    else:
                        data[field_name] = val

            if data:
                return data, None, None

        return None, None, None

    def validate_and_build(
        self,
        raw_data: Dict[str, Any],
        family: str,
        model_name: str,
        model_version: str,
        feature_spec_version: str,
        source: str,
    ) -> ConformalLoadResult:
        """
        Validates the extracted dictionary and constructs ConformalConfig and ConformalEvaluator.
        """
        if not raw_data or not isinstance(raw_data, dict):
            return ConformalLoadResult(
                status=ConformalLoadStatus.METADATA_MISSING,
                error_message="Conformal calibration metadata is empty or not a dictionary.",
                source=source,
            )

        # 1. Schema & mathematical consistency check via ConformalConfigValidator
        try:
            config = ConformalConfigValidator.validate_dict(raw_data, target_family=family)
        except ConformalValidationError as val_err:
            msg = str(val_err)
            lower_msg = msg.lower()
            if "missing required" in lower_msg:
                status = ConformalLoadStatus.METADATA_MISSING
            elif "quantile" in lower_msg:
                status = ConformalLoadStatus.INVALID_QUANTILE
            else:
                status = ConformalLoadStatus.INVALID_CONFIG

            return ConformalLoadResult(
                status=status,
                error_message=f"Validation failed for conformal config ({source}): {msg}",
                source=source,
            )
        except ConformalCompatibilityError as comp_err:
            return ConformalLoadResult(
                status=ConformalLoadStatus.INCOMPATIBLE_FAMILY,
                error_message=f"Family incompatibility for conformal config ({source}): {comp_err}",
                source=source,
            )
        except Exception as exc:
            return ConformalLoadResult(
                status=ConformalLoadStatus.INVALID_CONFIG,
                error_message=f"Unexpected error validating conformal config ({source}): {exc}",
                source=source,
            )

        # 2. Compatibility validation against active model coordinates
        try:
            ConformalConfigValidator.validate_compatibility(
                config=config,
                expected_family=family,
                expected_model_name=model_name,
                expected_model_version=model_version,
                expected_feature_spec_version=feature_spec_version,
            )
        except ConformalCompatibilityError as comp_err:
            msg = str(comp_err)
            lower_msg = msg.lower()
            if "model_version" in lower_msg:
                status = ConformalLoadStatus.INCOMPATIBLE_VERSION
            elif "feature_spec_version" in lower_msg:
                status = ConformalLoadStatus.INCOMPATIBLE_SPEC
            elif "family" in lower_msg:
                status = ConformalLoadStatus.INCOMPATIBLE_FAMILY
            else:
                status = ConformalLoadStatus.INCOMPATIBLE_VERSION

            return ConformalLoadResult(
                status=status,
                error_message=f"Compatibility check failed for active model ({source}): {msg}",
                source=source,
            )

        # 3. Evaluator instantiation
        try:
            evaluator = ConformalEvaluator(default_quantile=config.quantile)
            return ConformalLoadResult(
                status=ConformalLoadStatus.LOADED,
                evaluator=evaluator,
                config=config,
                source=source,
            )
        except Exception as init_exc:
            return ConformalLoadResult(
                status=ConformalLoadStatus.INVALID_QUANTILE,
                error_message=f"Failed to initialize ConformalEvaluator with quantile {config.quantile}: {init_exc}",
                source=source,
            )

    def load_conformal(
        self,
        family: str,
        model_name: str,
        model_version: str,
        feature_spec_version: str,
        client: Optional[Any] = None,
        model_version_meta: Optional[Any] = None,
        run_id: Optional[str] = None,
        artifact_name: Optional[str] = None,
    ) -> ConformalLoadResult:
        """
        Orchestrates loading conformal calibration for a model family.

        Execution steps:
        1. Resolve run_id from model_version_meta if omitted.
        2. Attempt artifact download from MLflow client.
        3. If artifact absent and tags fallback enabled, inspect ModelVersion and Run tags.
        4. Validate extracted data against schema, mathematical bounds, and active model.
        5. Return ConformalLoadResult (never raises).
        """
        clean_family = str(family).replace("\r", "").replace("\n", "")
        effective_run_id = run_id
        if effective_run_id is None and model_version_meta is not None:
            effective_run_id = getattr(model_version_meta, "run_id", None)

        last_status = ConformalLoadStatus.NOT_CONFIGURED
        last_error: Optional[str] = None

        # Strategy 1: Artifact download via MLflow client
        if client is not None and effective_run_id:
            raw_data, failure_status, err_msg = self.extract_from_artifact(
                client=client,
                run_id=effective_run_id,
                artifact_name=artifact_name,
            )
            if raw_data is not None:
                source = f"artifact:{artifact_name or self.artifact_name}"
                return self.validate_and_build(
                    raw_data=raw_data,
                    family=family,
                    model_name=model_name,
                    model_version=model_version,
                    feature_spec_version=feature_spec_version,
                    source=source,
                )
            if failure_status is not None:
                last_status = failure_status
                last_error = err_msg

        # If download failed with network error, do not fail silently if tags are absent
        # Strategy 2: Tags fallback
        if self.allow_tags_fallback:
            tags: Dict[str, Any] = {}
            if model_version_meta is not None and hasattr(model_version_meta, "tags"):
                tags.update(model_version_meta.tags or {})

            if client is not None and effective_run_id and hasattr(client, "get_run"):
                try:
                    run_obj = client.get_run(effective_run_id)
                    if hasattr(run_obj, "data") and hasattr(run_obj.data, "tags"):
                        tags.update(run_obj.data.tags or {})
                except Exception as run_tag_exc:
                    logger.debug("Non-fatal: could not read run tags for run '%s': %s", effective_run_id, run_tag_exc)

            if tags:
                tag_data, tag_status, tag_err = self.extract_from_tags(tags)
                if tag_data is not None:
                    source = "tags"
                    return self.validate_and_build(
                        raw_data=tag_data,
                        family=family,
                        model_name=model_name,
                        model_version=model_version,
                        feature_spec_version=feature_spec_version,
                        source=source,
                    )
                if tag_status is not None:
                    last_status = tag_status
                    last_error = tag_err

        # If neither artifact nor tags yielded calibration
        if last_error is None:
            last_error = (
                f"No conformal calibration artifact or metadata found for family '{clean_family}' "
                f"(run_id='{effective_run_id or 'none'}')."
            )

        return ConformalLoadResult(
            status=last_status,
            error_message=last_error,
        )
