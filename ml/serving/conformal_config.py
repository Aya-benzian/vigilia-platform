"""
conformal_config.py — Conformal Configuration Validator for Vigilia ML Serving (M2).

This module provides an isolated, robust validator for Split Conformal Prediction
configurations (Design 2 / Final v4.2).

Supported Calibration Metadata:
    - family: "purchase" or "transfer"
    - model_name: registered MLflow model name
    - model_version: resolved immutable version
    - feature_spec_version: "purchase-v2" or "transfer-v2"
    - method: "split_conformal"
    - alpha: significance level in (0.0, 1.0), e.g. 0.05 for 95% coverage
    - quantile: nonconformity score threshold in [0.0, 1.0] U {+infinity}
    - n: calibration sample size |D_cal_conf| >= 1
    - k: conformal rank ceil((n + 1) * (1 - alpha))
    - calibration_dataset_id: non-empty identifier for auditability

Mathematical Consistency Rules:
    In Split Conformal Prediction:
        k = ceil((n + 1) * (1 - alpha))
    - If k <= n: quantile q must be in [0.0, 1.0] (finite float).
    - If k > n: the calibration set is insufficient for 1 - alpha coverage,
      requiring q = +infinity.

Serialization of Infinity:
    Standard JSON (RFC 8259) does not support IEEE 754 infinity.
    Supported serialization representations for +infinity:
        - float("inf")
        - string literals: "inf", "+inf", "infinity", "+infinity" (case-insensitive)
    Strictly rejected:
        - negative infinity (-inf, "-inf", "-infinity")
        - NaN (nan, "nan", "NaN")
        - boolean values (True / False)
"""

from __future__ import annotations

from dataclasses import dataclass, field
import json
import math
from typing import Any, Dict, Optional, Set, Union


SUPPORTED_FAMILIES: Set[str] = {"purchase", "transfer"}
SUPPORTED_METHODS: Set[str] = {"split_conformal"}
SUPPORTED_POSITIVE_INF_STRINGS: Set[str] = {"inf", "+inf", "infinity", "+infinity"}
REJECTED_NEGATIVE_INF_STRINGS: Set[str] = {"-inf", "-infinity"}
REJECTED_NAN_STRINGS: Set[str] = {"nan", "+nan", "-nan"}


class ConformalConfigError(ValueError):
    """Base exception for conformal configuration errors."""
    pass


class ConformalValidationError(ConformalConfigError):
    """Raised when configuration attributes fail schema or mathematical validation."""
    pass


class ConformalCompatibilityError(ConformalConfigError):
    """Raised when configuration does not match the active model, family, or feature spec."""
    pass


@dataclass(frozen=True)
class ConformalConfig:
    """
    Immutable validated Split Conformal configuration.
    """
    family: str
    model_name: str
    model_version: str
    feature_spec_version: str
    method: str
    alpha: float
    quantile: float
    n: int
    k: int
    calibration_dataset_id: str
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        """Serializes the configuration into a plain dictionary."""
        q_val: Union[float, str]
        if math.isinf(self.quantile):
            q_val = "Infinity"
        else:
            q_val = self.quantile

        return {
            "family": self.family,
            "model_name": self.model_name,
            "model_version": self.model_version,
            "feature_spec_version": self.feature_spec_version,
            "method": self.method,
            "alpha": self.alpha,
            "quantile": q_val,
            "n": self.n,
            "k": self.k,
            "calibration_dataset_id": self.calibration_dataset_id,
            "metadata": dict(self.metadata),
        }


class ConformalConfigValidator:
    """
    Isolated validator for Split Conformal configurations.
    Enforces strict typing, mathematical rank alignment, and cross-family boundaries.
    """

    @classmethod
    def parse_quantile(cls, value: Any) -> float:
        """
        Parses and strictly validates the conformal quantile threshold.

        Supports:
            - numeric float / int in [0.0, 1.0]
            - float('inf')
            - string literals: "inf", "+inf", "infinity", "+infinity" (case-insensitive)

        Rejects:
            - bool (True/False)
            - NaN / "nan"
            - negative infinity (-inf / "-inf" / "-infinity")
            - numeric values < 0.0 or > 1.0 (unless +inf)
        """
        if isinstance(value, bool):
            raise ConformalValidationError(
                f"Quantile must be a numeric value or valid infinity string, got boolean: {value!r}"
            )

        if isinstance(value, str):
            clean_str = value.strip().lower()
            if clean_str in SUPPORTED_POSITIVE_INF_STRINGS:
                return float("inf")
            if clean_str in REJECTED_NEGATIVE_INF_STRINGS:
                raise ConformalValidationError("Quantile cannot be negative infinity.")
            if clean_str in REJECTED_NAN_STRINGS:
                raise ConformalValidationError("Quantile cannot be NaN.")
            try:
                numeric_val = float(clean_str)
            except ValueError:
                raise ConformalValidationError(f"Invalid quantile string representation: {value!r}")
            return cls.parse_quantile(numeric_val)

        if not isinstance(value, (int, float)):
            raise ConformalValidationError(
                f"Quantile must be numeric or valid string, got {type(value).__name__}: {value!r}"
            )

        if math.isnan(value):
            raise ConformalValidationError("Quantile cannot be NaN.")

        if math.isinf(value):
            if value < 0:
                raise ConformalValidationError("Quantile cannot be negative infinity.")
            return float("inf")

        val_float = float(value)
        if val_float < 0.0 or val_float > 1.0:
            raise ConformalValidationError(
                f"Finite quantile must be in range [0.0, 1.0], got: {val_float}"
            )

        return val_float

    @classmethod
    def validate_alpha(cls, alpha: Any) -> float:
        """Strictly validates significance level alpha in (0.0, 1.0)."""
        if isinstance(alpha, bool):
            raise ConformalValidationError(f"Alpha cannot be a boolean, got: {alpha!r}")
        if not isinstance(alpha, (int, float)):
            raise ConformalValidationError(
                f"Alpha must be numeric float, got {type(alpha).__name__}: {alpha!r}"
            )
        if math.isnan(alpha) or math.isinf(alpha):
            raise ConformalValidationError(f"Alpha must be a finite numeric value, got: {alpha}")
        alpha_float = float(alpha)
        if alpha_float <= 0.0 or alpha_float >= 1.0:
            raise ConformalValidationError(
                f"Alpha must be strictly in range (0.0, 1.0), got: {alpha_float}"
            )
        return alpha_float

    @classmethod
    def validate_n_and_k(cls, n: Any, k: Any, alpha: float) -> tuple[int, int]:
        """
        Strictly validates calibration size n and conformal rank k.
        Enforces mathematical identity: k == ceil((n + 1) * (1 - alpha)).
        """
        if isinstance(n, bool) or not isinstance(n, int):
            raise ConformalValidationError(f"Sample size n must be an integer, got: {n!r}")
        if n < 1:
            raise ConformalValidationError(f"Sample size n must be >= 1, got: {n}")

        if isinstance(k, bool) or not isinstance(k, int):
            raise ConformalValidationError(f"Conformal rank k must be an integer, got: {k!r}")
        if k < 1:
            raise ConformalValidationError(f"Conformal rank k must be >= 1, got: {k}")

        # Mathematical Split Conformal rank formula: k = ceil((n + 1) * (1 - alpha))
        expected_k = math.ceil((n + 1) * (1.0 - alpha))
        if k != expected_k:
            raise ConformalValidationError(
                f"Inconsistent conformal rank k: got {k}, expected ceil((n + 1) * (1 - alpha)) = {expected_k} "
                f"for n={n}, alpha={alpha}"
            )

        return n, k

    @classmethod
    def validate_dict(
        cls,
        data: Dict[str, Any],
        target_family: Optional[str] = None,
    ) -> ConformalConfig:
        """
        Validates raw conformal configuration dictionary.

        Args:
            data: Dictionary containing calibration configuration.
            target_family: Optional target family to check against ("purchase" or "transfer").

        Returns:
            Validated immutable ConformalConfig instance.

        Raises:
            ConformalValidationError: If any attribute is invalid or inconsistent.
            ConformalCompatibilityError: If target_family does not match configuration.
        """
        if not isinstance(data, dict):
            raise ConformalValidationError(f"Configuration must be a dictionary, got: {type(data).__name__}")

        # 1. Required fields presence check
        required_fields = (
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
        )
        missing = [f for f in required_fields if f not in data or data[f] is None]
        if missing:
            raise ConformalValidationError(f"Missing required conformal configuration fields: {missing}")

        # 2. Family validation
        family_raw = data["family"]
        if not isinstance(family_raw, str):
            raise ConformalValidationError(f"Family must be a string, got: {family_raw!r}")
        family = family_raw.strip().lower()
        if family not in SUPPORTED_FAMILIES:
            raise ConformalValidationError(
                f"Unsupported family '{family}'. Must be one of {sorted(SUPPORTED_FAMILIES)}."
            )

        if target_family is not None:
            clean_target = str(target_family).strip().lower()
            if family != clean_target:
                raise ConformalCompatibilityError(
                    f"Configuration family '{family}' is incompatible with target family '{clean_target}'."
                )

        # 3. Model identifiers
        model_name = str(data["model_name"]).strip()
        if not model_name:
            raise ConformalValidationError("model_name cannot be empty.")

        model_version = str(data["model_version"]).strip()
        if not model_version:
            raise ConformalValidationError("model_version cannot be empty.")

        raw_spec = str(data["feature_spec_version"]).strip()
        if not raw_spec:
            raise ConformalValidationError("feature_spec_version cannot be empty.")

        # Support "v2" as alias strictly in the context of the corresponding family
        if raw_spec == "v2":
            if family == "purchase":
                feature_spec_version = "purchase-v2"
            elif family == "transfer":
                feature_spec_version = "transfer-v2"
            else:
                raise ConformalValidationError(
                    f"Feature specification alias 'v2' cannot be resolved for unsupported family '{family}'."
                )
        else:
            feature_spec_version = raw_spec

        # Ensure feature spec matches family convention
        if family == "purchase" and not feature_spec_version.startswith("purchase"):
            raise ConformalValidationError(
                f"Feature specification '{feature_spec_version}' is incompatible with family 'purchase'."
            )
        if family == "transfer" and not feature_spec_version.startswith("transfer"):
            raise ConformalValidationError(
                f"Feature specification '{feature_spec_version}' is incompatible with family 'transfer'."
            )

        # 4. Method validation
        method_raw = str(data["method"]).strip().lower()
        if method_raw not in SUPPORTED_METHODS:
            raise ConformalValidationError(
                f"Unsupported conformal method '{method_raw}'. Must be one of {sorted(SUPPORTED_METHODS)}."
            )

        # 5. Calibration dataset id
        cal_dataset_id = str(data["calibration_dataset_id"]).strip()
        if not cal_dataset_id:
            raise ConformalValidationError("calibration_dataset_id cannot be empty.")

        # 6. Numeric and mathematical parameters
        alpha = cls.validate_alpha(data["alpha"])
        n, k = cls.validate_n_and_k(data["n"], data["k"], alpha)
        quantile = cls.parse_quantile(data["quantile"])

        # Consistency between k, n and infinite quantile
        if k > n and not math.isinf(quantile):
            raise ConformalValidationError(
                f"When rank k ({k}) > sample size n ({n}), quantile must be +infinity, got: {quantile}"
            )
        if k <= n and math.isinf(quantile):
            raise ConformalValidationError(
                f"When rank k ({k}) <= sample size n ({n}), quantile must be finite in [0.0, 1.0], got +infinity."
            )

        metadata = data.get("metadata", {})
        if not isinstance(metadata, dict):
            metadata = {}

        return ConformalConfig(
            family=family,
            model_name=model_name,
            model_version=model_version,
            feature_spec_version=feature_spec_version,
            method=method_raw,
            alpha=alpha,
            quantile=quantile,
            n=n,
            k=k,
            calibration_dataset_id=cal_dataset_id,
            metadata=metadata,
        )

    @classmethod
    def validate_json(
        cls,
        json_payload: str,
        target_family: Optional[str] = None,
    ) -> ConformalConfig:
        """Parses and validates a JSON string payload."""
        if not isinstance(json_payload, str):
            raise ConformalValidationError(
                f"Expected JSON string payload, got: {type(json_payload).__name__}"
            )
        try:
            parsed = json.loads(json_payload)
        except json.JSONDecodeError as err:
            raise ConformalValidationError(f"Malformed JSON configuration payload: {err}")
        return cls.validate_dict(parsed, target_family=target_family)

    @classmethod
    def validate_compatibility(
        cls,
        config: ConformalConfig,
        expected_family: str,
        expected_model_name: Optional[str] = None,
        expected_model_version: Optional[str] = None,
        expected_feature_spec_version: Optional[str] = None,
    ) -> None:
        """
        Validates that a ConformalConfig is compatible with active serving parameters.

        Raises:
            ConformalCompatibilityError: If any coordinate fails to match.
        """
        if config.family != expected_family.strip().lower():
            raise ConformalCompatibilityError(
                f"Conformal family mismatch: configuration is for '{config.family}', active is '{expected_family}'."
            )

        if expected_model_name is not None and config.model_name != expected_model_name:
            raise ConformalCompatibilityError(
                f"Conformal model_name mismatch: configuration is for '{config.model_name}', active is '{expected_model_name}'."
            )

        if expected_model_version is not None and config.model_version != str(expected_model_version):
            raise ConformalCompatibilityError(
                f"Conformal model_version mismatch: configuration is for '{config.model_version}', active is '{expected_model_version}'."
            )

        if expected_feature_spec_version is not None:
            expected_spec = expected_feature_spec_version
            if expected_spec == "v2":
                expected_spec = f"{expected_family}-v2"
            if config.feature_spec_version != expected_spec:
                raise ConformalCompatibilityError(
                    f"Conformal feature_spec_version mismatch: configuration is for '{config.feature_spec_version}', active is '{expected_feature_spec_version}'."
                )
