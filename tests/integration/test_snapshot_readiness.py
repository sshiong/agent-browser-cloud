"""Keep the smoke WAIT snapshot readiness loop strict and bounded."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


class SnapshotReadinessTests(unittest.TestCase):
    def execute(self, responses):
        source = Path(__file__).with_name("smoke.sh").read_text()
        begin = source.index('  high_level_wait_snapshot_status=""')
        end = source.index('  high_level_wait_request=', begin)
        with tempfile.TemporaryDirectory(prefix="browsercloud-snapshot-ready-") as folder:
            root = Path(folder)
            (root / "responses.json").write_text(json.dumps(responses))
            stub = root / "curl"
            stub.write_text('''#!/usr/bin/env python3
import json,os,pathlib,sys
root=pathlib.Path(os.environ["OWNED_SNAPSHOT_ROOT"])
f=root/"count"
n=int(f.read_text())+1 if f.exists() else 1
f.write_text(str(n))
a=sys.argv[1:]
assert "-X" not in a and "-d" not in a
items=json.loads((root/"responses.json").read_text())
status,body=items[min(n-1,len(items)-1)]
if status==0: sys.exit(28)
pathlib.Path(a[a.index("-o")+1]).write_text(json.dumps(body))
print(status,end="")
''')
            stub.chmod(0o700)
            env = dict(os.environ, OWNED_SNAPSHOT_ROOT=folder, PATH=folder+os.pathsep+os.environ["PATH"])
            setup = 'temp_dir="$OWNED_SNAPSHOT_ROOT"; control_port=1; session_one=owned; sleep(){ :; };\n'
            result = subprocess.run(["bash", "-euo", "pipefail", "-c", setup+source[begin:end]],
                                    env=env, capture_output=True, text=True, timeout=10)
            return result.returncode, int((root / "count").read_text())

    def test_waits_only_for_explicit_stale_perception_then_requires_success(self):
        stale = (422, {"code":"AGENT_BROWSER_PERCEPTION_REJECTED", "details":{"reason":"BROWSER_STATE_STALE"}})
        self.assertEqual(self.execute([stale, (200, {"state":{"freshness":"FRESH"}})]), (0, 2))

    def test_stale_budget_is_bounded(self):
        result, count = self.execute([(422, {"code":"AGENT_BROWSER_PERCEPTION_REJECTED", "details":{"reason":"BROWSER_STATE_STALE"}})])
        self.assertNotEqual(result, 0)
        self.assertEqual(count, 40)

    def test_other_rejections_and_transport_errors_are_not_hidden(self):
        for response in [(422, {"code":"AGENT_BROWSER_PERCEPTION_REJECTED", "details":{"reason":"BROWSER_STATE_NOT_EXECUTABLE"}}),
                         (422, {}), (409, {}), (503, {}), (0, None)]:
            with self.subTest(response=response):
                result, count = self.execute([response])
                self.assertNotEqual(result, 0)
                self.assertEqual(count, 1)


if __name__ == "__main__":
    unittest.main()
