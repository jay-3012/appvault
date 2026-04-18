import hashlib
import hmac
import json
import logging
import os
import time
from typing import Optional

logger = logging.getLogger(__name__)

# Load shared secret from environment (pulled from GCP Secret Manager)
_SECRET: Optional[str] = None


def get_secret() -> str:
    global _SECRET
    if _SECRET is None:
        _SECRET = os.environ.get("SCANNER_HMAC_SECRET", "")
        if not _SECRET:
            raise RuntimeError(
                "SCANNER_HMAC_SECRET not set. "
                "Pull secrets from GCP Secret Manager."
            )
    return _SECRET


def sign_payload(payload: dict) -> str:
    """
    Creates an HMAC-SHA256 signature over the JSON payload.
    Returns hex digest string.
    """
    secret = get_secret()
    body = json.dumps(payload, sort_keys=True, separators=(",", ":"))
    signature = hmac.new(
        secret.encode("utf-8"),
        body.encode("utf-8"),
        hashlib.sha256
    ).hexdigest()
    return signature


def verify_signature(payload_bytes: bytes, received_signature: str) -> bool:
    """
    Verifies HMAC-SHA256 signature using constant-time comparison.
    Always use this — never use == for signature comparison (timing attack).
    """
    secret = get_secret()
    expected = hmac.new(
        secret.encode("utf-8"),
        payload_bytes,
        hashlib.sha256
    ).hexdigest()
    return hmac.compare_digest(expected, received_signature)


def make_signed_headers(payload: dict) -> dict:
    """
    Returns headers dict with HMAC signature and timestamp.
    Use these when calling back to the main platform.
    """
    timestamp = str(int(time.time()))
    signature = sign_payload({**payload, "timestamp": timestamp})
    return {
        "X-Signature": signature,
        "X-Timestamp": timestamp,
        "Content-Type": "application/json",
    }