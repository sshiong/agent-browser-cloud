import importlib.util
import json
import pathlib
import subprocess
import time
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlencode, urlsplit

ROOT = pathlib.Path(__file__).parents[1]
SPEC = importlib.util.spec_from_file_location("oidc_client_test", ROOT / "fixtures" / "public-oidc-client.py")
OIDC = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(OIDC)


class PublicOidcTest(unittest.TestCase):
    def begin(self):
        client = OIDC.PublicOidcClient()
        metadata = {**OIDC.ENDPOINTS, "response_modes_supported": ["form_post"]}
        with patch.object(OIDC, "provider_request", return_value=(200, metadata)):
            authorize = client.begin()
        query = parse_qs(urlsplit(authorize).query)
        self.assertEqual(query["scope"], ["openid profile"])
        self.assertEqual(query["response_mode"], ["form_post"])
        self.assertEqual(query["code_challenge_method"], ["S256"])
        self.assertNotIn(client.flow["verifier"], authorize)
        return client, query

    def test_rejects_wrong_duplicate_or_expired_callback_state_without_provider_calls(self):
        for variant in ("wrong", "duplicate", "expired", "issuer"):
            with self.subTest(variant=variant):
                client, query = self.begin()
                body = urlencode({"state": query["state"][0], "code": "ephemeral-test-code"})
                if variant == "wrong":
                    body = urlencode({"state": "wrong-state", "code": "ephemeral-test-code"})
                elif variant == "duplicate":
                    body += "&state=duplicate-state"
                elif variant == "expired":
                    client.flow["expires"] = time.monotonic() - 1
                else:
                    body += "&iss=https%3A%2F%2Fattacker.invalid"
                with patch.object(OIDC, "provider_request") as provider:
                    with self.assertRaisesRegex(ValueError, "OIDC_CALLBACK_REJECTED"):
                        client.complete(body.encode())
                    provider.assert_not_called()
                self.assertIsNone(client.proof)

    def test_consumes_callback_before_exchange_and_requires_code_replay_rejection(self):
        for replay in ((400, {"error": "invalid_grant"}), (200, {"access_token": "unexpected"})):
            client, query = self.begin()
            body = urlencode({"state": query["state"][0], "code": "ephemeral-test-code"}).encode()
            tokens = {"token_type": "Bearer", "access_token": "ephemeral-access", "id_token": "ephemeral-id"}
            verified = {"signature": True, "issuerAudienceNonce": True, "userinfoSubject": True}
            with patch.object(OIDC, "provider_request", side_effect=[(200, tokens), (200, {}), (200, {}), replay]), \
                 patch.object(OIDC.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, json.dumps(verified), "")):
                if replay[0] == 400:
                    self.assertEqual(client.complete(body), OIDC.RESULT)
                    self.assertTrue(client.proof["reusedCodeRejected"])
                    self.assertNotIn("ephemeral", json.dumps(vars(client), default=str))
                else:
                    with self.assertRaisesRegex(ValueError, "OIDC_CODE_REPLAY_NOT_REJECTED"):
                        client.complete(body)
                    self.assertIsNone(client.proof)
            with patch.object(OIDC, "provider_request") as provider:
                with self.assertRaisesRegex(ValueError, "OIDC_CALLBACK_REJECTED"):
                    client.complete(body)
                provider.assert_not_called()

    def test_discovery_cannot_redirect_tokens_to_another_host(self):
        client = OIDC.PublicOidcClient()
        metadata = {**OIDC.ENDPOINTS, "token_endpoint": "https://attacker.invalid/token"}
        with patch.object(OIDC, "provider_request", return_value=(200, metadata)):
            with self.assertRaisesRegex(ValueError, "OIDC_DISCOVERY_REJECTED"):
                client.begin()
        with self.assertRaisesRegex(ValueError, "OIDC_ENDPOINT_REJECTED"):
            OIDC.provider_request("https://attacker.invalid/token")
        self.assertIsNone(client.flow)

    def test_jwt_signature_and_claim_attack_matrix(self):
        result = subprocess.run(["node", "--test", str(ROOT / "validation" / "public-oidc-verifier.test.mjs")],
                                text=True, capture_output=True, timeout=30, check=False)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
