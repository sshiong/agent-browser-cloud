import math
import threading
import unittest
from unittest.mock import Mock, patch

from agent_worker import PollBackoff, WorkerError, WorkerLoop, run_poll_loop
from reviewer_worker import ReviewerLoop
from vision_worker import VisionLoop


class PollBackoffTest(unittest.TestCase):
    def test_bounded_exponential_equal_jitter_and_reset(self):
        backoff = PollBackoff(2)
        with patch("agent_worker.random.uniform", side_effect=lambda low, high: high):
            self.assertEqual([backoff.next_delay() for _ in range(7)], [2, 4, 8, 16, 30, 30, 30])
            backoff.reset()
            self.assertEqual(backoff.next_delay(), 2)
        for _ in range(100):
            self.assertGreaterEqual(backoff.next_delay(), 1)
            self.assertLessEqual(backoff.next_delay(), 30)

    def test_non_finite_values_rejected(self):
        for value in (math.nan, math.inf, -math.inf):
            with self.assertRaises(ValueError):
                PollBackoff(value)

    def test_idle_errors_back_off_and_work_resets_without_sleep(self):
        run_once = Mock(side_effect=[False, WorkerError("UNAVAILABLE"), True, False, KeyboardInterrupt])
        with patch("agent_worker.random.uniform", side_effect=lambda low, high: high), patch("agent_worker.time.sleep") as sleep:
            with self.assertRaises(KeyboardInterrupt):
                run_poll_loop(run_once, False, 2)
        self.assertEqual([call.args[0] for call in sleep.call_args_list], [2, 4, 2])

    def test_once_never_sleeps_even_on_error(self):
        with patch("agent_worker.time.sleep") as sleep:
            run_poll_loop(Mock(side_effect=WorkerError("UNAVAILABLE")), True, 2)
            sleep.assert_not_called()

    def test_all_three_workers_use_shared_backoff(self):
        for cls, module in ((WorkerLoop, "agent_worker"), (ReviewerLoop, "reviewer_worker"), (VisionLoop, "vision_worker")):
            loop = cls.__new__(cls)
            loop.poll_seconds = 2
            with patch(f"{module}.run_poll_loop") as run:
                loop.run(True)
                run.assert_called_once_with(loop.run_once, True, 2)


class VisionLeaseTest(unittest.TestCase):
    def loop(self, lose_during):
        failed = threading.Event()
        client = Mock(deployment_id="vision-test")
        client.claim.return_value = {"screenshotUrl": "http://127.0.0.1/evidence"}

        def transition(claim, action, *args):
            if action == "heartbeat":
                failed.set()
                raise WorkerError("CHALLENGE_VISION_JOB_LEASE_EXPIRED", retryable=False)
            return {}

        client.transition.side_effect = transition
        provider = Mock()

        def wait_for_failure(*args):
            self.assertTrue(failed.wait(2), "heartbeat did not run")
            return b"redacted" if lose_during == "download" else {}

        provider.download.return_value = b"redacted"
        provider.analyze.return_value = {}
        getattr(provider, lose_during).side_effect = wait_for_failure
        loop = VisionLoop(client, provider, "test", [], 2, 1)
        loop.heartbeat_seconds = 0.01
        return loop, client, provider

    def test_lease_loss_during_download_prevents_model_call(self):
        loop, client, provider = self.loop("download")
        with self.assertRaisesRegex(WorkerError, "CHALLENGE_VISION_LEASE_LOST"):
            loop.run_once()
        provider.analyze.assert_not_called()
        self.assertEqual([call.args[1] for call in client.transition.call_args_list], ["start", "heartbeat"])

    def test_lease_loss_during_model_call_prevents_complete_and_fail(self):
        loop, client, _ = self.loop("analyze")
        with self.assertRaisesRegex(WorkerError, "CHALLENGE_VISION_LEASE_LOST"):
            loop.run_once()
        self.assertEqual([call.args[1] for call in client.transition.call_args_list], ["start", "heartbeat"])

    def test_unexpected_provider_failure_always_stops_heartbeat(self):
        client = Mock(deployment_id="vision-test")
        client.claim.return_value = {"screenshotUrl": "http://127.0.0.1/evidence"}
        provider = Mock()
        provider.download.side_effect = ValueError("invalid provider result")
        before = set(threading.enumerate())
        with self.assertRaises(ValueError):
            VisionLoop(client, provider, "test", [], 2, 1).run_once()
        self.assertEqual(set(threading.enumerate()), before)


if __name__ == "__main__":
    unittest.main()
