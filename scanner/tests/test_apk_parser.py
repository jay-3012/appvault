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
