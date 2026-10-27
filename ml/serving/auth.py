"""
auth.py — Authentication & Access Control for ML Serving.

Provides modular server-side authentication for administrative operations (Design 2 Section 15.5).
Configured via runtime environment variable `ADMIN_API_KEY` (or `ML_ADMIN_API_KEY`).
Never exposes, logs, or transmits credentials in URLs or responses.
"""

import logging
import secrets
from typing import Optional

from fastapi import Header

from config import get_settings
from schemas import UnauthorizedError

logger = logging.getLogger("ml_serving.auth")


class AdminAuthVerifier:
    """
    Pluggable authentication verifier for administrative ML serving endpoints.
    Allows easy future migration to OAuth2/JWT or external IDP validation.
    """

    def __init__(self, expected_api_key: Optional[str] = None) -> None:
        self._expected_key = expected_api_key

    def verify(
        self,
        authorization: Optional[str] = None,
        x_admin_token: Optional[str] = None,
        x_api_key: Optional[str] = None,
    ) -> None:
        """
        Validates administrative credentials.
        Extracts token from:
        1. 'Authorization: Bearer <secret>' header
        2. 'X-Admin-Token: <secret>' header
        3. 'X-API-Key: <secret>' header

        Raises UnauthorizedError (HTTP 401, RFC 7807) if missing or invalid.
        """
        settings = get_settings()
        expected = self._expected_key or settings.ADMIN_API_KEY

        # 1. Fail-safe: if no admin key is configured in settings
        if not expected or not expected.strip():
            logger.error(
                "Administrative action rejected: ADMIN_API_KEY is not configured in environment. "
                "Failing safely (access denied)."
            )
            raise UnauthorizedError(
                detail="Administrative authentication is not configured on this service. Access denied."
            )

        # 2. Extract client-provided token
        client_token: Optional[str] = None
        if authorization:
            parts = authorization.strip().split()
            if len(parts) == 2 and parts[0].lower() == "bearer":
                client_token = parts[1].strip()
            elif len(parts) == 1:
                client_token = parts[0].strip()

        if not client_token and x_admin_token:
            client_token = x_admin_token.strip()

        if not client_token and x_api_key:
            client_token = x_api_key.strip()

        # 3. Reject missing credentials immediately
        if not client_token:
            logger.warning("Administrative request rejected: Missing authentication credentials.")
            raise UnauthorizedError(
                detail="Administrative authentication credentials are required."
            )

        # 4. Constant-time comparison to prevent timing side-channel attacks
        if not secrets.compare_digest(client_token, expected.strip()):
            logger.warning("Administrative request rejected: Invalid credentials provided.")
            raise UnauthorizedError(
                detail="Invalid administrative authentication credentials."
            )

        logger.debug("Administrative request successfully authenticated.")


_default_verifier = AdminAuthVerifier()


def verify_admin_auth(
    authorization: Optional[str] = Header(None, alias="Authorization"),
    x_admin_token: Optional[str] = Header(None, alias="X-Admin-Token"),
    x_api_key: Optional[str] = Header(None, alias="X-API-Key"),
) -> None:
    """
    FastAPI dependency enforcing administrative authentication.
    Inject via `Depends(verify_admin_auth)`.
    """
    _default_verifier.verify(
        authorization=authorization,
        x_admin_token=x_admin_token,
        x_api_key=x_api_key,
    )
