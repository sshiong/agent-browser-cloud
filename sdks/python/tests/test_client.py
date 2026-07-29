import json
import unittest

from browsercloud import BrowserCloudClient, BrowserCloudError


class BrowserCloudClientTest(unittest.TestCase):
    def test_create_session_sends_tenant_and_idempotency(self):
        calls = []

        def transport(method, url, headers, body):
            calls.append((method, url, headers, json.loads(body)))
            return 201, {}, b'{"sessionId":"ses_1234567890abcdef"}'

        client = BrowserCloudClient(
            "https://browsercloud.example",
            tenant_id="tenant-a",
            actor_id="operator-a",
            transport=transport,
        )
        result = client.create_session(
            profile_id="profile-a",
            region="local",
            idempotency_key="idem-test",
        )

        self.assertEqual(result["sessionId"], "ses_1234567890abcdef")
        method, url, headers, body = calls[0]
        self.assertEqual(method, "POST")
        self.assertEqual(url, "https://browsercloud.example/api/v1/sessions")
        self.assertEqual(headers["X-Tenant-Id"], "tenant-a")
        self.assertEqual(headers["X-Actor-Id"], "operator-a")
        self.assertEqual(headers["Idempotency-Key"], "idem-test")
        self.assertEqual(body["profileId"], "profile-a")
        self.assertEqual(body["resourcePolicy"], {"mode": "AUTO"})
        self.assertNotIn("resourceClass", body)

    def test_preserves_structured_errors(self):
        def transport(method, url, headers, body):
            return (
                503,
                {},
                b'{"code":"CAPACITY_UNAVAILABLE","message":"closed","requestId":"req-1"}',
            )

        client = BrowserCloudClient(
            "https://browsercloud.example",
            tenant_id="tenant-a",
            transport=transport,
        )
        with self.assertRaises(BrowserCloudError) as raised:
            client.start_session("ses_1234567890abcdef")
        self.assertEqual(raised.exception.status, 503)
        self.assertEqual(raised.exception.code, "CAPACITY_UNAVAILABLE")
        self.assertEqual(raised.exception.request_id, "req-1")


if __name__ == "__main__":
    unittest.main()
