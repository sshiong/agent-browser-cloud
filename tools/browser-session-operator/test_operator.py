#!/usr/bin/env python3
"""Unit tests for BrowserSession operator lease election."""

import datetime
import importlib.util
import json
import os
import pathlib
import sys
import unittest
import urllib.error
import urllib.parse
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


class FakeWatchResponse:
    def __init__(self, events):
        self.lines = [json.dumps(event).encode() + b"\n" for event in events]

    def __enter__(self):
        return self

    def __exit__(self, *_):
        return False

    def readline(self, _limit):
        return self.lines.pop(0) if self.lines else b""


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


class ListWatchTest(unittest.TestCase):
    @mock.patch.object(operator, "kube_request")
    def test_list_uses_consistent_pagination_and_returns_snapshot_version(
        self, kube_request
    ):
        kube_request.side_effect = [
            {
                "metadata": {"resourceVersion": "100", "continue": "next page"},
                "items": [{"metadata": {"name": "a"}}],
            },
            {
                "metadata": {"resourceVersion": "100"},
                "items": [{"metadata": {"name": "b"}}],
            },
        ]

        pages = list(operator.resource_pages())

        self.assertEqual(
            ["a", "b"],
            [item["metadata"]["name"] for items, _ in pages for item in items],
        )
        self.assertEqual(["100", "100"], [version for _, version in pages])
        first = urllib.parse.parse_qs(
            urllib.parse.urlparse(kube_request.call_args_list[0].args[0]).query
        )
        second = urllib.parse.parse_qs(
            urllib.parse.urlparse(kube_request.call_args_list[1].args[0]).query
        )
        self.assertEqual(["0"], first["resourceVersion"])
        self.assertEqual(["NotOlderThan"], first["resourceVersionMatch"])
        self.assertEqual(["next page"], second["continue"])
        self.assertNotIn("resourceVersion", second)

    @mock.patch.object(operator, "reconcile")
    def test_watch_reconciles_events_and_advances_bookmark(self, reconcile):
        response = FakeWatchResponse(
            [
                {
                    "type": "ADDED",
                    "object": {"metadata": {"name": "a", "resourceVersion": "101"}},
                },
                {
                    "type": "MODIFIED",
                    "object": {"metadata": {"name": "a", "resourceVersion": "102"}},
                },
                {
                    "type": "BOOKMARK",
                    "object": {"metadata": {"resourceVersion": "105"}},
                },
            ]
        )

        version = operator.consume_watch(response, "100")

        self.assertEqual("105", version)
        self.assertEqual(2, reconcile.call_count)

    def test_watch_410_requires_a_fresh_list(self):
        response = FakeWatchResponse(
            [{"type": "ERROR", "object": {"code": 410, "message": "Gone"}}]
        )

        with self.assertRaises(operator.ResourceVersionExpired):
            operator.consume_watch(response, "100")

    @mock.patch.object(operator, "open_kube_watch")
    @mock.patch.object(operator, "reconcile")
    def test_watch_request_is_versioned_bounded_and_bookmarked(
        self, _reconcile, open_kube_watch
    ):
        open_kube_watch.return_value = FakeWatchResponse([])

        self.assertEqual("77", operator.watch_resources("77", timeout_seconds=4))

        path, timeout = open_kube_watch.call_args.args
        query = urllib.parse.parse_qs(urllib.parse.urlparse(path).query)
        self.assertEqual(4, timeout)
        self.assertEqual(["1"], query["watch"])
        self.assertEqual(["true"], query["allowWatchBookmarks"])
        self.assertEqual(["77"], query["resourceVersion"])
        self.assertEqual(["4"], query["timeoutSeconds"])
        self.assertNotIn("resourceVersionMatch", query)

    def test_backoff_is_bounded_and_resets_after_success(self):
        backoff = operator.BoundedBackoff(minimum=1, maximum=4)
        self.assertEqual([1, 2, 4, 4], [backoff.failure() for _ in range(4)])
        backoff.success()
        self.assertEqual(1, backoff.failure())


class ReconcileContractTest(unittest.TestCase):
    @mock.patch.object(operator, "patch_resource")
    @mock.patch.object(operator, "control_request")
    def test_create_uses_auto_policy_and_separate_execution_environment(
        self, control_request, patch_resource
    ):
        control_request.return_value = {"sessionId": "ses_1"}
        item = {
            "metadata": {
                "name": "session-a",
                "namespace": "tenant-a",
                "uid": "uid-a",
                "generation": 3,
                "finalizers": [operator.FINALIZER],
            },
            "spec": {
                "tenantId": "tenant-a",
                "profileId": "profile-a",
                "executionEnvironment": "CONTAINER",
                "resourcePolicy": {"onMaximumReached": "PAUSE_AGENT"},
            },
        }

        operator.reconcile(item)

        body = control_request.call_args.args[2]
        self.assertNotIn("resourceClass", body)
        self.assertEqual(
            {
                "mode": "AUTO",
                "executionEnvironment": "CONTAINER",
                "onMaximumReached": "PAUSE_AGENT",
            },
            body["resourcePolicy"],
        )
        self.assertEqual("ses_1", patch_resource.call_args.args[1]["status"]["sessionId"])

    @mock.patch.object(operator, "patch_resource")
    @mock.patch.object(operator, "control_request")
    def test_stable_ready_resource_does_not_create_a_watch_feedback_loop(
        self, control_request, patch_resource
    ):
        item = {
            "metadata": {
                "name": "session-a",
                "namespace": "tenant-a",
                "uid": "uid-a",
                "generation": 3,
                "finalizers": [operator.FINALIZER],
            },
            "spec": {"tenantId": "tenant-a", "profileId": "profile-a"},
            "status": {
                "sessionId": "ses_1",
                "phase": "Ready",
                "observedGeneration": 3,
                "lastError": "",
            },
        }

        operator.reconcile(item)

        control_request.assert_not_called()
        patch_resource.assert_not_called()


if __name__ == "__main__":
    unittest.main()
