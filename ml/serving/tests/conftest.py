"""
conftest.py — Pytest configuration and shared fixtures for Member 2 tests.
Sets APP_ENV=test before importing serving modules.
"""

import os
import sys
from pathlib import Path
from typing import Generator, Dict, Any

# Ensure ml/serving directory is in python path
SERVING_DIR = Path(__file__).resolve().parent.parent
if str(SERVING_DIR) not in sys.path:
    sys.path.insert(0, str(SERVING_DIR))

# Mandatory safety rule: test suite must run under APP_ENV=test
os.environ["APP_ENV"] = "test"
os.environ["USE_DUMMY_MODEL"] = "false"
os.environ["MODEL_LOAD_INITIAL_BACKOFF_SEC"] = "0.01"
os.environ["MODEL_LOAD_MAX_BACKOFF_SEC"] = "0.05"
os.environ["MLFLOW_TRACKING_URI"] = "http://127.0.0.1:5000"
os.environ["MLFLOW_HTTP_REQUEST_MAX_RETRIES"] = "0"
os.environ["MLFLOW_HTTP_REQUEST_TIMEOUT"] = "1"

import pytest
import httpx

# Compatibility patch for Starlette TestClient with httpx >= 0.28.0
_orig_httpx_init = httpx.Client.__init__
def _compat_httpx_init(self, *args, **kwargs):
    kwargs.pop("app", None)
    return _orig_httpx_init(self, *args, **kwargs)
httpx.Client.__init__ = _compat_httpx_init

from fastapi.testclient import TestClient
from model_loader import reset_model_cache, stop_background_model_loader


@pytest.fixture(autouse=True)
def clean_environment_and_model_state() -> Generator[None, None, None]:
    """Ensures each test starts with a clean model cache and stops background threads."""
    reset_model_cache()
    yield
    stop_background_model_loader()
    reset_model_cache()


@pytest.fixture
def valid_transaction_payload() -> Dict[str, Any]:
    """Returns a valid 10-feature raw transaction dictionary matching Contract 1."""
    return {
        "trans_date_trans_time": "2020-06-21T02:37:00",
        "amt": 850.00,
        "category": "grocery_pos",
        "gender": "F",
        "dob": "1987-04-19",
        "lat": 33.9659,
        "long": -80.9455,
        "city_pop": 149,
        "merch_lat": 33.986391,
        "merch_long": -81.200714,
    }


@pytest.fixture
def test_client() -> Generator[TestClient, None, None]:
    """Returns a TestClient with lifespan context."""
    from main import app
    with TestClient(app) as client:
        yield client


@pytest.fixture
def admin_api_key(monkeypatch: pytest.MonkeyPatch) -> str:
    """Fixture providing a test admin API key configured in environment."""
    key = "test-vigilia-admin-secret-key"
    monkeypatch.setenv("ADMIN_API_KEY", key)
    return key


@pytest.fixture
def admin_auth_headers(admin_api_key: str) -> Dict[str, str]:
    """Fixture providing valid Authorization bearer headers for admin endpoints."""
    return {"Authorization": f"Bearer {admin_api_key}"}
