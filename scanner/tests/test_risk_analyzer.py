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