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