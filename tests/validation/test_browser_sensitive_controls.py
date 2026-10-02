"""Execute the production DOM predicates against isolated attribute fixtures."""

import json
import pathlib
import re
import subprocess
import unittest


ROOT = pathlib.Path(__file__).resolve().parents[2]


class BrowserSensitiveControlsTest(unittest.TestCase):
    def test_state_recording_and_screenshot_classify_private_controls(self):
        state_source = (ROOT / "apps/browser-node/crates/state-collector/src/lib.rs").read_text()
        recorder_source = (ROOT / "apps/browser-node/crates/session-recorder/src/lib.rs").read_text()
        state = re.search(r"const sensitiveFor = \(element\) => \{(.*?)\n\s*\};",
                          state_source, re.S).group(1)
        recorder_predicates = re.findall(r"const isSensitive = \(element\) => \{(.*?)\n\s*\};",
                                         recorder_source, re.S)
        self.assertEqual(len(recorder_predicates), 2)
        cases = [
            ({"type": "hidden", "name": "ReturnUrl"}, True),
            ({"name": "__RequestVerificationToken"}, True),
            ({"name": "apiKey"}, True), ({"name": "APIKey"}, True),
            ({"id": "clientSecret"}, True), ({"id": "accessToken"}, True),
            ({"name": "oneTimeCode"}, True), ({"type": "password"}, True),
            ({"autocomplete": "section-login one-time-code"}, True),
            ({"data-private": ""}, True),
            ({"name": "tokenizationChoice"}, False),
            ({"name": "shippingName", "placeholder": "Name"}, False),
            ({"type": "submit", "value": "Login"}, False),
        ]
        for index, body in enumerate([state, *recorder_predicates]):
            # Recorder predicates declare their keyword/autocomplete tables outside the function.
            declarations = ""
            if index:
                start = recorder_source.index("  const sensitiveName =")
                end = recorder_source.index("  const candidateSelector =", start)
                declarations = recorder_source[start:end]
            script = declarations + "\nconst predicate = (element) => {" + body + "};\n" + """
const cases = JSON.parse(process.argv[1]);
console.log(JSON.stringify(cases.map(([attrs]) => predicate({
  getAttribute: (key) => attrs[key] ?? null,
  hasAttribute: (key) => Object.prototype.hasOwnProperty.call(attrs, key),
  matches: () => ['data-sensitive', 'data-private', 'data-redact'].some(
    key => Object.prototype.hasOwnProperty.call(attrs, key))
}))));
"""
            run = subprocess.run(["node", "-e", script, json.dumps(cases)],
                                 check=True, text=True, capture_output=True, timeout=10)
            with self.subTest(predicate=index):
                self.assertEqual(json.loads(run.stdout), [expected for _, expected in cases])


if __name__ == "__main__":
    unittest.main()
