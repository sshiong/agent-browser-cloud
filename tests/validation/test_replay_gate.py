import copy
import json
import pathlib
import unittest

from replay_gate import ReplayGate


DATASET = json.loads((pathlib.Path(__file__).parent / "replay-dataset-v1.json").read_text())


class ReplayGateTest(unittest.TestCase):
    def test_every_declared_case_needs_runtime_evidence(self):
        gate = ReplayGate(DATASET)
        for case in DATASET["cases"][:-1]:
            gate.pass_case(case["caseId"])
        with self.assertRaisesRegex(ValueError, "REPLAY_CASE_EVIDENCE_MISSING"):
            gate.required_tests()
        gate.pass_case(DATASET["cases"][-1]["caseId"])
        self.assertEqual(gate.required_tests(), len(DATASET["cases"]))
        with self.assertRaisesRegex(ValueError, "REPLAY_CASE_EVIDENCE_INVALID"):
            gate.pass_case(DATASET["cases"][-1]["caseId"])

    def test_opaque_policy_cannot_expand_to_sensitive_actions(self):
        for field, value in (
            ("allowedAction", "KEYBOARD"),
            ("frameOrigin", "http://another-provider.invalid"),
            ("forbiddenActions", ["TEXT"]),
            ("allowedDomains", ["agent-controls.invalid", "another-provider.invalid"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["kind"] == "SYNTHETIC_OPAQUE_FRAME")
            case[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "REPLAY_OPAQUE_POLICY_INVALID"):
                ReplayGate(dataset)

    def test_missing_or_unsafe_authorization_is_rejected(self):
        for mutation in (
            lambda value: value["authorization"].update(credentials=True),
            lambda value: value.update(containsProductionData=True),
            lambda value: value["cases"].append(copy.deepcopy(value["cases"][0])),
        ):
            dataset = copy.deepcopy(DATASET)
            mutation(dataset)
            with self.assertRaises(ValueError):
                ReplayGate(dataset)


if __name__ == "__main__":
    unittest.main()
