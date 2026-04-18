import logging
import os
import tempfile
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI, HTTPException, UploadFile, File
from fastapi.responses import JSONResponse
from pydantic import BaseModel, field_validator

from parser import (
    parse_apk,
    validate_version_code,
    ApkParseError,
    VersionCodeError,
    download_apk_from_gcs,
    cleanup_apk,
)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(name)s — %(message)s"
)
logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info("AppVault Scanner starting up")
    yield
    logger.info("AppVault Scanner shutting down")


app = FastAPI(
    title="AppVault Scanner",
    version="0.2.0",
    lifespan=lifespan
)


# ── Request / Response models ──────────────────────────────────────────────

class ParseFromUrlRequest(BaseModel):
    apk_url: str
    existing_version_code: int = 0  # 0 means first submission

    @field_validator("apk_url")
    @classmethod
    def must_be_gcs_url(cls, v):
        if not v.startswith("gs://"):
            raise ValueError("apk_url must be a GCS URL starting with gs://")
        return v


class ParseResponse(BaseModel):
    package_name: str
    version_code: int
    version_name: str
    min_sdk: int
    target_sdk: int
    cert_fingerprint: str
    permissions: list[str]
    app_name: str
    icon_base64: str | None = None


# ── Endpoints ──────────────────────────────────────────────────────────────

@app.get("/health")
def health():
    return {
        "status": "UP",
        "service": "appvault-scanner",
        "version": "0.2.0",
        "timestamp": datetime.utcnow().isoformat()
    }


@app.post("/parse", response_model=ParseResponse)
def parse_from_gcs_url(request: ParseFromUrlRequest):
    """
    Downloads an APK from GCS and returns parsed metadata.
    Validates version code if existing_version_code > 0.
    """
    apk_path = None
    try:
        # Download from GCS
        apk_path = download_apk_from_gcs(request.apk_url)

        # Parse
        metadata = parse_apk(apk_path)

        # Validate version code if this is an update
        if request.existing_version_code > 0:
            validate_version_code(
                metadata.version_code,
                request.existing_version_code
            )

        return ParseResponse(
            package_name=metadata.package_name,
            version_code=metadata.version_code,
            version_name=metadata.version_name,
            min_sdk=metadata.min_sdk,
            target_sdk=metadata.target_sdk,
            cert_fingerprint=metadata.cert_fingerprint,
            permissions=metadata.permissions,
            app_name=metadata.app_name,
            icon_base64=metadata.icon_base64,
        )

    except VersionCodeError as e:
        raise HTTPException(status_code=422, detail=str(e))

    except ApkParseError as e:
        raise HTTPException(status_code=400, detail=str(e))

    except Exception as e:
        logger.error("Unexpected error during parse: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail="Internal parse error")

    finally:
        if apk_path:
            cleanup_apk(apk_path)


@app.post("/parse/upload", response_model=ParseResponse)
async def parse_uploaded_file(
    file: UploadFile = File(...),
    existing_version_code: int = 0
):
    """
    Accepts a direct APK upload (for local testing without GCS).
    Do not expose this endpoint publicly in production.
    """
    if not file.filename.endswith(".apk"):
        raise HTTPException(status_code=400, detail="File must be an APK")

    apk_path = None
    try:
        # Write upload to temp file
        tmp = tempfile.NamedTemporaryFile(
            suffix=".apk", delete=False, prefix="appvault_upload_"
        )
        content = await file.read()
        tmp.write(content)
        tmp.flush()
        tmp.close()
        apk_path = tmp.name

        metadata = parse_apk(apk_path)

        if existing_version_code > 0:
            validate_version_code(
                metadata.version_code,
                existing_version_code
            )

        return ParseResponse(
            package_name=metadata.package_name,
            version_code=metadata.version_code,
            version_name=metadata.version_name,
            min_sdk=metadata.min_sdk,
            target_sdk=metadata.target_sdk,
            cert_fingerprint=metadata.cert_fingerprint,
            permissions=metadata.permissions,
            app_name=metadata.app_name,
            icon_base64=metadata.icon_base64,
        )

    except VersionCodeError as e:
        raise HTTPException(status_code=422, detail=str(e))

    except ApkParseError as e:
        raise HTTPException(status_code=400, detail=str(e))

    except Exception as e:
        logger.error("Unexpected error during upload parse: %s", e, exc_info=True)
        raise HTTPException(status_code=500, detail="Internal parse error")

    finally:
        if apk_path:
            cleanup_apk(apk_path)


# ── Global error handler ───────────────────────────────────────────────────

@app.exception_handler(Exception)
async def global_exception_handler(request, exc):
    logger.error("Unhandled exception: %s", exc, exc_info=True)
    return JSONResponse(
        status_code=500,
        content={"detail": "An unexpected error occurred"}
    )