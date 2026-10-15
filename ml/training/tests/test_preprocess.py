"""
test_preprocess.py — Unit tests for preprocess.py (Member 1, §14 of design v5).

Tests:
    1. haversine_km on a known coordinate pair.
    2. hour / dow / is_night logic on specific timestamps.
    3. Unseen category produces all-zero one-hot row (handle_unknown="ignore").
    4. Stable column order after Pipeline transform.
    5. amt_log = log1p(amt) values.
    6. age calculation from dob and transaction date.

Run with: pytest tests/test_preprocess.py -v
"""

import numpy as np
import pandas as pd
import pytest

# ---------------------------------------------------------------------------
# Test 1 — Haversine on a known pair
# ---------------------------------------------------------------------------

def test_haversine_known_pair():
    """
    New York City (40.7128° N, 74.0060° W) to
    Los Angeles (34.0522° N, 118.2437° W).
    Expected distance ≈ 3,940 km (within 1% tolerance).
    """
    from src.preprocess import haversine_km

    lat1 = np.array([40.7128])
    lon1 = np.array([-74.0060])
    lat2 = np.array([34.0522])
    lon2 = np.array([-118.2437])

    result = haversine_km(lat1, lon1, lat2, lon2)
    assert len(result) == 1
    assert abs(result[0] - 3940.0) / 3940.0 < 0.01, (
        f"Expected ~3940 km, got {result[0]:.1f} km"
    )


def test_haversine_zero_distance():
    """Same point should give distance ≈ 0."""
    from src.preprocess import haversine_km

    dist = haversine_km(
        np.array([48.8566]),
        np.array([2.3522]),
        np.array([48.8566]),
        np.array([2.3522]),
    )
    assert dist[0] < 1e-6


# ---------------------------------------------------------------------------
# Test 2 — Hour / dow / is_night logic
# ---------------------------------------------------------------------------

def test_is_night_true():
    """02:37 should be is_night=1."""
    from src.preprocess import add_engineered_features

    df = _make_minimal_row(trans_datetime="2020-06-21 02:37:00")
    result = add_engineered_features(df)
    assert result["hour"].iloc[0] == 2
    assert result["is_night"].iloc[0] == 1


def test_is_night_false():
    """14:00 should be is_night=0."""
    from src.preprocess import add_engineered_features

    df = _make_minimal_row(trans_datetime="2020-06-21 14:00:00")
    result = add_engineered_features(df)
    assert result["is_night"].iloc[0] == 0


def test_dow_monday():
    """2020-06-22 is a Monday → dow=0."""
    from src.preprocess import add_engineered_features

    df = _make_minimal_row(trans_datetime="2020-06-22 10:00:00")
    result = add_engineered_features(df)
    assert result["dow"].iloc[0] == 0


# ---------------------------------------------------------------------------
# Test 3 — Unseen category → all-zero one-hot row
# ---------------------------------------------------------------------------

def test_unseen_category_handled():
    """
    A category not seen during fit() should produce all-zero one-hot encoding
    (handle_unknown="ignore"). The pipeline should not raise an error.
    """
    pytest.skip("Implement after build_pipeline() is complete.")


# ---------------------------------------------------------------------------
# Test 4 — Stable column order
# ---------------------------------------------------------------------------

def test_stable_column_order():
    """
    Two identical rows must produce the same feature column order.
    Column order must not depend on dict insertion order or categorical values.
    """
    pytest.skip("Implement after build_pipeline() is complete.")


# ---------------------------------------------------------------------------
# Test 5 — amt_log
# ---------------------------------------------------------------------------

def test_amt_log_values():
    """amt_log = log1p(amt). For amt=99.0, log1p(99.0) ≈ 4.6052."""
    from src.preprocess import add_engineered_features
    import math

    df = _make_minimal_row(amt=99.0)
    result = add_engineered_features(df)
    expected = math.log1p(99.0)
    assert abs(result["amt_log"].iloc[0] - expected) < 1e-9


# ---------------------------------------------------------------------------
# Test 6 — Age calculation
# ---------------------------------------------------------------------------

def test_age_calculation():
    """
    dob=1987-04-19, trans_date=2020-06-21 → age ≈ 33.17 years.
    Allow ±0.1 years tolerance.
    """
    from src.preprocess import add_engineered_features

    df = _make_minimal_row(
        trans_datetime="2020-06-21 02:37:00",
        dob="1987-04-19",
    )
    result = add_engineered_features(df)
    assert abs(result["age"].iloc[0] - 33.17) < 0.1


# ---------------------------------------------------------------------------
# Helper
# ---------------------------------------------------------------------------

def _make_minimal_row(
    trans_datetime: str = "2020-06-21 02:37:00",
    amt: float = 850.0,
    dob: str = "1987-04-19",
    lat: float = 33.9659,
    lon: float = -80.9455,
    merch_lat: float = 33.9864,
    merch_lon: float = -81.2007,
    city_pop: int = 149,
    category: str = "grocery_pos",
    gender: str = "F",
) -> pd.DataFrame:
    """Build a one-row DataFrame with minimal Sparkov fields for testing."""
    return pd.DataFrame([{
        "trans_date_trans_time": pd.Timestamp(trans_datetime),
        "amt": amt,
        "dob": pd.Timestamp(dob).date(),
        "lat": lat,
        "long": lon,
        "merch_lat": merch_lat,
        "merch_long": merch_lon,
        "city_pop": city_pop,
        "category": category,
        "gender": gender,
    }])
