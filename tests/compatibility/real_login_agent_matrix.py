#!/usr/bin/env python3
"""Real Chrome Agent-control login fixture runner.

This driver talks only to the local Control Plane API. The HTTP fixture is served by the
temporary proxy sibling and uses repository-owned test credentials; no user credentials are read.
"""

import hashlib
import json
import pathlib
import sys
import time
import urllib.error
import urllib.request
import uuid


BASE_URL = sys.argv[1].rstrip("/")
EVENT_LOG = pathlib.Path(sys.argv[2])
PROVIDER_MODE = sys.argv[3]
MODEL_NAME = sys.argv[4]
MODEL_REVISION = sys.argv[5]
TENANT = "tenant-login-fixture"
FIXTURE_HOST = "agent-controls.invalid"
LOGIN_URL = "http://agent-controls.invalid/login"

# These values are owned by this ephemeral fixture, not supplied by or read from the user.
FIXTURE_USERNAME = "fixture-user"
FIXTURE_PASSWORD = "fixture-pass"


def request(method, path, body=None, idempotency_key=None, tenant=TENANT, roles=None, actor_id=None):
    headers = {"X-Tenant-Id": tenant, "Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    if roles:
        headers["X-Roles"] = roles
    if actor_id:
        headers["X-Actor-Id"] = actor_id
    data = None if body is None else json.dumps(body).encode()
    for attempt in range(4):
        call = urllib.request.Request(BASE_URL + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(call, timeout=30) as response:
                raw = response.read()
                return response.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as error:
            raw = error.read()
            parsed = json.loads(raw) if raw else None
            if error.code == 503 and attempt < 3:
                time.sleep(0.2 * (attempt + 1))
                continue
            return error.code, parsed
    raise AssertionError("request retry exhausted")


def require(method, path, body=None, idempotency_key=None, **kwargs):
    status, payload = request(method, path, body, idempotency_key, **kwargs)
    if status < 200 or status >= 300:
        raise AssertionError(f"{method} {path}: HTTP {status}: {payload}")
    return payload


def wait_for(path, predicate, timeout=90, **kwargs):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, payload = request("GET", path, **kwargs)
        if status == 200:
            last = payload
            if predicate(payload):
                return payload
        elif status != 204:
            raise AssertionError(f"GET {path}: HTTP {status}: {payload}")
        time.sleep(0.3)
    raise AssertionError(f"timed out waiting for {path}: {last}")


def state(session_id, minimum_state_version=None):
    return wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda item: item.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and (
            minimum_state_version is None
            or item.get("stateVersion", 0) > minimum_state_version
        ),
        timeout=90,
    )


def task_until_terminal(task_id):
    return wait_for(
        f"/api/v1/agent-tasks/{task_id}",
        lambda item: item.get("state") in {"COMPLETED", "FAILED", "BLOCKED"},
        timeout=120,
    )


def create_and_execute(session_id, body, label):
    task = require(
        "POST",
        f"/api/v1/sessions/{session_id}/agent-tasks",
        body,
        f"login-{label}-create-{uuid.uuid4().hex}",
    )
    if task.get("state") != "PLANNED":
        raise AssertionError(f"{label} task not planned: {task}")
    task_id = task["taskId"]
    require(
        "POST",
        f"/api/v1/agent-tasks/{task_id}:execute",
        idempotency_key=f"login-{label}-execute-{uuid.uuid4().hex}",
    )
    return task_until_terminal(task_id)


def create_secret(session_id, purpose, value, label):
    result = require(
        "POST",
        f"/api/v1/sessions/{session_id}/agent-input-secrets",
        {"purpose": purpose, "value": value},
        f"login-secret-{label}-{uuid.uuid4().hex}",
        actor_id="login-fixture-operator",
        roles="TENANT_OPERATOR",
    )
    if "value" in result or result.get("consumed") is not False:
        raise AssertionError(f"secret API leaked or consumed write-only value: {result}")
    return result["secretId"]


def expected(title, status_text):
    return [
        {"outcomeId": "login-title", "type": "PAGE_TITLE_EQUALS", "matchValue": title},
        {"outcomeId": "login-status", "type": "TARGET_PRESENT", "role": "status", "matchValue": status_text},
    ]


session_ids = []


def create_session(label):
    session = require(
        "POST",
        "/api/v1/sessions",
        {
            "tenantId": TENANT,
            # A Profile has exactly one active writer. Every independent login case therefore
            # receives its own Profile instead of accidentally testing the writer fence.
            "profileId": f"profile-login-fixture-{label}",
            "region": "local",
            "resourceClass": "L1",
            "metadata": {"displayName": f"Controlled login fixture {label}"},
        },
        f"login-session-create-{label}",
    )
    session_id = session["sessionId"]
    session_ids.append(session_id)
    require("POST", f"/api/v1/sessions/{session_id}:start")
    wait_for(f"/api/v1/sessions/{session_id}", lambda item: item.get("state") == "RUNNING")
    require(
        "PUT",
        f"/api/v1/sessions/{session_id}/challenge-automation/policy",
        {
            "controlMode": "AUTONOMOUS",
            "sensitiveInputMaximumAttempts": 3,
            "enabled": True,
            "maximumAttempts": 3,
            "minimumConfidence": 0.9,
            "allowMultiClick": True,
            "allowSlide": True,
        },
        actor_id="login-fixture-operator",
        roles="TENANT_OPERATOR",
    )
    return session_id


def navigate_to_login(session_id, label):
    landing = create_and_execute(
        session_id,
        {
            "goal": "Open the controlled login fixture",
            "startUrl": LOGIN_URL,
            "allowedDomains": [FIXTURE_HOST],
            "maxActions": 8,
            "replanBudget": 1,
            "expectedOutcomes": expected("Fixture Login", "Use the controlled fixture account"),
        },
        f"{label}-landing",
    )
    if landing["state"] != "COMPLETED" or landing.get("outcomeVerification", {}).get("status") != "VERIFIED":
        raise AssertionError(f"{label} landing outcome verification failed: {landing}")
    return state(session_id), landing


def login_targets(login_state):
    targets = login_state.get("targets", [])
    username = next(item for item in targets if item.get("role") == "textbox" and item.get("name") == "Username")
    password = next(item for item in targets if item.get("role") == "textbox" and item.get("sensitive") is True)
    submit = next(item for item in targets if item.get("role") == "button" and item.get("name") == "Sign in")
    return username, password, submit


def action_task(session_id, login_state, label, action, expected_outcomes):
    username, password, submit = login_targets(login_state)
    if action == "username":
        username_secret = create_secret(session_id, "USERNAME", FIXTURE_USERNAME, f"{label}-username")
        action_body = {
            "toolId": "TYPE_TEXT",
            "targetRef": username["targetRef"],
            "targetRevision": login_state["targetRevision"],
            "secretId": username_secret,
            "dataClass": "CREDENTIAL",
        }
    elif action == "password":
        password_secret = create_secret(session_id, "PASSWORD", label["password"], f"{label['name']}-password")
        action_body = {
            "toolId": "TYPE_TEXT",
            "targetRef": password["targetRef"],
            "targetRevision": login_state["targetRevision"],
            "secretId": password_secret,
            "dataClass": "CREDENTIAL",
        }
        label = label["name"]
    elif action == "submit":
        action_body = {
            "toolId": "CLICK_TARGET",
            "targetRef": submit["targetRef"],
            "targetRevision": login_state["targetRevision"],
        }
    else:
        raise AssertionError(f"unsupported fixture action: {action}")
    return create_and_execute(
        session_id,
        {
            "goal": "Perform one bounded login fixture action and verify the resulting state",
            "allowedDomains": [FIXTURE_HOST],
            "maxActions": 8,
            "replanBudget": 1,
            "expectedOutcomes": expected_outcomes,
            "actions": [action_body],
        },
        label,
    )


def run_login_case(label, password_value, final_expected_outcomes):
    session_id = create_session(label)
    login_state, landing = navigate_to_login(session_id, label)
    login_page_expected = expected("Fixture Login", "Use the controlled fixture account")
    username_task = action_task(session_id, login_state, f"{label}-username", "username", login_page_expected)
    if username_task["state"] != "COMPLETED" or username_task.get("outcomeVerification", {}).get("status") != "VERIFIED":
        raise AssertionError(f"{label} username action was not verified: {username_task}")
    username_state = state(session_id, login_state["stateVersion"])
    password_task = action_task(
        session_id,
        username_state,
        {"name": label, "password": password_value},
        "password",
        login_page_expected,
    )
    if password_task["state"] != "COMPLETED" or password_task.get("outcomeVerification", {}).get("status") != "VERIFIED":
        raise AssertionError(f"{label} password action was not verified: {password_task}")
    password_state = state(session_id, username_state["stateVersion"])
    submit_task = action_task(session_id, password_state, label, "submit", final_expected_outcomes)
    return session_id, landing, username_task, password_task, submit_task


def require_provider_evidence(task):
    evidence = task.get("outcomeVerification") or {}
    if evidence.get("modelName") != MODEL_NAME or evidence.get("modelRevision") != MODEL_REVISION:
        raise AssertionError(f"Outcome Provider identity mismatch: {evidence}")
    if not isinstance(evidence.get("inputTokens"), int) or evidence["inputTokens"] <= 0:
        raise AssertionError(f"Outcome Provider input-token evidence missing: {evidence}")
    if not isinstance(evidence.get("latencyMs"), int) or evidence["latencyMs"] < 0:
        raise AssertionError(f"Outcome Provider latency evidence missing: {evidence}")
    return {
        "modelName": evidence["modelName"],
        "modelRevision": evidence["modelRevision"],
        "inputTokens": evidence["inputTokens"],
        "outputTokens": evidence.get("outputTokens"),
        "latencyMs": evidence["latencyMs"],
        "evidenceHash": evidence.get("evidenceHash"),
    }


success_session, success_landing, success_username, success_password, success = run_login_case(
    "login-success", FIXTURE_PASSWORD, expected("Fixture Dashboard", "Login successful")
)
if success["state"] != "COMPLETED" or success.get("outcomeVerification", {}).get("status") != "VERIFIED":
    raise AssertionError(f"successful login did not verify: {success}")
success_provider_evidence = require_provider_evidence(success)

failure_session, failure_landing, failure_username, failure_password, failure = run_login_case(
    "login-failure", "wrong-fixture-password", expected("Fixture Login Failed", "Invalid username or password")
)
if failure["state"] != "COMPLETED" or failure.get("outcomeVerification", {}).get("status") != "VERIFIED":
    raise AssertionError(f"expected invalid-login outcome was not verified: {failure}")
failure_provider_evidence = require_provider_evidence(failure)

false_success_session, false_success_landing, false_success_username, false_success_password, false_success = run_login_case(
    "login-false-success", "wrong-fixture-password-2", expected("Fixture Dashboard", "Login successful")
)
if false_success["state"] != "FAILED" or false_success.get("lastError") != "AGENT_OUTCOME_NOT_VERIFIED":
    raise AssertionError(f"false success was not rejected by Outcome Verifier: {false_success}")
false_success_provider_evidence = require_provider_evidence(false_success)

events = []
if PROVIDER_MODE == "fixture":
    events = [json.loads(line) for line in EVENT_LOG.read_text().splitlines() if line.strip()]
    if not events or not all(event.get("forbiddenFieldsAbsent") for event in events):
        raise AssertionError(f"model fixture evidence missing minimization: {events}")
elif PROVIDER_MODE != "external":
    raise AssertionError(f"unsupported Provider mode: {PROVIDER_MODE}")

for session_id in session_ids:
    require("POST", f"/api/v1/sessions/{session_id}:terminate")
    wait_for(f"/api/v1/sessions/{session_id}", lambda item: item.get("state") == "TERMINATED")

print(json.dumps({
    "status": "PASS",
    "sessionIds": session_ids,
    "successTask": success["taskId"],
    "invalidLoginTask": failure["taskId"],
    "falseSuccessRejectedTask": false_success["taskId"],
    "landingTasks": [success_landing["taskId"], failure_landing["taskId"], false_success_landing["taskId"]],
    "verifiedActionTasks": [
        success_username["taskId"], success_password["taskId"],
        failure_username["taskId"], failure_password["taskId"],
        false_success_username["taskId"], false_success_password["taskId"],
    ],
    "verified": ["AUTONOMOUS_SENSITIVE_INPUT", "LOGIN_SUCCESS_EXPECTED_OUTCOME", "LOGIN_FAILURE_EXPECTED_OUTCOME"],
    "rejected": "TECHNICAL_SUCCESS_WITH_UNSATISFIED_LOGIN_OUTCOME",
    "providerMode": PROVIDER_MODE,
    "providerEvidence": {
        "success": success_provider_evidence,
        "invalidLogin": failure_provider_evidence,
        "falseSuccessRejected": false_success_provider_evidence,
    },
    "modelFixtureRequests": len(events) if PROVIDER_MODE == "fixture" else None,
    "secretValuesPrinted": False,
}, sort_keys=True))
