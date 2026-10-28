"""Exercise the exact smoke completion block with an owned curl stub, without a server."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class OutcomeCompletionRetryTests(unittest.TestCase):
    def execute(self, responses):
        source = Path(__file__).with_name("smoke.sh").read_text()
        block = source[source.index('false_outcome_complete_body='):source.index(
            'curl -fsS "http://localhost:${control_b_port}/api/v1/agent-tasks/${false_success_task_id}"')]
        with tempfile.TemporaryDirectory(prefix="browsercloud-outcome-retry-") as folder:
            root = Path(folder)
            (root / "responses.json").write_text(json.dumps(responses))
            stub = root / "curl"
            stub.write_text('''#!/usr/bin/env python3
import json, os, pathlib, sys
root=pathlib.Path(os.environ["OWNED_RETRY_ROOT"])
a=sys.argv[1:]
log=root/"calls.json"
calls=json.loads(log.read_text()) if log.exists() else []
calls.append(a[a.index("-d")+1])
log.write_text(json.dumps(calls))
responses=json.loads((root/"responses.json").read_text())
status,body=responses[min(len(calls)-1,len(responses)-1)]
if status==0: sys.exit(28)
pathlib.Path(a[a.index("-o")+1]).write_text(json.dumps(body))
print(status,end="")
''')
            stub.chmod(0o700)
            env = dict(os.environ, OWNED_RETRY_ROOT=folder, PATH=folder + os.pathsep + os.environ["PATH"])
            setup = 'temp_dir="$OWNED_RETRY_ROOT"; control_b_port=1; false_outcome_job_id=owned; false_outcome_token=owned; false_outcome_hash=owned; sleep(){ :; };\n'
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", setup + block], env=env,
                                    capture_output=True, text=True, timeout=10)
            calls = json.loads((root / "calls.json").read_text())
            self.assertEqual(len(set(calls)), 1, "A retry changed the original body")
            return result.returncode, len(calls)

    def test_retries_only_explicit_rolled_back_transaction(self):
        transient = (503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}})
        rejected = (200, {"state": "NOT_VERIFIED", "decision": "NOT_VERIFIED", "reasonCodes": ["EXPECTED_OUTCOME_NOT_MET"]})
        self.assertEqual(self.execute([transient, rejected]), (0, 2))
        self.assertEqual(self.execute([rejected]), (0, 1))

    def test_repeated_conflicts_remain_bounded_failure(self):
        code, count = self.execute([(503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}})])
        self.assertNotEqual(code, 0)
        self.assertEqual(count, 5)

    def test_other_failures_and_false_success_are_never_retried_or_accepted(self):
        for response in [
            (503, {"code": "DATABASE_UNAVAILABLE"}),
            (503, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": 1}}),
            (503, {"code": "DATABASE_TRANSACTION_RETRY"}),
            (409, {"code": "DATABASE_TRANSACTION_RETRY", "details": {"retryable": True}}),
            (200, {"state": "VERIFIED", "decision": "VERIFIED", "reasonCodes": ["GOAL_SATISFIED"]}),
            (0, None),
        ]:
            with self.subTest(response=response):
                code, count = self.execute([response])
                self.assertNotEqual(code, 0)
                self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
