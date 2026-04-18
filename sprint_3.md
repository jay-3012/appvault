# Sprint 3 — APK Parser Microservice

Build the APK parsing module on the scanner VM. Extracts all metadata
from an APK file and exposes it via a FastAPI endpoint.

Follow every section in order.

---

## What you are building

A Python module that takes an APK (by file path or GCS URL) and returns:

```json
{
  "packageName": "com.example.myapp",
  "versionCode": 12,
  "versionName": "1.2.0",
  "minSdk": 21,
  "targetSdk": 34,
  "certFingerprint": "sha256:AB:CD:EF:...",
  "permissions": ["android.permission.CAMERA", "android.permission.INTERNET"],
  "iconBase64": "iVBORw0KGgo...",
  "appName": "My App"
}
```

Endpoints:
- `GET  /health`  — already exists, no change
- `POST /parse`   — accepts `{ "apkUrl": "gs://..." }`, returns parsed metadata

---

## Step 1 — updated file structure

```
scanner/
├── Dockerfile
├── requirements.txt
├── main.py
├── parser/
│   ├── __init__.py
│   ├── apk_parser.py        ← core parsing logic
│   └── gcs_client.py        ← download APK from GCS
└── tests/
    ├── __init__.py
    ├── test_apk_parser.py
    └── fixtures/            ← put test APK files here
```

---

## Step 2 — update requirements.txt

Replace `scanner/requirements.txt` entirely:

```
fastapi==0.111.0
uvicorn[standard]==0.30.0
androguard==3.4.0
google-cloud-storage==2.17.0
requests==2.32.3
pytest==8.2.2
httpx==0.27.0
python-multipart==0.0.9
```

---

## Step 3 — GCS client

Create `scanner/parser/gcs_client.py`:

```python
import os
import tempfile
import logging
from google.cloud import storage

logger = logging.getLogger(__name__)


def download_apk_from_gcs(gcs_url: str) -> str:
    """
    Downloads an APK from a GCS URL to a local temp file.
    Returns the local file path.
    gcs_url format: gs://bucket-name/path/to/file.apk
    """
    if not gcs_url.startswith("gs://"):
        raise ValueError(f"Invalid GCS URL: {gcs_url}")

    # Parse bucket and object path
    without_prefix = gcs_url[5:]
    slash_index = without_prefix.index("/")
    bucket_name = without_prefix[:slash_index]
    object_path = without_prefix[slash_index + 1:]

    logger.info("Downloading APK from GCS: %s", gcs_url)

    client = storage.Client()
    bucket = client.bucket(bucket_name)
    blob = bucket.blob(object_path)

    # Write to a named temp file that persists until we delete it
    tmp = tempfile.NamedTemporaryFile(
        suffix=".apk",
        delete=False,
        prefix="appvault_scan_"
    )
    blob.download_to_file(tmp)
    tmp.flush()
    tmp.close()

    logger.info("APK downloaded to: %s", tmp.name)
    return tmp.name


def cleanup_apk(file_path: str) -> None:
    """Deletes the temp APK file after scanning."""
    try:
        os.unlink(file_path)
        logger.info("Cleaned up temp file: %s", file_path)
    except OSError as e:
        logger.warning("Could not delete temp file %s: %s", file_path, e)
```

---

## Step 4 — core APK parser

Create `scanner/parser/apk_parser.py`:

```python
import base64
import hashlib
import logging
from dataclasses import dataclass, field
from typing import Optional

from androguard.misc import AnalyzeAPK

logger = logging.getLogger(__name__)


@dataclass
class ApkMetadata:
    package_name: str
    version_code: int
    version_name: str
    min_sdk: int
    target_sdk: int
    cert_fingerprint: str
    permissions: list[str]
    app_name: str
    icon_base64: Optional[str] = None


class ApkParseError(Exception):
    """Raised when an APK cannot be parsed."""
    pass


class VersionCodeError(Exception):
    """Raised when version code validation fails."""
    pass


def parse_apk(apk_path: str) -> ApkMetadata:
    """
    Parses an APK file and returns all metadata.
    Raises ApkParseError if the file is invalid or corrupt.
    """
    try:
        logger.info("Parsing APK: %s", apk_path)
        apk, _, _ = AnalyzeAPK(apk_path)
    except Exception as e:
        raise ApkParseError(f"Could not parse APK: {e}") from e

    # Package name
    package_name = apk.get_package()
    if not package_name:
        raise ApkParseError("APK has no package name")

    # Version info
    try:
        version_code = int(apk.get_androidversion_code() or 0)
    except (ValueError, TypeError):
        version_code = 0

    version_name = apk.get_androidversion_name() or "unknown"

    # SDK versions
    try:
        min_sdk = int(apk.get_min_sdk_version() or 1)
    except (ValueError, TypeError):
        min_sdk = 1

    try:
        target_sdk = int(apk.get_target_sdk_version() or min_sdk)
    except (ValueError, TypeError):
        target_sdk = min_sdk

    # Certificate fingerprint (SHA-256 of the signing cert DER bytes)
    cert_fingerprint = _extract_cert_fingerprint(apk)

    # Permissions
    permissions = sorted(list(set(apk.get_permissions() or [])))

    # App name
    app_name = apk.get_app_name() or package_name

    # Icon (best effort — not all APKs have extractable icons)
    icon_base64 = _extract_icon(apk)

    metadata = ApkMetadata(
        package_name=package_name,
        version_code=version_code,
        version_name=version_name,
        min_sdk=min_sdk,
        target_sdk=target_sdk,
        cert_fingerprint=cert_fingerprint,
        permissions=permissions,
        app_name=app_name,
        icon_base64=icon_base64,
    )

    logger.info(
        "Parsed APK: %s v%s (code %d), cert: %s",
        package_name, version_name, version_code, cert_fingerprint[:20]
    )
    return metadata


def validate_version_code(new_code: int, existing_code: int) -> None:
    """
    Raises VersionCodeError if the new version code is not
    strictly greater than the existing one.
    """
    if new_code <= existing_code:
        raise VersionCodeError(
            f"Version code {new_code} must be greater than "
            f"existing version code {existing_code}"
        )


def _extract_cert_fingerprint(apk) -> str:
    """
    Extracts SHA-256 fingerprint from the APK signing certificate.
    Returns a hex string like 'sha256:AB:CD:EF:...'
    Falls back to 'sha256:unknown' if extraction fails.
    """
    try:
        certs = apk.get_certificates()
        if not certs:
            return "sha256:no-certificate"

        # Use the first certificate
        cert = certs[0]

        # Get DER-encoded bytes
        cert_der = cert.dump()

        # SHA-256 hash
        digest = hashlib.sha256(cert_der).hexdigest().upper()

        # Format as colon-separated pairs: AB:CD:EF:...
        formatted = ":".join(
            digest[i:i+2] for i in range(0, len(digest), 2)
        )
        return f"sha256:{formatted}"

    except Exception as e:
        logger.warning("Could not extract cert fingerprint: %s", e)
        return "sha256:extraction-failed"


def _extract_icon(apk) -> Optional[str]:
    """
    Attempts to extract the app icon as a base64-encoded PNG.
    Returns None if extraction fails — this is non-fatal.
    """
    try:
        icon_name = apk.get_app_icon()
        if not icon_name:
            return None

        icon_data = apk.get_file(icon_name)
        if not icon_data:
            return None

        return base64.b64encode(icon_data).decode("utf-8")

    except Exception as e:
        logger.debug("Icon extraction failed (non-fatal): %s", e)
        return None
```

---

## Step 5 — parser `__init__.py`

Create `scanner/parser/__init__.py`:

```python
from .apk_parser import parse_apk, validate_version_code, ApkParseError, VersionCodeError
from .gcs_client import download_apk_from_gcs, cleanup_apk

__all__ = [
    "parse_apk",
    "validate_version_code",
    "ApkParseError",
    "VersionCodeError",
    "download_apk_from_gcs",
    "cleanup_apk",
]
```

---

## Step 6 — update main.py

Replace `scanner/main.py` entirely:

```python
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
```

---

## Step 7 — update Dockerfile

Replace `scanner/Dockerfile`:

```dockerfile
FROM python:3.12-slim

# Install system dependencies needed by androguard
RUN apt-get update && apt-get install -y \
    openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Create temp directory for APK downloads
RUN mkdir -p /tmp/appvault_scans

EXPOSE 8000

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
```

---

## Step 8 — write tests

Create `scanner/tests/__init__.py` (empty file):

```python
```

Create `scanner/tests/test_apk_parser.py`:

```python
import os
import pytest
from parser.apk_parser import (
    parse_apk,
    validate_version_code,
    ApkParseError,
    VersionCodeError,
)

FIXTURES_DIR = os.path.join(os.path.dirname(__file__), "fixtures")


def get_fixture(name: str) -> str:
    path = os.path.join(FIXTURES_DIR, name)
    if not os.path.exists(path):
        pytest.skip(f"Fixture not found: {name} — add a real APK to tests/fixtures/")
    return path


# ── parse_apk tests ────────────────────────────────────────────────────────

class TestParseApk:

    def test_parses_package_name(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert result.package_name
        assert "." in result.package_name  # valid package name has dots

    def test_parses_version_code(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert isinstance(result.version_code, int)
        assert result.version_code >= 0

    def test_parses_version_name(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert result.version_name
        assert isinstance(result.version_name, str)

    def test_parses_min_sdk(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert isinstance(result.min_sdk, int)
        assert result.min_sdk >= 1

    def test_parses_target_sdk(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert isinstance(result.target_sdk, int)
        assert result.target_sdk >= result.min_sdk

    def test_cert_fingerprint_format(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert result.cert_fingerprint.startswith("sha256:")
        # Should have colon-separated hex pairs
        parts = result.cert_fingerprint.replace("sha256:", "").split(":")
        assert len(parts) == 32  # SHA-256 = 32 bytes = 32 pairs

    def test_cert_fingerprint_is_stable(self):
        """Same APK must always return same fingerprint."""
        path = get_fixture("sample.apk")
        result1 = parse_apk(path)
        result2 = parse_apk(path)
        assert result1.cert_fingerprint == result2.cert_fingerprint

    def test_permissions_is_list(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        assert isinstance(result.permissions, list)

    def test_permissions_are_strings(self):
        path = get_fixture("sample.apk")
        result = parse_apk(path)
        for perm in result.permissions:
            assert isinstance(perm, str)
            assert perm.startswith("android.permission") or "." in perm

    def test_invalid_file_raises_parse_error(self):
        with pytest.raises(ApkParseError):
            parse_apk("/tmp/this_file_does_not_exist.apk")

    def test_non_apk_file_raises_parse_error(self, tmp_path):
        fake_apk = tmp_path / "fake.apk"
        fake_apk.write_bytes(b"this is not an apk file at all")
        with pytest.raises(ApkParseError):
            parse_apk(str(fake_apk))


# ── validate_version_code tests ────────────────────────────────────────────

class TestValidateVersionCode:

    def test_higher_code_passes(self):
        # Should not raise
        validate_version_code(new_code=2, existing_code=1)

    def test_same_code_raises(self):
        with pytest.raises(VersionCodeError):
            validate_version_code(new_code=1, existing_code=1)

    def test_lower_code_raises(self):
        with pytest.raises(VersionCodeError):
            validate_version_code(new_code=1, existing_code=5)

    def test_first_submission_passes(self):
        # existing_code=0 means first submission
        validate_version_code(new_code=1, existing_code=0)

    def test_error_message_contains_codes(self):
        with pytest.raises(VersionCodeError) as exc_info:
            validate_version_code(new_code=3, existing_code=10)
        assert "3" in str(exc_info.value)
        assert "10" in str(exc_info.value)
```

---

## Step 9 — get a test APK for fixtures

You need a real APK in `scanner/tests/fixtures/sample.apk` to run the tests.
Download a small open-source APK:

```bash
# On your local machine, inside the scanner directory
mkdir -p tests/fixtures

# Download a small open-source APK (F-Droid's own app — well-known, safe)
curl -L "https://f-droid.org/F-Droid.apk" -o tests/fixtures/sample.apk

# Verify it downloaded
ls -lh tests/fixtures/sample.apk
# Should be around 10MB
```

Add it to `.gitignore` — do not commit APK files to git:

```bash
# Add to scanner/.gitignore
echo "tests/fixtures/*.apk" >> .gitignore
```

---

## Step 10 — run tests locally

Install dependencies locally first (use a virtualenv):

```bash
cd scanner

python3 -m venv venv
source venv/bin/activate        # Windows: venv\Scripts\activate

pip install -r requirements.txt

# Run tests
pytest tests/ -v
```

Expected output:
```
tests/test_apk_parser.py::TestParseApk::test_parses_package_name PASSED
tests/test_apk_parser.py::TestParseApk::test_parses_version_code PASSED
tests/test_apk_parser.py::TestParseApk::test_parses_version_name PASSED
tests/test_apk_parser.py::TestParseApk::test_parses_min_sdk PASSED
tests/test_apk_parser.py::TestParseApk::test_parses_target_sdk PASSED
tests/test_apk_parser.py::TestParseApk::test_cert_fingerprint_format PASSED
tests/test_apk_parser.py::TestParseApk::test_cert_fingerprint_is_stable PASSED
tests/test_apk_parser.py::TestParseApk::test_permissions_is_list PASSED
tests/test_apk_parser.py::TestParseApk::test_permissions_are_strings PASSED
tests/test_apk_parser.py::TestParseApk::test_invalid_file_raises_parse_error PASSED
tests/test_apk_parser.py::TestParseApk::test_non_apk_file_raises_parse_error PASSED
tests/test_apk_parser.py::TestValidateVersionCode::test_higher_code_passes PASSED
tests/test_apk_parser.py::TestValidateVersionCode::test_same_code_raises PASSED
tests/test_apk_parser.py::TestValidateVersionCode::test_lower_code_raises PASSED
tests/test_apk_parser.py::TestValidateVersionCode::test_first_submission_passes PASSED
tests/test_apk_parser.py::TestValidateVersionCode::test_error_message_contains_codes PASSED

16 passed in X.XXs
```

All 16 must pass before committing.

---

## Step 11 — test the API endpoint locally

Run the scanner locally:

```bash
cd scanner
source venv/bin/activate
uvicorn main:app --reload --port 8000
```

Test the upload endpoint (easier than GCS for local testing):

```bash
# Test health
curl http://localhost:8000/health

# Test parse via file upload (use the fixture APK)
curl -X POST http://localhost:8000/parse/upload \
  -F "file=@tests/fixtures/sample.apk"

# Expected response:
# {
#   "package_name": "org.fdroid.fdroid",
#   "version_code": 1014051,
#   "version_name": "1.20.0",
#   "min_sdk": 22,
#   "target_sdk": 34,
#   "cert_fingerprint": "sha256:43:23:...",
#   "permissions": ["android.permission.INTERNET", ...],
#   "app_name": "F-Droid",
#   "icon_base64": "iVBORw0KGgo..."
# }

# Test version code validation — should fail with 422
curl -X POST http://localhost:8000/parse/upload \
  -F "file=@tests/fixtures/sample.apk" \
  -F "existing_version_code=9999999"
# Expected: 422 {"detail":"Version code X must be greater than existing version code 9999999"}

# Test with a non-APK file — should fail with 400
echo "not an apk" > /tmp/fake.apk
curl -X POST http://localhost:8000/parse/upload \
  -F "file=@/tmp/fake.apk"
# Expected: 400 {"detail":"Could not parse APK: ..."}
```

---

## Step 12 — commit and deploy

```bash
git add .
git commit -m "feat: Sprint 3 — APK parser with androguard, FastAPI /parse endpoint"
git push origin main
```

GitHub Actions deploys to scanner-vm automatically. After deployment:

```bash
# Verify health on live scanner
curl https://scanner.appvault.online/health

# Test live parse endpoint with the F-Droid APK
# First upload to GCS so the scanner can fetch it
gsutil cp tests/fixtures/sample.apk gs://appvault-files/test/sample.apk

# Then call the /parse endpoint with the GCS URL
curl -X POST https://scanner.appvault.online/parse \
  -H "Content-Type: application/json" \
  -d '{"apk_url": "gs://appvault-files/test/sample.apk"}'
```

---

## Step 13 — scanner-vm needs GCS credentials

The scanner needs the GCS service account to download APKs. On scanner-vm:

```bash
ssh -i ~/.ssh/appvault_deploy ubuntu@YOUR_SCANNER_VM_IP

# Pull the scanner service account key from Secret Manager
gcloud secrets versions access latest \
  --secret="gcs-scanner-service-account" > /tmp/scanner-sa-key.json

# Set the env variable so google-cloud-storage picks it up
echo "GOOGLE_APPLICATION_CREDENTIALS=/home/ubuntu/scanner/scanner-sa-key.json" \
  >> /home/ubuntu/scanner/.env

# Move the key to the scanner directory
mv /tmp/scanner-sa-key.json /home/ubuntu/scanner/scanner-sa-key.json
chmod 600 /home/ubuntu/scanner/scanner-sa-key.json
```

Update `scanner/docker-compose.yml` to mount the credentials file and pass the env var:

```yaml
services:
  scanner:
    image: ghcr.io/YOUR_GITHUB_USERNAME/appvault-scanner:latest
    container_name: appvault-scanner
    restart: unless-stopped
    environment:
      GOOGLE_APPLICATION_CREDENTIALS: /secrets/scanner-sa-key.json
    volumes:
      - /home/ubuntu/scanner/scanner-sa-key.json:/secrets/scanner-sa-key.json:ro
      - /tmp/appvault_scans:/tmp/appvault_scans
    ports:
      - "8000:8000"
```

Restart:

```bash
cd /home/ubuntu/scanner
docker compose up -d --force-recreate scanner
```

---

## Sprint 3 definition of done checklist

```
[ ] pytest runs — all 16 tests pass locally
[ ] GET /health returns 200 on scanner-vm
[ ] POST /parse/upload with valid APK returns correct packageName
[ ] POST /parse/upload with valid APK returns cert fingerprint starting with sha256:
[ ] Same APK parsed twice returns identical certFingerprint
[ ] POST /parse/upload with existing_version_code higher than APK returns 422
[ ] POST /parse/upload with a text file returns 400
[ ] POST /parse with a valid GCS URL returns parsed metadata
[ ] Temp APK file is deleted after every parse (no disk leak)
[ ] Scanner deployed to scanner-vm via GitHub Actions
```

---

## Troubleshooting

### "ModuleNotFoundError: No module named 'parser'"
You are running pytest or uvicorn from the wrong directory.
Always `cd scanner` first, then run commands.

### androguard takes 30+ seconds on first parse
Normal — androguard loads slowly on first import. Subsequent calls are fast.
The `--workers 2` in the Dockerfile keeps one warm worker ready.

### "Could not extract cert fingerprint"
Some debug/unsigned APKs have no certificate. The parser returns
`sha256:no-certificate` gracefully. Production APKs will always have a cert.

### GCS download fails with credentials error
Confirm `GOOGLE_APPLICATION_CREDENTIALS` points to the key file inside
the container and the file is correctly mounted as a volume.

### Androguard install fails on ARM (Apple Silicon Mac)
```bash
pip install androguard==3.4.0 --no-binary :all:
```