# Sprint 4 — Scanner Microservice

Wire the APK parser into a full async scan pipeline with HMAC authentication,
risk scoring, and signed webhook callback to the main platform.

Follow every section in order.

---

## What you are building

```
Main Platform                        Scanner VM
     |                                    |
     |  POST /scan  (HMAC signed)         |
     | ─────────────────────────────────► |
     |                                    | 1. Verify HMAC signature
     |                                    | 2. Download APK from GCS
     |                                    | 3. Run APK parser (Sprint 3)
     |                                    | 4. Run risk analysis
     |                                    | 5. Assemble risk report
     |  POST /internal/scanner-callback   |
     | ◄───────────────────────────────── |  (HMAC signed)
     |                                    |
     | 6. Verify HMAC signature           |
     | 7. Update app version status       |
     | 8. Notify admin queue              |
```

New endpoints:
- Scanner VM:  `POST /scan`                        — receives scan job from main platform
- Main Platform: `POST /internal/scanner-callback` — receives risk report from scanner

---

## Step 1 — updated scanner file structure

```
scanner/
├── Dockerfile
├── requirements.txt
├── main.py                       ← updated
├── parser/                       ← Sprint 3, no changes
│   ├── __init__.py
│   ├── apk_parser.py
│   └── gcs_client.py
├── security/
│   ├── __init__.py
│   └── hmac_auth.py              ← NEW: HMAC sign + verify
├── scanner/
│   ├── __init__.py
│   ├── risk_analyzer.py          ← NEW: risk scoring logic
│   └── scan_worker.py            ← NEW: async scan task
└── tests/
    ├── test_apk_parser.py        ← Sprint 3, no changes
    └── test_hmac_auth.py         ← NEW
    └── test_risk_analyzer.py     ← NEW
```

---

## Step 2 — update requirements.txt

```
fastapi==0.111.0
uvicorn[standard]==0.30.0
androguard==3.4.0
google-cloud-storage==2.17.0
google-cloud-secret-manager==2.20.0
requests==2.32.3
pytest==8.2.2
httpx==0.27.0
python-multipart==0.0.9
celery==5.4.0
redis==5.0.6
```

---

## Step 3 — HMAC authentication module

Create `scanner/security/__init__.py` (empty):

```python
```

Create `scanner/security/hmac_auth.py`:

```python
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
```

---

## Step 4 — risk analyzer

Create `scanner/scanner/__init__.py` (empty):

```python
```

Create `scanner/scanner/risk_analyzer.py`:

```python
import logging
from dataclasses import dataclass, field
from enum import Enum
from typing import Optional

from parser.apk_parser import ApkMetadata

logger = logging.getLogger(__name__)


class ScanStatus(str, Enum):
    PASSED = "PASSED"
    FLAGGED = "FLAGGED"
    REJECTED = "REJECTED"


# Permissions that are considered high risk
HIGH_RISK_PERMISSIONS = {
    "android.permission.READ_CONTACTS",
    "android.permission.WRITE_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.WRITE_CALL_LOG",
    "android.permission.PROCESS_OUTGOING_CALLS",
    "android.permission.READ_SMS",
    "android.permission.SEND_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.RECORD_AUDIO",
    "android.permission.CAMERA",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_BACKGROUND_LOCATION",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
}

# Permissions that are critical risk — auto-flag the app
CRITICAL_PERMISSIONS = {
    "android.permission.BIND_DEVICE_ADMIN",
    "android.permission.BIND_ACCESSIBILITY_SERVICE",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
}

# Minimum acceptable SDK version
MIN_ACCEPTABLE_SDK = 21  # Android 5.0


@dataclass
class RiskReport:
    scan_status: ScanStatus
    risk_score: int                    # 0–100, higher = more risky
    flags: list[str] = field(default_factory=list)
    high_risk_permissions: list[str] = field(default_factory=list)
    critical_permissions: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)


def analyze_risk(metadata: ApkMetadata) -> RiskReport:
    """
    Performs static risk analysis on parsed APK metadata.
    Returns a RiskReport with a score, status, and flags.

    Scoring:
      - Each high-risk permission:    +5 points
      - Each critical permission:    +20 points
      - Old min SDK (< 21):          +15 points
      - Very old min SDK (< 16):     +30 points
      - No certificate:              +50 points (auto-reject)
      - Excessive permissions (>15): +10 points

    Thresholds:
      - 0–30:  PASSED
      - 31–59: FLAGGED  (admin must review)
      - 60+:   REJECTED (auto-reject, admin can override)
    """
    flags = []
    warnings = []
    score = 0

    # Check certificate
    if "no-certificate" in metadata.cert_fingerprint or \
       "extraction-failed" in metadata.cert_fingerprint:
        flags.append("NO_VALID_CERTIFICATE")
        score += 50

    # Analyse permissions
    found_high_risk = []
    found_critical = []

    for perm in metadata.permissions:
        if perm in CRITICAL_PERMISSIONS:
            found_critical.append(perm)
            flags.append(f"CRITICAL_PERMISSION:{perm.split('.')[-1]}")
            score += 20
        elif perm in HIGH_RISK_PERMISSIONS:
            found_high_risk.append(perm)
            score += 5

    if found_high_risk:
        flags.append(f"HIGH_RISK_PERMISSIONS:{len(found_high_risk)}")

    # Excessive permissions check
    if len(metadata.permissions) > 15:
        flags.append("EXCESSIVE_PERMISSIONS")
        score += 10
        warnings.append(
            f"App requests {len(metadata.permissions)} permissions"
        )

    # SDK version check
    if metadata.min_sdk < 16:
        flags.append("VERY_OLD_MIN_SDK")
        score += 30
        warnings.append(f"minSdkVersion {metadata.min_sdk} is very old (< 16)")
    elif metadata.min_sdk < MIN_ACCEPTABLE_SDK:
        flags.append("OLD_MIN_SDK")
        score += 15
        warnings.append(
            f"minSdkVersion {metadata.min_sdk} is below recommended {MIN_ACCEPTABLE_SDK}"
        )

    # Clamp score to 0–100
    score = min(100, max(0, score))

    # Determine status
    if score >= 60:
        status = ScanStatus.REJECTED
    elif score >= 31:
        status = ScanStatus.FLAGGED
    else:
        status = ScanStatus.PASSED

    logger.info(
        "Risk analysis: %s score=%d flags=%s",
        status.value, score, flags
    )

    return RiskReport(
        scan_status=status,
        risk_score=score,
        flags=flags,
        high_risk_permissions=found_high_risk,
        critical_permissions=found_critical,
        warnings=warnings,
    )
```

---

## Step 5 — scan worker (async task)

Create `scanner/scanner/scan_worker.py`:

```python
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

    headers = make_signed_headers(payload)

    try:
        response = httpx.post(
            MAIN_PLATFORM_CALLBACK_URL,
            json=payload,
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
```

---

## Step 6 — Celery app

Create `scanner/celery_app.py`:

```python
import os
from celery import Celery

REDIS_URL = os.environ.get("REDIS_URL", "redis://localhost:6379/0")

celery_app = Celery(
    "appvault_scanner",
    broker=REDIS_URL,
    backend=REDIS_URL,
    include=["tasks"],
)

celery_app.conf.update(
    task_serializer="json",
    accept_content=["json"],
    result_serializer="json",
    timezone="UTC",
    enable_utc=True,
    task_soft_time_limit=300,   # 5 min soft limit per scan
    task_time_limit=360,        # 6 min hard limit
    worker_prefetch_multiplier=1,
    task_acks_late=True,
)
```

Create `scanner/tasks.py`:

```python
import logging
from celery_app import celery_app
from scanner.scan_worker import run_scan

logger = logging.getLogger(__name__)


@celery_app.task(name="tasks.scan_apk", bind=True, max_retries=2)
def scan_apk_task(self, app_id: str, version_id: str,
                  apk_url: str, callback_id: str):
    """Celery task that runs the full APK scan pipeline."""
    try:
        run_scan(
            app_id=app_id,
            version_id=version_id,
            apk_url=apk_url,
            callback_id=callback_id,
        )
    except Exception as exc:
        logger.error("Task failed: %s", exc, exc_info=True)
        raise self.retry(exc=exc, countdown=30)
```

---

## Step 7 — update main.py

Replace `scanner/main.py` entirely:

```python
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
```

---

## Step 8 — update Dockerfile

```dockerfile
FROM python:3.12-slim

RUN apt-get update && apt-get install -y \
    openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN mkdir -p /tmp/appvault_scans

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
```

---

## Step 9 — update docker-compose.yml on scanner-vm

SSH into scanner-vm and update `/home/ubuntu/scanner/docker-compose.yml`:

```yaml
version: "3.9"

services:
  redis:
    image: redis:7-alpine
    container_name: scanner-redis
    restart: unless-stopped
    volumes:
      - scanner_redis_data:/data

  scanner:
    image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-scanner:latest
    container_name: appvault-scanner
    restart: unless-stopped
    depends_on:
      - redis
    environment:
      REDIS_URL: redis://redis:6379/0
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/scanner-sa-key.json
      SCANNER_HMAC_SECRET: ${SCANNER_HMAC_SECRET}
      MAIN_PLATFORM_CALLBACK_URL: https://appvault.online/internal/scanner-callback
    volumes:
      - /home/ubuntu/scanner/scanner-sa-key.json:/secrets/scanner-sa-key.json:ro
      - /tmp/appvault_scans:/tmp/appvault_scans
    ports:
      - "8000:8000"

  celery_worker:
    image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-scanner:latest
    container_name: scanner-celery
    restart: unless-stopped
    depends_on:
      - redis
    environment:
      REDIS_URL: redis://redis:6379/0
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/scanner-sa-key.json
      SCANNER_HMAC_SECRET: ${SCANNER_HMAC_SECRET}
      MAIN_PLATFORM_CALLBACK_URL: https://appvault.online/internal/scanner-callback
    volumes:
      - /home/ubuntu/scanner/scanner-sa-key.json:/secrets/scanner-sa-key.json:ro
      - /tmp/appvault_scans:/tmp/appvault_scans
    command: celery -A celery_app worker --loglevel=info --concurrency=2

  nginx:
    image: nginx:alpine
    container_name: scanner-nginx
    restart: unless-stopped
    depends_on:
      - scanner
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx.conf:/etc/nginx/nginx.conf:ro
      - /etc/letsencrypt:/etc/letsencrypt:ro

volumes:
  scanner_redis_data:
```

Pull HMAC secret to scanner `.env`:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP
cd /home/ubuntu/scanner

HMAC_SECRET=$(gcloud secrets versions access latest --secret="scanner-hmac-secret")
echo "SCANNER_HMAC_SECRET=${HMAC_SECRET}" > .env
chmod 600 .env
```

---

## Step 10 — Spring Boot: scanner callback endpoint

Add these files to the main backend.

### ScanResult DTO

Create `backend/src/main/java/com/appvault/scanner/dto/ScanResultCallback.java`:

```java
package com.appvault.scanner.dto;

import lombok.Data;
import java.util.List;

@Data
public class ScanResultCallback {
    private String appId;
    private String versionId;
    private String callbackId;
    private String scanStatus;   // PASSED | FLAGGED | REJECTED
    private int riskScore;
    private List<String> flags;
    private String certFingerprint;
    private String packageName;
    private List<String> permissions;
    private int minSdk;
    private int targetSdk;
    private String error;
}
```

### HMAC verifier in Spring Boot

Create `backend/src/main/java/com/appvault/scanner/HmacVerifier.java`:

```java
package com.appvault.scanner;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class HmacVerifier {

    @Value("${scanner.hmac-secret}")
    private String hmacSecret;

    /**
     * Verifies the HMAC-SHA256 signature on an incoming scanner callback.
     * Uses constant-time comparison to prevent timing attacks.
     */
    public boolean verify(byte[] rawBody, String receivedSignature) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    hmacSecret.getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"
            );
            mac.init(keySpec);
            byte[] expectedBytes = mac.doFinal(rawBody);
            String expectedHex = HexFormat.of().formatHex(expectedBytes);

            // Constant-time comparison — never use .equals() here
            return MessageDigest.isEqual(
                    expectedHex.getBytes(StandardCharsets.UTF_8),
                    receivedSignature.getBytes(StandardCharsets.UTF_8)
            );
        } catch (Exception e) {
            return false;
        }
    }
}
```

### Scanner callback controller

Create `backend/src/main/java/com/appvault/scanner/ScannerCallbackController.java`:

```java
package com.appvault.scanner;

import com.appvault.scanner.dto.ScanResultCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/internal/scanner-callback")
@RequiredArgsConstructor
@Slf4j
public class ScannerCallbackController {

    private final HmacVerifier hmacVerifier;
    private final ScannerCallbackService callbackService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    @PostMapping
    public ResponseEntity<Map<String, String>> handleCallback(
            HttpServletRequest request) throws Exception {

        // Read raw body BEFORE Jackson touches it
        byte[] rawBody = StreamUtils.copyToByteArray(request.getInputStream());

        // Verify HMAC signature
        String signature = request.getHeader("X-Signature");
        if (signature == null || signature.isBlank()) {
            log.warn("Scanner callback missing X-Signature header");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Missing signature"));
        }

        if (!hmacVerifier.verify(rawBody, signature)) {
            log.warn("Scanner callback HMAC verification failed");
            return ResponseEntity.status(401)
                    .body(Map.of("error", "Invalid signature"));
        }

        // Parse body after verification
        ScanResultCallback callback = objectMapper.readValue(
                rawBody, ScanResultCallback.class);

        // Replay protection — check if this callbackId was already processed
        String replayKey = "scanner:callback:" + callback.getCallbackId();
        Boolean isNew = redisTemplate.opsForValue()
                .setIfAbsent(replayKey, "1", 24, TimeUnit.HOURS);

        if (Boolean.FALSE.equals(isNew)) {
            log.info("Duplicate callback ignored: {}", callback.getCallbackId());
            return ResponseEntity.ok(Map.of("message", "Already processed"));
        }

        // Process the scan result async
        callbackService.processScanResult(callback);

        log.info("Scan callback accepted: appId={} status={} score={}",
                callback.getAppId(),
                callback.getScanStatus(),
                callback.getRiskScore());

        return ResponseEntity.accepted()
                .body(Map.of("message", "Scan result accepted"));
    }
}
```

### Scanner callback service

Create `backend/src/main/java/com/appvault/scanner/ScannerCallbackService.java`:

```java
package com.appvault.scanner;

import com.appvault.scanner.dto.ScanResultCallback;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScannerCallbackService {

    // AppVersionRepository and notification service will be wired in
    // Sprint 5 when the full submission flow is built.
    // For now we log the result and confirm the pipeline works.

    @Async
    public void processScanResult(ScanResultCallback callback) {
        log.info(
            "Processing scan result: appId={} versionId={} status={} score={} flags={}",
            callback.getAppId(),
            callback.getVersionId(),
            callback.getScanStatus(),
            callback.getRiskScore(),
            callback.getFlags()
        );

        // Sprint 5 will:
        // 1. Update AppVersion status to SCAN_COMPLETE
        // 2. Store certFingerprint + permissions + riskScore on the version
        // 3. Fire notification to admin queue
        // Stub left intentionally — do not implement here.
    }
}
```

### Add scanner config to application.yml

Add to `backend/src/main/resources/application.yml`:

```yaml
scanner:
  hmac-secret: ${SCANNER_HMAC_SECRET:local_dev_hmac_secret_32_chars_min}
  callback-url: ${SCANNER_CALLBACK_URL:http://localhost:8000/scan}
```

### Expose /internal/** in SecurityConfig

In `SecurityConfig.java`, add `/internal/scanner-callback` to the permitted paths
only for requests from the scanner VM IP (or simply authenticate via HMAC — already done):

```java
.requestMatchers("/internal/scanner-callback").permitAll()
```

Add this line inside `authorizeHttpRequests`, with the other `permitAll` paths.

---

## Step 11 — write tests

Create `scanner/tests/test_hmac_auth.py`:

```python
import json
import os
import pytest

os.environ["SCANNER_HMAC_SECRET"] = "test_secret_32_chars_minimum_ok!"

from security.hmac_auth import sign_payload, verify_signature, make_signed_headers


class TestHmacAuth:

    def test_sign_and_verify_roundtrip(self):
        payload = {"appId": "123", "status": "PASSED"}
        body = json.dumps(payload, sort_keys=True,
                          separators=(",", ":")).encode()
        sig = sign_payload(payload)
        assert verify_signature(body, sig)

    def test_tampered_body_fails(self):
        payload = {"appId": "123", "status": "PASSED"}
        sig = sign_payload(payload)
        tampered = json.dumps({"appId": "123", "status": "REJECTED"},
                              sort_keys=True,
                              separators=(",", ":")).encode()
        assert not verify_signature(tampered, sig)

    def test_wrong_signature_fails(self):
        payload = {"appId": "123"}
        body = json.dumps(payload, sort_keys=True,
                          separators=(",", ":")).encode()
        assert not verify_signature(body, "completely_wrong_signature")

    def test_empty_signature_fails(self):
        payload = {"appId": "123"}
        body = json.dumps(payload, sort_keys=True,
                          separators=(",", ":")).encode()
        assert not verify_signature(body, "")

    def test_make_signed_headers_has_required_keys(self):
        headers = make_signed_headers({"appId": "123"})
        assert "X-Signature" in headers
        assert "X-Timestamp" in headers
        assert "Content-Type" in headers

    def test_signature_is_hex_string(self):
        sig = sign_payload({"test": "value"})
        assert all(c in "0123456789abcdef" for c in sig)
        assert len(sig) == 64  # SHA-256 hex is 64 chars
```

Create `scanner/tests/test_risk_analyzer.py`:

```python
import pytest
from parser.apk_parser import ApkMetadata
from scanner.risk_analyzer import analyze_risk, ScanStatus


def make_metadata(**kwargs) -> ApkMetadata:
    defaults = dict(
        package_name="com.example.app",
        version_code=1,
        version_name="1.0",
        min_sdk=26,
        target_sdk=34,
        cert_fingerprint="sha256:AB:CD:EF",
        permissions=[],
        app_name="Test App",
        icon_base64=None,
    )
    defaults.update(kwargs)
    return ApkMetadata(**defaults)


class TestRiskAnalyzer:

    def test_clean_app_passes(self):
        result = analyze_risk(make_metadata())
        assert result.scan_status == ScanStatus.PASSED
        assert result.risk_score < 31

    def test_no_certificate_increases_score(self):
        result = analyze_risk(make_metadata(
            cert_fingerprint="sha256:no-certificate"
        ))
        assert result.risk_score >= 50
        assert "NO_VALID_CERTIFICATE" in result.flags

    def test_critical_permission_flags_app(self):
        result = analyze_risk(make_metadata(
            permissions=["android.permission.BIND_DEVICE_ADMIN"]
        ))
        assert result.risk_score >= 20
        assert any("CRITICAL_PERMISSION" in f for f in result.flags)

    def test_many_high_risk_permissions_flags_app(self):
        perms = [
            "android.permission.READ_SMS",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.CAMERA",
            "android.permission.SEND_SMS",
        ]
        result = analyze_risk(make_metadata(permissions=perms))
        assert result.risk_score >= 30

    def test_old_sdk_adds_risk(self):
        result = analyze_risk(make_metadata(min_sdk=15))
        assert "VERY_OLD_MIN_SDK" in result.flags
        assert result.risk_score >= 30

    def test_excessive_permissions_flagged(self):
        perms = [f"android.permission.PERM_{i}" for i in range(20)]
        result = analyze_risk(make_metadata(permissions=perms))
        assert "EXCESSIVE_PERMISSIONS" in result.flags

    def test_high_score_results_in_rejected(self):
        result = analyze_risk(make_metadata(
            cert_fingerprint="sha256:no-certificate",
            permissions=["android.permission.BIND_DEVICE_ADMIN"],
            min_sdk=10,
        ))
        assert result.scan_status == ScanStatus.REJECTED
        assert result.risk_score >= 60

    def test_score_capped_at_100(self):
        result = analyze_risk(make_metadata(
            cert_fingerprint="sha256:no-certificate",
            permissions=[
                "android.permission.BIND_DEVICE_ADMIN",
                "android.permission.BIND_ACCESSIBILITY_SERVICE",
                "android.permission.MANAGE_EXTERNAL_STORAGE",
            ],
            min_sdk=10,
        ))
        assert result.risk_score <= 100
```

Run all tests:

```bash
cd scanner
source venv/bin/activate
pytest tests/ -v
```

Expected: all tests pass including the 16 from Sprint 3 plus 6 HMAC tests plus 8 risk tests = **30 tests total**.

---

## Step 12 — end-to-end test

Test the full pipeline locally before deploying.

Terminal 1 — start Redis:
```bash
docker run -d --name test-redis -p 6379:6379 redis:7-alpine
```

Terminal 2 — start Celery worker:
```bash
cd scanner
source venv/bin/activate
export SCANNER_HMAC_SECRET="test_secret_32_chars_minimum_ok!"
export REDIS_URL="redis://localhost:6379/0"
export MAIN_PLATFORM_CALLBACK_URL="http://localhost:9999/internal/scanner-callback"
celery -A celery_app worker --loglevel=info
```

Terminal 3 — start FastAPI:
```bash
cd scanner
source venv/bin/activate
export SCANNER_HMAC_SECRET="test_secret_32_chars_minimum_ok!"
export REDIS_URL="redis://localhost:6379/0"
export MAIN_PLATFORM_CALLBACK_URL="http://localhost:9999/internal/scanner-callback"
uvicorn main:app --reload --port 8000
```

Terminal 4 — send a test scan request:
```bash
# Generate the HMAC signature for the test payload
python3 - << 'EOF'
import hmac, hashlib, json, time

secret = "test_secret_32_chars_minimum_ok!"
payload = {
    "appId": "test-app-001",
    "versionId": "test-version-001",
    "apkUrl": "gs://appvault-files/test/sample.apk",
    "callbackId": "test-callback-001"
}
body = json.dumps(payload, sort_keys=True, separators=(",", ":"))
sig = hmac.new(secret.encode(), body.encode(), hashlib.sha256).hexdigest()
print(f"Payload: {body}")
print(f"Signature: {sig}")
EOF

# Use the printed signature in the request
curl -X POST http://localhost:8000/scan \
  -H "Content-Type: application/json" \
  -H "X-Signature: SIGNATURE_FROM_ABOVE" \
  -d '{"appId":"test-app-001","versionId":"test-version-001","apkUrl":"gs://appvault-files/test/sample.apk","callbackId":"test-callback-001"}'

# Expected: {"message":"Scan job accepted","callbackId":"test-callback-001"}

# Test HMAC rejection — wrong signature
curl -X POST http://localhost:8000/scan \
  -H "Content-Type: application/json" \
  -H "X-Signature: wrongsignature" \
  -d '{"appId":"test","versionId":"test","apkUrl":"gs://bucket/file.apk","callbackId":"test"}'
# Expected: 401 {"detail":"Invalid signature"}
```

---

## Step 13 — commit and deploy

```bash
git add .
git commit -m "feat: Sprint 4 — scanner microservice with HMAC, Celery, risk analyzer, callback"
git push origin main
```

On scanner-vm after deploy:
```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP
cd /home/ubuntu/scanner
docker compose up -d
docker compose ps
# All 4 services should be Up: redis, scanner, celery_worker, nginx

docker logs scanner-celery --tail 20
# Should show: [celery] ready. Consuming from queue.
```

---

## Sprint 4 definition of done checklist

```
[ ] pytest — all 30 tests pass (16 parser + 6 HMAC + 8 risk)
[ ] POST /scan with valid HMAC returns 202
[ ] POST /scan with invalid/missing HMAC returns 401
[ ] POST /scan with valid request enqueues Celery task (check celery logs)
[ ] Celery worker processes task and logs scan result
[ ] Duplicate callbackId returns 200 immediately with no reprocessing (Redis check)
[ ] POST /parse/upload returns scan_status and risk_score in addition to metadata
[ ] Spring Boot POST /internal/scanner-callback with valid HMAC returns 202
[ ] Spring Boot POST /internal/scanner-callback with invalid HMAC returns 401
[ ] All 4 scanner-vm containers healthy after deployment
```

---

## Troubleshooting

### "SCANNER_HMAC_SECRET not set"
The `.env` file on scanner-vm is missing the secret.
Run `./pull-secrets.sh` or manually set it as shown in Step 9.

### Celery worker not picking up tasks
```bash
docker logs scanner-celery --tail 50
# Check Redis connection — should say "Connected to redis://redis:6379/0"
# If not: docker compose restart redis celery_worker
```

### Sign/verify mismatch between Python and Java
The Python signer uses `json.dumps(payload, sort_keys=True, separators=(",",":"))`.
The Java verifier receives the raw request body bytes — it does NOT re-serialize.
This is correct: sign the exact bytes that will be sent over the wire, verify those same bytes.
Never re-serialize on either end.

### httpx timeout on callback
If the main platform is not yet running the callback endpoint,
the worker logs an error but does not crash — this is expected.
The callback endpoint is fully wired in Sprint 5.