"""
schema.py — Pydantic v2 input schema for Contract 1 (Member 1 → Member 2).

This module defines the exact structure of the raw transaction input that
the ML serving API (Member 2 / FastAPI) must accept.

Why it lives here:
    Member 1 defines the schema because Member 1 knows the raw fields,
    their types, and their valid ranges. Member 2 imports this file directly
    so there is no mismatch between what Training produces and what Serving expects.
    This is Contract 1 (design v5 §5).

How Member 2 uses it:
    from schema import TransactionInput
    @app.post("/predict")
    def predict(tx: TransactionInput): ...

Version:
    Schema version is tracked in SCHEMA_VERSION and logged in MLflow.
    Any breaking change (field removed, type changed) must bump the version.

Notes on personal data:
    dob, lat, long, city_pop, job, gender together can identify a cardholder.
    The service must receive only what the model strictly needs.
    This is documented in the report (§5 of design v5).
"""

from datetime import date, datetime
from typing import Literal, Optional

from pydantic import BaseModel, Field, model_validator

SCHEMA_VERSION = "1.0"   # bump on any breaking change


class TransactionInput(BaseModel):
    """
    Raw transaction fields supplied by Member 3 (Transaction Service)
    to Member 2 (ML Serving API) for a single fraud prediction.

    Field names, types, and ranges must be verified against the real Sparkov
    CSV on day 1 and agreed with Members 3 and 4 before finalizing.

    All validation runs automatically on instantiation.
    If a field fails validation, Pydantic raises a ValidationError with a
    clear message — Member 2 returns HTTP 422 to Member 3.
    """

    # Transaction basics
    trans_date_trans_time: datetime = Field(
        ...,
        description="Transaction timestamp (ISO 8601). Used to derive hour, dow, is_night.",
    )
    amt: float = Field(
        ...,
        gt=0,
        description="Transaction amount in USD. Must be strictly positive.",
    )

    # Merchant
    category: str = Field(
        ...,
        description=(
            "Merchant category. "
            "Unknown categories are handled with handle_unknown='ignore' in the pipeline."
        ),
    )
    merch_lat: float = Field(
        ...,
        ge=-90,
        le=90,
        description="Merchant latitude in decimal degrees.",
    )
    merch_long: float = Field(
        ...,
        ge=-180,
        le=180,
        description="Merchant longitude in decimal degrees.",
    )

    # Cardholder
    gender: Literal["M", "F"] = Field(
        ...,
        description="Cardholder gender. Verify allowed values on real Sparkov data.",
    )
    dob: date = Field(
        ...,
        description="Cardholder date of birth. Used to compute age at transaction time.",
    )
    lat: float = Field(
        ...,
        ge=-90,
        le=90,
        description="Cardholder home latitude in decimal degrees.",
    )
    long: float = Field(
        ...,
        ge=-180,
        le=180,
        description="Cardholder home longitude in decimal degrees.",
    )
    city_pop: int = Field(
        ...,
        gt=0,
        description="Population of the cardholder's city. Used as city_pop_log.",
    )

    # [NICE] Optional fields — only if frequency/target encoding is used in v1
    # merchant: Optional[str] = Field(None, description="Merchant name (optional).")
    # job: Optional[str] = Field(None, description="Cardholder job (optional).")
    # state: Optional[str] = Field(None, description="State (optional).")

    model_config = {
        "json_schema_extra": {
            "example": {
                "trans_date_trans_time": "2020-06-21T02:37:00",
                "amt": 850.00,
                "category": "grocery_pos",
                "merch_lat": 33.986391,
                "merch_long": -81.200714,
                "gender": "F",
                "dob": "1987-04-19",
                "lat": 33.9659,
                "long": -80.9455,
                "city_pop": 149,
            }
        }
    }

    @model_validator(mode="after")
    def check_dob_before_transaction(self) -> "TransactionInput":
        """Cardholder must be born before the transaction date."""
        if self.dob >= self.trans_date_trans_time.date():
            raise ValueError("dob must be before trans_date_trans_time")
        return self


class PredictionOutput(BaseModel):
    """
    Structured prediction returned by Member 2's /predict endpoint.
    Defined here for documentation and Contract 1 completeness.
    Member 2 owns the actual implementation.

    Contract 10: model_version uses the fraud-model-vX naming convention.
    """

    prediction: Literal["FRAUD", "LEGITIMATE"] = Field(
        ..., description="Model prediction class."
    )
    probability: float = Field(
        ..., ge=0.0, le=1.0,
        description="Calibrated probability of FRAUD (output of calibrated pipeline).",
    )
    uncertainty: float = Field(
        ..., ge=0.0, le=1.0,
        description="Uncertainty score. Method owned by Member 2.",
    )
    model_version: str = Field(
        ...,
        description="Model version string, e.g. 'fraud-model-v1'. Contract 10.",
    )
    explanation: dict = Field(
        default_factory=dict,
        description="SHAP feature contributions. Structure owned by Member 2 (Contract 4).",
    )
