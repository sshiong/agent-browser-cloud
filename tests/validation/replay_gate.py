"""Fail-closed accounting for the real-browser replay dataset.

The gate records only cases whose runtime assertions completed. A validation worker must
not claim that every declared case passed merely because the overall script reached its end.
"""

from urllib.parse import urlsplit


EXPECTED_CASES = {
    "public-example-navigation": "PUBLIC_PAGE",
    "public-w3c-navigation": "PUBLIC_PAGE",
    "public-cloudflare-trace": "PUBLIC_PAGE",
    "public-selenium-form": "PUBLIC_FORM",
    "public-expandtesting-login-invalid-password": "PUBLIC_LOGIN",
    "public-expandtesting-login-success": "PUBLIC_LOGIN",
    "public-expandtesting-otp-invalid": "PUBLIC_OTP",
    "public-expandtesting-otp-success": "PUBLIC_OTP",
    "public-playwright-todomvc-spa": "PUBLIC_SPA",
    "public-saucedemo-cart": "PUBLIC_COMMERCE_DEMO",
    "synthetic-form-controls": "SYNTHETIC_CONTROL",
    "synthetic-simple-challenge": "SYNTHETIC_CHALLENGE",
    "synthetic-opaque-frame-single-click": "SYNTHETIC_OPAQUE_FRAME",
    "cross-domain-fail-closed": "SECURITY_NEGATIVE",
    "non-allowlisted-plan": "SECURITY_NEGATIVE",
}


class ReplayGate:
    def __init__(self, dataset):
        if dataset.get("containsProductionData") is not False:
            raise ValueError("REPLAY_PRODUCTION_DATA_FORBIDDEN")
        authorization = dataset.get("authorization")
        if not isinstance(authorization, dict) or not authorization.get("basis"):
            raise ValueError("REPLAY_AUTHORIZATION_MISSING")
        if authorization.get("personalData") is not False or authorization.get("credentials") is not False:
            raise ValueError("REPLAY_SENSITIVE_DATA_FORBIDDEN")
        cases = dataset.get("cases")
        if not isinstance(cases, list) or len(cases) != len(EXPECTED_CASES):
            raise ValueError("REPLAY_CASE_SET_MISMATCH")
        ids = [case.get("caseId") for case in cases if isinstance(case, dict)]
        if len(ids) != len(EXPECTED_CASES) or set(ids) != set(EXPECTED_CASES):
            raise ValueError("REPLAY_CASE_SET_MISMATCH")
        for case in cases:
            if case.get("kind") != EXPECTED_CASES[case["caseId"]]:
                raise ValueError("REPLAY_CASE_KIND_MISMATCH")
            parsed = urlsplit(case.get("url", ""))
            if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
                raise ValueError("REPLAY_CASE_URL_INVALID")
            domains = case.get("allowedDomains")
            if not isinstance(domains, list) or not domains or any(
                not isinstance(domain, str) or domain != domain.lower() or "/" in domain
                for domain in domains
            ):
                raise ValueError("REPLAY_CASE_DOMAINS_INVALID")
            if case["kind"] == "SYNTHETIC_OPAQUE_FRAME":
                if (
                    case.get("url") != "http://agent-controls.invalid/opaque-challenge"
                    or case.get("frameOrigin") != "http://opaque-challenge.invalid"
                    or case.get("allowedAction") != "SINGLE_LEFT_CLICK"
                    or case.get("forbiddenActions") != ["TEXT", "KEYBOARD", "SECRET", "PAYMENT", "ACCOUNT_SECURITY"]
                    or domains != ["agent-controls.invalid", "opaque-challenge.invalid"]
                ):
                    raise ValueError("REPLAY_OPAQUE_POLICY_INVALID")
            if case["kind"] == "PUBLIC_FORM" and (
                case.get("url") != "https://www.selenium.dev/selenium/web/web-form.html"
                or domains != ["www.selenium.dev"]
                or case.get("requiredControls") != ["NAVIGATE", "TYPE_TEXT", "SCROLL", "CLICK_TARGET", "READ"]
            ):
                raise ValueError("REPLAY_PUBLIC_FORM_POLICY_INVALID")
            if case["kind"] == "PUBLIC_LOGIN" and (
                case.get("url") != "https://practice.expandtesting.com/login"
                or domains != ["practice.expandtesting.com"]
                or case.get("expectedPath") != (
                    "/login" if case["caseId"].endswith("invalid-password") else "/secure"
                )
                or case.get("requiredControls") != ["NAVIGATE", "SCROLL", "SECRET_USERNAME", "SECRET_PASSWORD", "CLICK_TARGET", "READ"]
            ):
                raise ValueError("REPLAY_PUBLIC_LOGIN_POLICY_INVALID")
            if case["kind"] == "PUBLIC_OTP" and (
                case.get("url") != "https://practice.expandtesting.com/otp-login"
                or domains != ["practice.expandtesting.com"]
                or case.get("expectedPath") != (
                    "/otp-verification" if case["caseId"].endswith("invalid") else "/secure"
                )
                or case.get("requiredControls") != ["NAVIGATE", "SCROLL", "SECRET_EMAIL", "CLICK_TARGET", "SECRET_OTP", "CLICK_TARGET", "READ"]
            ):
                raise ValueError("REPLAY_PUBLIC_OTP_POLICY_INVALID")
            if case["kind"] == "PUBLIC_SPA" and (
                case.get("url") != "https://demo.playwright.dev/todomvc/"
                or domains != ["demo.playwright.dev"]
                or case.get("expectedRoute") != "#/completed"
                or case.get("requiredControls") != ["NAVIGATE", "TYPE_TEXT", "PRESS_KEY", "CLICK_TARGET", "REVISIT", "READ"]
            ):
                raise ValueError("REPLAY_PUBLIC_SPA_POLICY_INVALID")
            if case["kind"] == "PUBLIC_COMMERCE_DEMO" and (
                case.get("url") != "https://www.saucedemo.com/"
                or domains != ["www.saucedemo.com"]
                or case.get("expectedCartPath") != "/cart.html"
                or case.get("requiredControls") != ["NAVIGATE", "SECRET_USERNAME", "SECRET_PASSWORD", "CLICK_TARGET", "READ"]
            ):
                raise ValueError("REPLAY_PUBLIC_COMMERCE_DEMO_POLICY_INVALID")
        self.cases = {case["caseId"]: case for case in cases}
        self.passed = set()

    def pass_case(self, case_id):
        if case_id not in self.cases or case_id in self.passed:
            raise ValueError("REPLAY_CASE_EVIDENCE_INVALID")
        self.passed.add(case_id)

    def required_tests(self):
        missing = set(self.cases) - self.passed
        if missing:
            raise ValueError("REPLAY_CASE_EVIDENCE_MISSING:" + ",".join(sorted(missing)))
        return len(self.passed)
