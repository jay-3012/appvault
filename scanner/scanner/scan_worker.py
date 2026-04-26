import json
import logging
import os
import time
import uuid

import httpx

from parser import parse_apk, download_apk_from_gcs, cleanup_apk, ApkParseError
from scanner.risk_analyzer import analyze_risk, RiskReport, ScanStatus
from security.hmac_auth import make_signed_headers

logger = logging.getLogger(__name__)

MAIN_PLATFORM_CALLBACK_URL = os.environ.get(
    "MAIN_PLATFORM_CALLBACK_URL",
    "https://appvault.online/internal/scanner-callback"
)


def run_scan(app_id: str, version_id: str, apk_url: str, callback_id: str) -> None:
    """
    Full scan pipeline:
    1. Download APK from GCS
    2. Parse metadata
    3. Run risk analysis
    4. POST signed risk report to main platform
    """
    logger.info(
        "Starting scan: appId=%s versionId=%s callbackId=%s",
        app_id, version_id, callback_id
    )

    apk_path = None
    start_time = time.time()

    try:
        # Step 1: Download
        apk_path = download_apk_from_gcs(apk_url)

        # Step 2: Parse
        try:
            metadata = parse_apk(apk_path)
        except ApkParseError as e:
            logger.error("APK parse failed: %s", e)
            _send_callback(
                app_id=app_id,
                version_id=version_id,
                callback_id=callback_id,
                scan_status=ScanStatus.REJECTED.value,
                risk_score=100,
                flags=["PARSE_FAILED"],
                cert_fingerprint="sha256:parse-failed",
                package_name="unknown",
                permissions=[],
                min_sdk=0,
                target_sdk=0,
                error=str(e),
            )
            return

        # Step 3: Risk analysis
        risk_report = analyze_risk(metadata)

        elapsed = round(time.time() - start_time, 2)
        logger.info(
            "Scan complete in %ss: %s score=%d",
            elapsed, risk_report.scan_status.value, risk_report.risk_score
        )

        # Step 4: Send callback
        _send_callback(
            app_id=app_id,
            version_id=version_id,
            callback_id=callback_id,
            scan_status=risk_report.scan_status.value,
            risk_score=risk_report.risk_score,
            flags=risk_report.flags,
            cert_fingerprint=metadata.cert_fingerprint,
            package_name=metadata.package_name,
            permissions=metadata.permissions,
            min_sdk=metadata.min_sdk,
            target_sdk=metadata.target_sdk,
        )

    except Exception as e:
        logger.error("Unexpected scan error: %s", e, exc_info=True)
        _send_callback(
            app_id=app_id,
            version_id=version_id,
            callback_id=callback_id,
            scan_status=ScanStatus.REJECTED.value,
            risk_score=100,
            flags=["INTERNAL_ERROR"],
            cert_fingerprint="sha256:error",
            package_name="unknown",
            permissions=[],
            min_sdk=0,
            target_sdk=0,
            error=str(e),
        )
    finally:
        if apk_path:
            cleanup_apk(apk_path)

def _send_callback(
    app_id: str,
    version_id: str,
    callback_id: str,
    scan_status: str,
    risk_score: int,
    flags: list,
    cert_fingerprint: str,
    package_name: str,
    permissions: list,
    min_sdk: int,
    target_sdk: int,
    error: str = None,
) -> None:
    """Signs and POSTs the scan result back to the main platform."""

    payload = {
        "appId": app_id,
        "versionId": version_id,
        "callbackId": callback_id,
        "scanStatus": scan_status,
        "riskScore": risk_score,
        "flags": flags,
        "certFingerprint": cert_fingerprint,
        "packageName": package_name,
        "permissions": permissions,
        "minSdk": min_sdk,
        "targetSdk": target_sdk,
    }
    if error:
        payload["error"] = error

    # Serialize ONCE — these exact bytes are both signed and sent
    payload_bytes = json.dumps(
        payload, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")

    # Sign the exact bytes being sent
    secret = get_secret()
    signature = hmac_lib.new(
        secret.encode("utf-8"),
        payload_bytes,
        hashlib.sha256
    ).hexdigest()

    headers = {
        "X-Signature": signature,
        "Content-Type": "application/json",
    }

    logger.info("Sending callback: callbackId=%s signature=%s...",
                callback_id, signature[:16])

    try:
        response = httpx.post(
            MAIN_PLATFORM_CALLBACK_URL,
            content=payload_bytes,   # send raw bytes, NOT json=payload
            headers=headers,
            timeout=30.0,
        )
        response.raise_for_status()
        logger.info(
            "Callback sent successfully: callbackId=%s status=%d",
            callback_id, response.status_code
        )
    except httpx.HTTPStatusError as e:
        logger.error(
            "Callback HTTP error: %s — %s",
            e.response.status_code, e.response.text
        )
    except Exception as e:
        logger.error("Callback failed: %s", e, exc_info=True)