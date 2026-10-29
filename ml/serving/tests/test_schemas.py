"""
test_schemas.py — Unit tests for strict Pydantic input validation (Contract 1).
"""

from datetime import date, datetime
import pytest
from pydantic import ValidationError
from schemas import TransactionInput


def test_valid_transaction_input_parsing(valid_transaction_payload: dict) -> None:
    tx = TransactionInput(**valid_transaction_payload)
    assert tx.amt == 850.00
    assert tx.category == "grocery_pos"
    assert tx.gender == "F"
    assert tx.city_pop == 149
    assert tx.dob == date(1987, 4, 19)


def test_missing_required_field(valid_transaction_payload: dict) -> None:
    del valid_transaction_payload["amt"]
    with pytest.raises(ValidationError) as exc_info:
        TransactionInput(**valid_transaction_payload)
    errors = exc_info.value.errors()
    assert any(e["loc"] == ("amt",) for e in errors)


def test_invalid_amt_non_positive(valid_transaction_payload: dict) -> None:
    valid_transaction_payload["amt"] = 0.0
    with pytest.raises(ValidationError):
        TransactionInput(**valid_transaction_payload)

    valid_transaction_payload["amt"] = -50.0
    with pytest.raises(ValidationError):
        TransactionInput(**valid_transaction_payload)


def test_invalid_city_pop_non_positive(valid_transaction_payload: dict) -> None:
    valid_transaction_payload["city_pop"] = 0
    with pytest.raises(ValidationError):
        TransactionInput(**valid_transaction_payload)


def test_invalid_latitude_longitude_bounds(valid_transaction_payload: dict) -> None:
    # Latitude out of [-90, 90]
    payload = valid_transaction_payload.copy()
    payload["lat"] = 91.5
    with pytest.raises(ValidationError):
        TransactionInput(**payload)

    payload = valid_transaction_payload.copy()
    payload["merch_lat"] = -91.0
    with pytest.raises(ValidationError):
        TransactionInput(**payload)

    # Longitude out of [-180, 180]
    payload = valid_transaction_payload.copy()
    payload["long"] = 181.0
    with pytest.raises(ValidationError):
        TransactionInput(**payload)

    payload = valid_transaction_payload.copy()
    payload["merch_long"] = -185.0
    with pytest.raises(ValidationError):
        TransactionInput(**payload)


def test_invalid_gender_literal(valid_transaction_payload: dict) -> None:
    valid_transaction_payload["gender"] = "X"
    with pytest.raises(ValidationError):
        TransactionInput(**valid_transaction_payload)


def test_dob_must_be_strictly_before_transaction_date(valid_transaction_payload: dict) -> None:
    # Same date as transaction
    valid_transaction_payload["trans_date_trans_time"] = "2020-06-21T10:00:00"
    valid_transaction_payload["dob"] = "2020-06-21"
    with pytest.raises(ValidationError, match="must be strictly before transaction date"):
        TransactionInput(**valid_transaction_payload)

    # Date after transaction
    valid_transaction_payload["dob"] = "2025-01-01"
    with pytest.raises(ValidationError, match="must be strictly before transaction date"):
        TransactionInput(**valid_transaction_payload)


def test_extra_fields_forbidden_especially_cc_num(valid_transaction_payload: dict) -> None:
    # Attempting to send credit card number (PCI-DSS violation)
    valid_transaction_payload["cc_num"] = "4000123456789010"
    with pytest.raises(ValidationError) as exc_info:
        TransactionInput(**valid_transaction_payload)
    errors = exc_info.value.errors()
    assert any(e["loc"] == ("cc_num",) for e in errors)


def test_forbidden_identifiers_rejected(valid_transaction_payload: dict) -> None:
    # Attempting to send other dropped identifiers
    for forbidden in ["trans_num", "first", "last", "street"]:
        payload = valid_transaction_payload.copy()
        payload[forbidden] = "forbidden_data"
        with pytest.raises(ValidationError) as exc_info:
            TransactionInput(**payload)
        errors = exc_info.value.errors()
        assert any(e["loc"] == (forbidden,) for e in errors)
