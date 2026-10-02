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

    def test_public_form_case_is_locked_to_selenium_site(self):
        dataset = copy.deepcopy(DATASET)
        case = next(item for item in dataset["cases"] if item["kind"] == "PUBLIC_FORM")
        case["url"] = "https://other.example/form"
        with self.assertRaisesRegex(ValueError, "REPLAY_PUBLIC_FORM_POLICY_INVALID"):
            ReplayGate(dataset)

    def test_public_login_cases_are_locked_to_practice_site_and_outcomes(self):
        for case_id, field, value in (
            ("public-expandtesting-login-invalid-password", "expectedPath", "/secure"),
            ("public-expandtesting-login-success", "allowedDomains", ["other.example"]),
            ("public-expandtesting-login-success", "requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["caseId"] == case_id)
            case[field] = value
            with self.subTest(case_id=case_id, field=field), self.assertRaisesRegex(
                ValueError, "REPLAY_PUBLIC_LOGIN_POLICY_INVALID"
            ):
                ReplayGate(dataset)

    def test_public_otp_cases_are_locked_to_practice_site_and_outcomes(self):
        for case_id, field, value in (
            ("public-expandtesting-otp-invalid", "expectedPath", "/secure"),
            ("public-expandtesting-otp-success", "url", "https://other.example/otp-login"),
            ("public-expandtesting-otp-success", "requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["caseId"] == case_id)
            case[field] = value
            with self.subTest(case_id=case_id, field=field), self.assertRaisesRegex(
                ValueError, "REPLAY_PUBLIC_OTP_POLICY_INVALID"
            ):
                ReplayGate(dataset)

    def test_public_spa_case_is_locked_to_browser_testing_demo(self):
        for field, value in (
            ("url", "https://other.example/todomvc/"),
            ("allowedDomains", ["other.example"]),
            ("expectedRoute", "#/active"),
            ("requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["kind"] == "PUBLIC_SPA")
            case[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                ValueError, "REPLAY_PUBLIC_SPA_POLICY_INVALID"
            ):
                ReplayGate(dataset)

    def test_public_idp_case_is_locked_to_official_demo(self):
        for field, value in (
            ("url", "https://other.example/Account/Login"),
            ("allowedDomains", ["other.example"]),
            ("expectedPath", "/Account/Logout"),
            ("requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["kind"] == "PUBLIC_IDP_DEMO")
            case[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                ValueError, "REPLAY_PUBLIC_IDP_DEMO_POLICY_INVALID"
            ):
                ReplayGate(dataset)

    def test_public_commerce_case_is_locked_to_sauce_demo(self):
        for field, value in (
            ("url", "https://other.example/"),
            ("allowedDomains", ["other.example"]),
            ("expectedCartPath", "/checkout-complete.html"),
            ("requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["kind"] == "PUBLIC_COMMERCE_DEMO")
            case[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(
                ValueError, "REPLAY_PUBLIC_COMMERCE_DEMO_POLICY_INVALID"
            ):
                ReplayGate(dataset)

    def test_oidc_replay_cannot_change_issuer_client_callback_scopes_or_response_mode(self):
        for field, value in (
            ("issuer", "https://attacker.invalid"), ("clientId", "m2m"),
            ("redirectUri", "https://attacker.invalid/callback"), ("scope", "openid offline_access"),
            ("responseMode", "query"), ("allowedDomains", ["demo.duendesoftware.com"]),
            ("requiredControls", ["NAVIGATE"]),
        ):
            dataset = copy.deepcopy(DATASET)
            case = next(item for item in dataset["cases"] if item["kind"] == "PUBLIC_OIDC_DEMO")
            case[field] = value
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "REPLAY_PUBLIC_OIDC_POLICY_INVALID"):
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
