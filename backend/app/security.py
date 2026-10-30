"""API authentication and rate limiting.

Clients send `Authorization: Bearer <key>`. The server stores only SHA-256 digests of keys
(JOBINTEL_API_KEY_HASHES) and compares in constant time. Rate limits are keyed on the
key digest (or client IP for unauthenticated routes).
"""
from __future__ import annotations

import hashlib
import hmac

from fastapi import Depends, HTTPException, Request, status
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from slowapi import Limiter
from slowapi.util import get_remote_address

from .config import get_settings

_bearer = HTTPBearer(auto_error=False)


def hash_key(key: str) -> str:
    return hashlib.sha256(key.encode()).hexdigest()


def _rate_key(request: Request) -> str:
    auth = request.headers.get("authorization", "")
    if auth.lower().startswith("bearer ") and len(auth) < 300:
        return "k:" + hash_key(auth[7:].strip())[:16]
    return "ip:" + get_remote_address(request)


limiter = Limiter(key_func=_rate_key, default_limits=[get_settings().rate_limit])


def require_api_key(creds: HTTPAuthorizationCredentials | None = Depends(_bearer)) -> str:
    valid = get_settings().api_key_hash_set
    if not valid:
        # Fail closed: a server without configured keys serves nothing.
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "server has no API keys configured")
    if creds is None or creds.scheme.lower() != "bearer" or not creds.credentials or len(creds.credentials) > 256:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing or invalid credentials",
                            headers={"WWW-Authenticate": "Bearer"})
    digest = hash_key(creds.credentials)
    if not any(hmac.compare_digest(digest, h) for h in valid):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "missing or invalid credentials",
                            headers={"WWW-Authenticate": "Bearer"})
    return digest[:16]
