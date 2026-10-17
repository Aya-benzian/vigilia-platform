"""
test_schema.py — Unit tests for schema.py / Pydantic validation (Member 1, §14 of design v5).

Tests:
    1. Valid input passes without error.
    2. Negative amount raises ValidationError.
    3. Invalid gender raises ValidationError.
    4. dob after transaction date raises ValidationError.
    5. Out-of-range lat/long raises ValidationError.

Run with: pytest tests/test_schema.py -v
"""

import pytest
from datetime import date, datetime
from pydantic import ValidationError

from src.schema import TransactionInput


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

VALID_INPUT = {
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


# ---------------------------------------------------------------------------
# Test 1 — Valid input
# ---------------------------------------------------------------------------

def test_valid_input_passes():
    tx = TransactionInput(**VALID_INPUT)
    assert tx.amt == 850.00
    assert tx.gender == "F"


# ---------------------------------------------------------------------------
# Test 2 — Negative amount
# ---------------------------------------------------------------------------

def test_negative_amount_rejected():
    bad = {**VALID_INPUT, "amt": -10.0}
    with pytest.raises(ValidationError) as exc_info:
        TransactionInput(**bad)
    assert "amt" in str(exc_info.value).lower()


# ---------------------------------------------------------------------------
# Test 3 — Invalid gender
# ---------------------------------------------------------------------------

def test_invalid_gender_rejected():
    bad = {**VALID_INPUT, "gender": "X"}
    with pytest.raises(ValidationError):
        TransactionInput(**bad)


# ---------------------------------------------------------------------------
# Test 4 — dob after transaction date
# ---------------------------------------------------------------------------

def test_dob_after_transaction_rejected():
    bad = {**VALID_INPUT, "dob": "2025-01-01"}  # future DOB
    with pytest.raises(ValidationError) as exc_info:
        TransactionInput(**bad)
    assert "dob" in str(exc_info.value).lower()


# ---------------------------------------------------------------------------
# Test 5 — Out-of-range lat/long
# ---------------------------------------------------------------------------

def test_lat_out_of_range_rejected():
    bad = {**VALID_INPUT, "lat": 95.0}  # >90 degrees
    with pytest.raises(ValidationError):
        TransactionInput(**bad)


def test_long_out_of_range_rejected():
    bad = {**VALID_INPUT, "long": 200.0}  # >180 degrees
    with pytest.raises(ValidationError):
        TransactionInput(**bad)


# ---------------------------------------------------------------------------
# Test 6 — Zero city_pop rejected
# ---------------------------------------------------------------------------

def test_zero_city_pop_rejected():
    bad = {**VALID_INPUT, "city_pop": 0}
    with pytest.raises(ValidationError):
        TransactionInput(**bad)
