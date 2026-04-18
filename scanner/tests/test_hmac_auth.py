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