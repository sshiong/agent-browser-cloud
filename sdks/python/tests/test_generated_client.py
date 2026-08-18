import json
import unittest

from browsercloud import ApiError, BrowserCloudGeneratedClient, OPERATIONS
from browsercloud.generated_models import ProxyRoutingDecision, SessionView


class GeneratedClientTest(unittest.TestCase):
    def test_contract_surface_and_runtime_request(self):
        captured = {}

        def transport(method, url, headers, body):
            captured.update(method=method, url=url, headers=headers, body=body)
            return 200, {"Content-Type": "application/json"}, b'{"sessionId":"ses_1"}'

        client = BrowserCloudGeneratedClient(
            "https://browser.example",
            tenant_id="tenant-a",
            actor_id="actor-a",
            transport=transport,
        )
        result = client.getSession(path={"sessionId": "ses_1"})
        self.assertEqual(213, len(OPERATIONS))
        self.assertEqual("GET", captured["method"])
        self.assertEqual(
            "https://browser.example/api/v1/sessions/ses_1", captured["url"]
        )
        self.assertEqual("tenant-a", captured["headers"]["X-Tenant-Id"])
        self.assertEqual("actor-a", captured["headers"]["X-Actor-Id"])
        self.assertEqual({"sessionId": "ses_1"}, result)
        self.assertTrue(issubclass(SessionView, dict))
        self.assertTrue(issubclass(ProxyRoutingDecision, dict))

    def test_query_allowlist_and_structured_error(self):
        client = BrowserCloudGeneratedClient(
            "https://browser.example",
            tenant_id="tenant-a",
            transport=lambda *_: (
                409,
                {"Content-Type": "application/json"},
                json.dumps(
                    {"code": "VERSION_CONFLICT", "message": "conflict", "requestId": "req-1"}
                ).encode(),
            ),
        )
        with self.assertRaisesRegex(ValueError, "unknown query parameter"):
            client.listSessions(query={"notInContract": "value"})
        with self.assertRaisesRegex(ValueError, "identity-controlled header"):
            client.getSession(
                path={"sessionId": "ses_1"}, headers={"X-Tenant-Id": "tenant-b"}
            )
        with self.assertRaisesRegex(ValueError, "request body is required"):
            client.createSession(headers={"Idempotency-Key": "idem-1"})
        with self.assertRaises(ApiError) as raised:
            client.getSession(path={"sessionId": "ses_1"})
        self.assertEqual(409, raised.exception.status)
        self.assertEqual("VERSION_CONFLICT", raised.exception.code)
        self.assertEqual("req-1", raised.exception.request_id)


if __name__ == "__main__":
    unittest.main()
