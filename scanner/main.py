import logging
import os
import uuid
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI, HTTPException, Request, UploadFile, File
from fastapi.responses import JSONResponse
from pydantic import BaseModel, field_validator

from parser import parse_apk, download_apk_from_gcs, cleanup_apk, ApkParseError
from security.hmac_auth import verify_signature
from tasks import scan_apk_task

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AppVault Scanner v0.3.0 starting")
    yield
    logger.info("AppVault Scanner shutting down")


app = FastAPI(title="AppVault Scanner", version="0.3.0", lifespan=lifespan)


# ── Request models ─────────────────────────────────────────────────────────

class ScanRequest(BaseModel):
    appId: str
    versionId: str
    apkUrl: str
    callbackId: str

    @field_validator("apkUrl")
    @classmethod
    def must_be_gcs_url(cls, v):
        if not v.startswith("gs://"):
            raise ValueError("apkUrl must start with gs://")
        return v


# ── Endpoints ──────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status": "UP",
        "service": "appvault-scanner",
        "version": "0.3.0",
        "timestamp": datetime.utcnow().isoformat(),
    }


@app.post("/scan", status_code=202)
async def receive_scan_job(request: Request):
    """
    Receives a scan job from the main platform.
    Verifies HMAC signature, then enqueues the scan as a Celery task.
    Returns 202 immediately — scan runs async.
    """
    raw_body = await request.body()

    # Verify HMAC signature
    signature = request.headers.get("X-Signature", "")
    if not signature:
        raise HTTPException(status_code=401, detail="Missing X-Signature header")

    if not verify_signature(raw_body, signature):
        logger.warning("Invalid HMAC signature on /scan request")
        raise HTTPException(status_code=401, detail="Invalid signature")

    # Parse body after verification
    try:
        import json
        data = json.loads(raw_body)
        scan_req = ScanRequest(**data)
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"Invalid request body: {e}")

    # Enqueue async task
    scan_apk_task.delay(
        app_id=scan_req.appId,
        version_id=scan_req.versionId,
        apk_url=scan_req.apkUrl,
        callback_id=scan_req.callbackId,
    )

    logger.info(
        "Scan job enqueued: appId=%s callbackId=%s",
        scan_req.appId, scan_req.callbackId
    )

    return {
        "message": "Scan job accepted",
        "callbackId": scan_req.callbackId,
    }


@app.post("/parse/upload")
async def parse_uploaded_file(
    file: UploadFile = File(...),
    existing_version_code: int = 0,
):
    """Direct APK upload for testing. Do not expose publicly in production."""
    import tempfile, os
    from parser import validate_version_code, VersionCodeError

    if not file.filename.endswith(".apk"):
        raise HTTPException(status_code=400, detail="File must be an APK")

    apk_path = None
    try:
        tmp = tempfile.NamedTemporaryFile(suffix=".apk", delete=False)
        tmp.write(await file.read())
        tmp.flush()
        tmp.close()
        apk_path = tmp.name

        metadata = parse_apk(apk_path)

        if existing_version_code > 0:
            validate_version_code(metadata.version_code, existing_version_code)

        from scanner.risk_analyzer import analyze_risk
        risk = analyze_risk(metadata)

        return {
            "package_name": metadata.package_name,
            "version_code": metadata.version_code,
            "version_name": metadata.version_name,
            "min_sdk": metadata.min_sdk,
            "target_sdk": metadata.target_sdk,
            "cert_fingerprint": metadata.cert_fingerprint,
            "permissions": metadata.permissions,
            "app_name": metadata.app_name,
            "scan_status": risk.scan_status.value,
            "risk_score": risk.risk_score,
            "flags": risk.flags,
        }

    except VersionCodeError as e:
        raise HTTPException(status_code=422, detail=str(e))
    except ApkParseError as e:
        raise HTTPException(status_code=400, detail=str(e))
    finally:
        if apk_path:
            cleanup_apk(apk_path)