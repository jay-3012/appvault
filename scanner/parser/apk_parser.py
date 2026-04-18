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
    try:
        app_name = apk.get_app_name() or package_name
    except Exception as e:
        logger.warning("Could not extract app name, using package name: %s", e)
        app_name = package_name

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
