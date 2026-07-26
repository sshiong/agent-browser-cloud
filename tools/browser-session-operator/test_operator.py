#!/usr/bin/env python3
"""Unit tests for BrowserSession operator lease election."""

import datetime
import importlib.util
import os
import pathlib
import sys
import unittest
import urllib.error
from unittest import mock

os.environ.setdefault("CONTROL_PLANE_URL", "http://control-plane.test")
os.environ.setdefault("CONTROL_PLANE_TOKEN", "test-token")

MODULE_PATH = pathlib.Path(__file__).with_name("browser_session_operator.py")
SPEC = importlib.util.spec_from_file_location("browser_session_operator", MODULE_PATH)
operator = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = operator
SPEC.loader.exec_module(operator)


def http_error(code):
    return urllib.error.HTTPError("https://kubernetes.test", code, "test", {}, None)


class LeaseElectionTest(unittest.TestCase):
    def setUp(self):
        self.now = datetime.datetime(2026, 7, 26, tzinfo=datetime.timezone.utc)
        self.lease = {
            "metadata": {"resourceVersion": "42"},
            "spec": {
                "holderIdentity": "operator-a",
                "leaseDurationSeconds": 15,
                "acquireTime": "2026-07-25T23:00:00.000000Z",
                "renewTime": "2026-07-25T23:00:00.000000Z",
                "leaseTransitions": 2,
            },
        }

    @mock.patch.object(operator, "kube_request")
    def test_creates_missing_lease(self, kube_request):
        kube_request.side_effect = [http_error(404), {}]

        self.assertTrue(
            operator.try_acquire_or_renew("browsercloud-system", "operator-a", self.now)
        )

        created = kube_request.call_args_list[1].args[2]
        self.assertEqual(created["spec"]["holderIdentity"], "operator-a")
        self.assertEqual(kube_request.call_args_list[1].args[1], "POST")

    @mock.patch.object(operator, "kube_request")
    def test_renews_owned_lease(self, kube_request):
        kube_request.side_effect = [self.lease, {}]

        self.assertTrue(
            operator.try_acquire_or_renew("browsercloud-system", "operator-a", self.now)
        )

        patch = kube_request.call_args_list[1].args[2]
        self.assertEqual(patch["metadata"]["resourceVersion"], "42")
        self.assertNotIn("leaseTransitions", patch["spec"])

    @mock.patch.object(operator, "kube_request")
    def test_does_not_take_active_foreign_lease(self, kube_request):
        self.lease["spec"]["holderIdentity"] = "operator-b"
        self.lease["spec"]["renewTime"] = operator.format_kube_time(self.now)
        kube_request.return_value = self.lease

        self.assertFalse(
            operator.try_acquire_or_renew("browsercloud-system", "operator-a", self.now)
        )

        kube_request.assert_called_once()

    @mock.patch.object(operator, "kube_request")
    def test_takes_over_expired_foreign_lease(self, kube_request):
        self.lease["spec"]["holderIdentity"] = "operator-b"
        kube_request.side_effect = [self.lease, {}]

        self.assertTrue(
            operator.try_acquire_or_renew("browsercloud-system", "operator-a", self.now)
        )

        patch = kube_request.call_args_list[1].args[2]
        self.assertEqual(patch["spec"]["holderIdentity"], "operator-a")
        self.assertEqual(patch["spec"]["leaseTransitions"], 3)
        self.assertIn("acquireTime", patch["spec"])

    @mock.patch.object(operator, "kube_request")
    def test_conflict_does_not_claim_leadership(self, kube_request):
        kube_request.side_effect = [self.lease, http_error(409)]

        self.assertFalse(
            operator.try_acquire_or_renew("browsercloud-system", "operator-a", self.now)
        )


class ResourcePathTest(unittest.TestCase):
    @mock.patch.object(operator, "kube_request")
    def test_patch_uses_namespaced_resource_path(self, kube_request):
        item = {"metadata": {"namespace": "tenant-a", "name": "session-a"}}

        operator.patch_resource(item, {"metadata": {"finalizers": []}})

        self.assertEqual(
            kube_request.call_args.args[0],
            "/apis/browsercloud.io/v1alpha1/namespaces/tenant-a/"
            "browsersessions/session-a",
        )

    @mock.patch.object(operator, "kube_request")
    def test_status_patch_uses_status_subresource(self, kube_request):
        item = {"metadata": {"namespace": "tenant-a", "name": "session-a"}}

        operator.patch_resource(item, {"status": {"phase": "Ready"}}, status=True)

        self.assertEqual(
            kube_request.call_args.args[0],
            "/apis/browsercloud.io/v1alpha1/namespaces/tenant-a/"
            "browsersessions/session-a/status",
        )


if __name__ == "__main__":
    unittest.main()
