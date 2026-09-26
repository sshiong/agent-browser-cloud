#!/usr/bin/env python3
"""Exercise authorized public pages through the real Browser Node and Chrome."""

import json
import hashlib
import os
import pathlib
import platform
import re
import sys
import time
import urllib.error
import urllib.request
import uuid

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "validation"))
from replay_gate import ReplayGate


BASE_URL = sys.argv[1].rstrip("/")
TENANT = "tenant-real-url"
DATASET_PATH = pathlib.Path(sys.argv[2])
DATASET_BYTES = DATASET_PATH.read_bytes()
DATASET = json.loads(DATASET_BYTES)
REPLAY_GATE = ReplayGate(DATASET)
OPAQUE_CASE = REPLAY_GATE.cases["synthetic-opaque-frame-single-click"]
DATASET_DIGEST = hashlib.sha256(DATASET_BYTES).hexdigest()


def request(
    method,
    path,
    body=None,
    idempotency_key=None,
    tenant=TENANT,
    roles=None,
    actor_id=None,
):
    headers = {"X-Tenant-Id": tenant, "Content-Type": "application/json"}
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key
    if roles:
        headers["X-Roles"] = roles
    if actor_id:
        headers["X-Actor-Id"] = actor_id
    data = None if body is None else json.dumps(body).encode()
    for attempt in range(3):
        call = urllib.request.Request(
            BASE_URL + path, data=data, headers=headers, method=method
        )
        try:
            with urllib.request.urlopen(call, timeout=20) as response:
                payload = response.read()
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            payload = error.read()
            parsed = json.loads(payload) if payload else None
            if (
                error.code == 503
                and isinstance(parsed, dict)
                and parsed.get("code") == "DATABASE_TRANSACTION_RETRY"
                and attempt < 2
            ):
                time.sleep(0.1 * (attempt + 1))
                continue
            return error.code, parsed
    raise AssertionError("database retry loop exhausted without a response")


def require_status(actual, expected, context):
    status, payload = actual
    if status != expected:
        raise AssertionError(f"{context}: expected HTTP {expected}, got {status}: {payload}")
    return payload


def wait_for(path, predicate, timeout=45):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        last = require_status(request("GET", path), 200, f"poll {path}")
        if predicate(last):
            return last
        time.sleep(0.25)
    raise AssertionError(f"timed out polling {path}: {last}")


def wait_for_executable_state(session_id, timeout=45):
    path = f"/api/v1/sessions/{session_id}/state"
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, state = request("GET", path)
        if status == 204:
            last = None
        elif status == 200:
            last = state
            if state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}:
                return state
        else:
            raise AssertionError(
                f"poll {path}: expected HTTP 200 or 204, got {status}: {state}"
            )
        time.sleep(0.25)
    raise AssertionError(f"timed out polling executable state {path}: {last}")


def create_execute_task(session_id, body, label, terminal_states=("COMPLETED",)):
    created = None
    for _ in range(3):
        wait_for_executable_state(session_id)
        created = require_status(
            request(
                "POST",
                f"/api/v1/sessions/{session_id}/agent-tasks",
                body,
                f"real-{label}-create-{uuid.uuid4().hex}",
            ),
            201,
            f"create Agent task {label}",
        )
        if not (
            created.get("state") == "BLOCKED"
            and created.get("blockedReason") == "STATE_QUALITY_NOT_EXECUTABLE"
        ):
            break
    if created is None:
        raise AssertionError(f"Agent task {label} was not created")
    if created["state"] != "PLANNED":
        raise AssertionError(f"Agent task {label} was not planned: {created}")
    task_id = created["taskId"]
    require_status(
        request(
            "POST",
            f"/api/v1/agent-tasks/{task_id}:execute",
            idempotency_key=f"real-{label}-execute-{uuid.uuid4().hex}",
        ),
        200,
        f"execute Agent task {label}",
    )
    result = wait_for(
        f"/api/v1/agent-tasks/{task_id}",
        lambda task: task["state"] in {*terminal_states, "FAILED", "BLOCKED"},
    )
    if result["state"] not in terminal_states:
        raise AssertionError(f"Agent task {label} ended unexpectedly: {result}")
    return result


def require_verified(task, expected_tools):
    results = task["executionResults"]
    tools = [result["toolId"] for result in results]
    if tools != expected_tools:
        raise AssertionError(f"unexpected tools: expected {expected_tools}, got {tools}")
    if any(result["status"] != "VERIFIED" for result in results):
        raise AssertionError(f"unverified Agent result: {results}")


def current_state(session_id):
    return wait_for_executable_state(session_id)


def wait_for_named_target(session_id, name, role="button", timeout=45):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, state = request("GET", f"/api/v1/sessions/{session_id}/state")
        if status not in {200, 204}:
            raise AssertionError(f"poll named target {name}: HTTP {status}: {state}")
        if status == 200:
            last = state
            if state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}:
                target = next(
                    (
                        target
                        for target in state.get("targets", [])
                        if target.get("role") == role
                        and target.get("name") == name
                        and target.get("visible")
                        and target.get("enabled")
                    ),
                    None,
                )
                if target is not None:
                    return state, target
        time.sleep(0.25)
    raise AssertionError(
        f"timed out waiting for {name} target: quality={(last or {}).get('stateQuality')} "
        f"revision={(last or {}).get('targetRevision')} "
        f"buttons={[(target.get('name'), target.get('visible'), target.get('enabled'), target.get('inViewport')) for target in (last or {}).get('targets', []) if target.get('role') == 'button']} "
        f"matches={[target for target in (last or {}).get('targets', []) if target.get('name') == name]}"
    )


session = require_status(
    request(
        "POST",
        "/api/v1/sessions",
        {
            "tenantId": TENANT,
            "profileId": "profile-real-url",
            "region": "local",
            "resourceClass": "L1",
            "metadata": {"displayName": "Authorized public URL acceptance"},
        },
        "real-url-session-create",
    ),
    201,
    "create browser session",
)
session_id = session["sessionId"]
require_status(
    request("POST", f"/api/v1/sessions/{session_id}:start"),
    202,
    "start browser session",
)
wait_for(
    f"/api/v1/sessions/{session_id}",
    lambda item: item["state"] == "RUNNING",
)

public_cases = [case for case in DATASET["cases"] if case["kind"] == "PUBLIC_PAGE"]
sites = [
    (case["caseId"], case["url"], case["allowedDomains"][0])
    for case in public_cases
]
for label, url, domain in sites:
    task = create_execute_task(
        session_id,
        {
            "goal": f"Open and summarize the authorized {label} page",
            "startUrl": url,
            "allowedDomains": [domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        f"navigate-{label}",
    )
    require_verified(
        task, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"]
    )
    navigation = task["executionResults"][0]["output"]
    if navigation["domain"] != domain or not navigation["finalUrl"].startswith(url):
        raise AssertionError(f"navigation left authorized domain: {navigation}")
    REPLAY_GATE.pass_case(label)

form_case = REPLAY_GATE.cases["public-selenium-form"]
form_domain = form_case["allowedDomains"][0]
public_form = create_execute_task(
    session_id,
    {
        "goal": "Open Selenium's public web form practice page",
        "startUrl": form_case["url"],
        "allowedDomains": [form_domain],
        "maxActions": 8,
        "replanBudget": 1,
    },
    "navigate-public-selenium-form",
)
require_verified(
    public_form, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"]
)
form_navigation = public_form["executionResults"][0]["output"]
if form_navigation["domain"] != form_domain or not form_navigation["finalUrl"].startswith(form_case["url"]):
    raise AssertionError(f"Selenium form navigation left authorized page: {form_navigation}")
public_state, public_textbox = wait_for_named_target(session_id, "Text input", role="textbox")
if public_textbox["role"] != "textbox" or public_textbox["sensitive"]:
    raise AssertionError(f"Selenium text input was not actionable: {public_textbox}")
public_marker = "agent-browser-public-form"
public_typed = create_execute_task(
    session_id,
    {
        "goal": "Fill Selenium's public practice form with a harmless test marker",
        "allowedDomains": [form_domain],
        "maxActions": 8,
        "replanBudget": 1,
        "actions": [{
            "toolId": "TYPE_TEXT",
            "targetRef": public_textbox["targetRef"],
            "targetRevision": public_state["targetRevision"],
            "value": public_marker,
            "dataClass": "PUBLIC",
        }],
    },
    "public-selenium-form-type",
)
require_verified(public_typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
if public_marker in json.dumps(public_typed):
    raise AssertionError("public Selenium form marker leaked into Agent task response")
public_after_type = current_state(session_id)
if not any(
    target.get("name") == "Text input" and target.get("value") == public_marker
    for target in public_after_type["targets"]
):
    raise AssertionError("public Selenium form did not retain typed text")
public_scrolled = create_execute_task(
    session_id,
    {
        "goal": "Reveal Selenium's public form submit button",
        "allowedDomains": [form_domain],
        "maxActions": 8,
        "replanBudget": 1,
        "actions": [{"toolId": "SCROLL", "scrollDeltaY": 500}],
    },
    "public-selenium-form-scroll",
)
require_verified(public_scrolled, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
public_after_type, public_submit = wait_for_named_target(session_id, "Submit")
public_submitted = create_execute_task(
    session_id,
    {
        "goal": "Submit Selenium's public practice form",
        "allowedDomains": [form_domain],
        "maxActions": 8,
        "replanBudget": 1,
        "actions": [{
            "toolId": "CLICK_TARGET",
            "targetRef": public_submit["targetRef"],
            "targetRevision": public_after_type["targetRevision"],
        }],
    },
    "public-selenium-form-submit",
)
require_verified(public_submitted, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
public_result = current_state(session_id)
for _ in range(40):
    if "/selenium/web/submitted-form.html" in public_result.get("url", ""):
        break
    time.sleep(0.25)
    public_result = current_state(session_id)
if "/selenium/web/submitted-form.html" not in public_result.get("url", ""):
    raise AssertionError(
        f"public Selenium form did not submit: {public_result.get('url')}; "
        f"submit={public_submit}; click={public_submitted['executionResults'][1].get('output')}"
    )
if public_marker not in public_result["url"]:
    raise AssertionError("public Selenium form submission omitted the test marker")
REPLAY_GATE.pass_case("public-selenium-form")

control_url = "http://agent-controls.invalid/form"
control_task = create_execute_task(
    session_id,
    {
        "goal": "Open the deterministic authorized control fixture",
        "startUrl": control_url,
        "allowedDomains": ["agent-controls.invalid"],
        "maxActions": 8,
        "replanBudget": 1,
    },
    "navigate-control-fixture",
)
require_verified(
    control_task, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"]
)
form_state = current_state(session_id)
textbox = next(
    (
        target
        for target in form_state["targets"]
        if target["role"] in {"textbox", "combobox"}
        and target["visible"]
        and target["enabled"]
        and not target["sensitive"]
    ),
    None,
)
if textbox is None:
    raise AssertionError(f"authorized form exposed no actionable textbox: {form_state}")

marker = "browser-cloud-public-input"
typed = create_execute_task(
    session_id,
    {
        "goal": "Enter the user-authorized public test marker into the current form",
        "allowedDomains": ["agent-controls.invalid"],
        "maxActions": 8,
        "replanBudget": 1,
        "actions": [
            {
                "toolId": "TYPE_TEXT",
                "targetRef": textbox["targetRef"],
                "targetRevision": form_state["targetRevision"],
                "value": marker,
                "dataClass": "PUBLIC",
            }
        ],
    },
    "type-text",
)
require_verified(
    typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"]
)
typed_json = json.dumps(typed)
if marker in typed_json:
    raise AssertionError("plaintext TYPE_TEXT value leaked into Agent task response")
type_output = typed["executionResults"][1]["output"]
if type_output.get("inputLength") != len(marker) or len(type_output.get("inputHash", "")) != 64:
    raise AssertionError(f"TYPE_TEXT evidence was not minimized: {type_output}")

scrolled = create_execute_task(
    session_id,
    {
        "goal": "Scroll the current authorized documentation page",
        "allowedDomains": ["agent-controls.invalid"],
        "maxActions": 8,
        "replanBudget": 1,
        "actions": [{"toolId": "SCROLL", "scrollDeltaY": 600}],
    },
    "scroll",
)
require_verified(scrolled, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
REPLAY_GATE.pass_case("synthetic-form-controls")

challenge_url = "http://agent-controls.invalid/challenge"
try:
    challenge_task = create_execute_task(
        session_id,
        {
            "goal": "Open the authorized simple challenge and continue after automatic verification",
            "startUrl": challenge_url,
            "allowedDomains": ["agent-controls.invalid"],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "simple-challenge",
    )
except AssertionError as error:
    simple_diagnostics = {
        "state": request("GET", f"/api/v1/sessions/{session_id}/state"),
        "challenges": request("GET", f"/api/v1/sessions/{session_id}/challenges"),
        "run": request(
            "GET", f"/api/v1/sessions/{session_id}/challenge-automation/current"
        ),
    }
    raise AssertionError(
        f"{error}; simple Challenge diagnostics: "
        + json.dumps(simple_diagnostics, sort_keys=True)
    ) from error
require_verified(
    challenge_task, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"]
)
challenge_run = wait_for(
    f"/api/v1/sessions/{session_id}/challenge-automation/current",
    lambda run: run["state"] == "COMPLETED",
)
if (
    challenge_run["attemptCount"] != 1
    or challenge_run["lastAction"] != "CLICKx1"
    or challenge_task.get("challengeEventId") is not None
):
    raise AssertionError(
        f"simple Challenge did not complete through bounded Agent automation: {challenge_run} {challenge_task}"
    )
challenge_events = require_status(
    request("GET", f"/api/v1/sessions/{session_id}/challenges"),
    200,
    "read simple Challenge timeline",
)
simple_challenge = next(
    (
        item
        for item in challenge_events["items"]
        if item["suspectedType"] == "SINGLE_CLICK"
    ),
    None,
)
if simple_challenge is None or simple_challenge["status"] == "AUTHORIZED":
    raise AssertionError(
        f"simple Challenge required a manual authorization: {challenge_events}"
    )
challenge_state = current_state(session_id)
if challenge_state["title"] != "Challenge passed":
    raise AssertionError(f"simple Challenge outcome was not observed: {challenge_state}")
REPLAY_GATE.pass_case("synthetic-simple-challenge")

# A real cross-origin iframe remains DOM-opaque. Automation is allowed only after an exact
# Session-origin opt-in and Task allowedDomains intersection, then only through the screenshot
# vision protocol. This controlled worker response exercises the real pixel/click transport while
# keeping the fixture deterministic and free of external model credentials.
require_status(
    request(
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
            "opaqueFrameClickEnabled": True,
            "opaqueFrameClickOrigins": [OPAQUE_CASE["frameOrigin"]],
        },
        actor_id="opaque-challenge-operator",
        roles="TENANT_OPERATOR",
    ),
    200,
    "enable exact opaque Challenge origin",
)
opaque_navigation = create_execute_task(
    session_id,
    {
        "goal": "Open the authorized hosted verification page",
        "startUrl": OPAQUE_CASE["url"],
        "allowedDomains": OPAQUE_CASE["allowedDomains"],
        "maxActions": 8,
        "replanBudget": 1,
    },
    "navigate-opaque-challenge",
)
require_verified(
    opaque_navigation,
    ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"],
)
# The hosted iframe is attached asynchronously.  Wait for the authoritative Browser State to
# contain the exact opaque-frame identity before starting the task that automation will pause and
# resume.  A broad STATE_CHANGED wait started before this point can legitimately finish on an
# unrelated navigation-stability update, leaving no active task for Challenge detection to bind.
opaque_frame_state = wait_for(
    f"/api/v1/sessions/{session_id}/state",
    lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
    and any(
        frame.get("origin") == OPAQUE_CASE["frameOrigin"]
        and frame.get("frameRef", "").startswith("ofr_")
        and frame.get("boundaryReason") == "CROSS_ORIGIN"
        for frame in state.get("opaqueFrames", [])
    ),
)
if opaque_frame_state.get("title") != "Verify you are human":
    raise AssertionError(
        f"opaque Challenge parent page changed before task binding: {opaque_frame_state}"
    )
opaque_frames = [
    frame for frame in opaque_frame_state["opaqueFrames"]
    if frame.get("origin") == OPAQUE_CASE["frameOrigin"]
]
if len(opaque_frames) != 1 or any(
    target.get("targetRef") == opaque_frames[0]["frameRef"]
    or target.get("elementId") == opaque_frames[0]["frameRef"]
    for target in opaque_frame_state.get("targets", [])
):
    raise AssertionError("opaque frame became an executable target")
opaque_created = require_status(
    request(
        "POST",
        f"/api/v1/sessions/{session_id}/agent-tasks",
        {
            "goal": "Wait for the authorized hosted verification and continue after one bounded click",
            "allowedDomains": OPAQUE_CASE["allowedDomains"],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [
                {
                    "toolId": "WAIT_FOR",
                    "waitCondition": "STATE_CHANGED",
                    "timeoutMs": 10000,
                }
            ],
        },
        f"real-opaque-challenge-create-{uuid.uuid4().hex}",
    ),
    201,
    "create opaque Challenge task",
)
opaque_task_id = opaque_created["taskId"]
if opaque_created["state"] != "PLANNED":
    raise AssertionError(f"opaque Challenge task was not planned: {opaque_created}")
require_status(
    request(
        "POST",
        f"/api/v1/agent-tasks/{opaque_task_id}:execute",
        idempotency_key=f"real-opaque-challenge-execute-{uuid.uuid4().hex}",
    ),
    200,
    "execute opaque Challenge task",
)
opaque_claim = None
deadline = time.monotonic() + 45
while time.monotonic() < deadline:
    status, candidate = request(
        "POST",
        "/api/v1/challenge-visual-jobs:claim",
        {
            "protocolVersion": "challenge-vision-worker/v1",
            "capabilities": {
                "screenshot-ocr-actions-v1": True,
                "local-ocr-pii-gate-v1": True,
            },
            "deploymentId": "challenge-vision-default",
            "modelRevision": "challenge-vision-v1",
        },
        roles="VISION_WORKER",
        actor_id="opaque-fixture-vision-worker",
    )
    if status == 200:
        opaque_claim = candidate
        break
    if status != 204:
        raise AssertionError(f"claim opaque Challenge vision job failed: {status} {candidate}")
    time.sleep(0.25)
if opaque_claim is None:
    opaque_diagnostics = {
        "policy": request(
            "GET", f"/api/v1/sessions/{session_id}/challenge-automation/policy"
        ),
        "task": request("GET", f"/api/v1/agent-tasks/{opaque_task_id}"),
        "state": request("GET", f"/api/v1/sessions/{session_id}/state"),
        "challenges": request("GET", f"/api/v1/sessions/{session_id}/challenges"),
        "run": request(
            "GET", f"/api/v1/sessions/{session_id}/challenge-automation/current"
        ),
    }
    raise AssertionError(
        "opaque Challenge never produced a vision job: "
        + json.dumps(opaque_diagnostics, sort_keys=True)
    )
opaque_job_id = opaque_claim["job"]["jobId"]
opaque_token = opaque_claim["claimToken"]
require_status(
    request(
        "POST",
        f"/api/v1/challenge-visual-jobs/{opaque_job_id}:start",
        {"claimToken": opaque_token},
        roles="VISION_WORKER",
        actor_id="opaque-fixture-vision-worker",
    ),
    200,
    "start opaque Challenge vision job",
)
require_status(
    request(
        "POST",
        f"/api/v1/challenge-visual-jobs/{opaque_job_id}:complete",
        {
            "claimToken": opaque_token,
            "decision": "ACT",
            "actions": [
                {"actionType": "CLICK", "x": 0.5, "y": 0.5, "repeatCount": 1}
            ],
            "confidence": 0.99,
            "deploymentId": "challenge-vision-default",
            "modelRevision": "challenge-vision-v1",
            "providerRequestId": "fixture-opaque-click",
            "inputTokens": 0,
            "outputTokens": 0,
            "latencyMs": 1,
            "outputHash": hashlib.sha256(b"fixture-opaque-click").hexdigest(),
            "privacyScanVersion": "tesseract-pii-v1",
            "ocrTextHash": hashlib.sha256(b"").hexdigest(),
            "detectedSensitivePatternCount": 0,
            "piiRedactedRegionCount": 0,
            "remainingSensitivePatternCount": 0,
        },
        roles="VISION_WORKER",
        actor_id="opaque-fixture-vision-worker",
    ),
    200,
    "complete opaque Challenge vision job",
)
opaque_task = wait_for(
    f"/api/v1/agent-tasks/{opaque_task_id}",
    lambda task: task["state"] in {"COMPLETED", "FAILED", "BLOCKED"},
)
if opaque_task["state"] != "COMPLETED":
    raise AssertionError(f"opaque Challenge task did not resume: {opaque_task}")
require_verified(
    opaque_task,
    ["GET_CURRENT_STATE", "WAIT_FOR", "GET_URL", "GET_PAGE_SUMMARY"],
)
opaque_run = wait_for(
    f"/api/v1/sessions/{session_id}/challenge-automation/current",
    lambda run: run["state"] in {"COMPLETED", "FAILED", "ESCALATED", "EXHAUSTED"},
)
if opaque_run["state"] != "COMPLETED" or opaque_run["lastAction"] != "CLICKx1":
    raise AssertionError(f"opaque Challenge automation did not complete: {opaque_run}")
opaque_events = require_status(
    request("GET", f"/api/v1/sessions/{session_id}/challenges"),
    200,
    "read opaque Challenge timeline",
)
opaque_event = next(
    (
        item
        for item in opaque_events["items"]
        if item["suspectedType"] == "OPAQUE_FRAME_SINGLE_CLICK"
    ),
    None,
)
if opaque_event is None or not opaque_event["targetRef"].startswith("ofr_"):
    raise AssertionError(f"opaque Challenge did not retain a frame identity: {opaque_events}")
opaque_state = current_state(session_id)
if opaque_state["title"] != "Opaque challenge passed":
    raise AssertionError(f"opaque Challenge click did not reach the hosted frame: {opaque_state}")
REPLAY_GATE.pass_case("synthetic-opaque-frame-single-click")

example_task = create_execute_task(
    session_id,
    {
        "goal": "Return to the authorized example page",
        "startUrl": "https://example.com/",
        "allowedDomains": ["example.com"],
        "maxActions": 8,
        "replanBudget": 1,
    },
    "return-example",
)
require_verified(
    example_task, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"]
)
example_state = current_state(session_id)
cross_domain_link = next(
    (
        target
        for target in example_state["targets"]
        if target["role"] == "link"
        and target["visible"]
        and target["enabled"]
    ),
    None,
)
if cross_domain_link is None:
    raise AssertionError(f"example.com link target missing: {example_state}")

failed_click = create_execute_task(
    session_id,
    {
        "goal": "Click the visible link only if it remains within the authorized domain",
        "allowedDomains": ["example.com"],
        "maxActions": 8,
        "replanBudget": 0,
        "actions": [
            {
                "toolId": "CLICK_TARGET",
                "targetRef": cross_domain_link["targetRef"],
                "targetRevision": example_state["targetRevision"],
            }
        ],
    },
    "cross-domain-click",
    terminal_states=("FAILED",),
)
if "POST_ACTION_DOMAIN_NOT_ALLOWED" not in (failed_click.get("lastError") or ""):
    raise AssertionError(f"cross-domain click did not fail closed: {failed_click}")
REPLAY_GATE.pass_case("cross-domain-fail-closed")

proxy_denied_status, proxy_denied_task = request(
    "POST",
    f"/api/v1/sessions/{session_id}/agent-tasks",
    {
        "goal": "Open an explicitly non-authorized site",
        "startUrl": "https://www.iana.org/",
        "allowedDomains": ["example.com"],
    },
    f"real-denied-create-{uuid.uuid4().hex}",
)
if proxy_denied_status != 201:
    raise AssertionError(f"blocked Agent plan should be persisted, got {proxy_denied_status}")
if proxy_denied_task.get("state") != "BLOCKED" or proxy_denied_task.get("blockedReason") != "DOMAIN_NOT_ALLOWED":
    raise AssertionError(f"non-allowlisted Agent plan was not blocked: {proxy_denied_task}")
REPLAY_GATE.pass_case("non-allowlisted-plan")

require_status(
    request("POST", f"/api/v1/sessions/{session_id}:terminate"),
    202,
    "terminate browser session",
)
wait_for(
    f"/api/v1/sessions/{session_id}",
    lambda item: item["state"] == "TERMINATED",
)
browser_product = os.environ.get("BROWSER_VERSION", "unknown")
browser_version_match = re.search(r"\b\d+(?:\.\d+){1,3}\b", browser_product)
browser_version = browser_version_match.group(0) if browser_version_match else "unknown"
environment_facts = {
    "browserProduct": browser_product,
    "browserVersion": browser_version,
    "datasetDigest": f"sha256:{DATASET_DIGEST}",
    "os": platform.platform(),
}
environment_digest = "sha256:" + hashlib.sha256(
    json.dumps(environment_facts, sort_keys=True, separators=(",", ":")).encode()
).hexdigest()
validation = require_status(
    request(
        "POST",
        "/api/v1/enterprise/runtime-validations",
        {
            "buildId": "runtime_local_chromium",
            "suiteVersion": DATASET["version"],
            "environmentDigest": environment_digest,
            "replayDatasetId": DATASET["datasetId"],
            "persona": DATASET["persona"],
            "browserEngine": "chromium",
            "browserVersion": environment_facts["browserVersion"],
            "operatingSystem": "macos" if platform.system() == "Darwin" else platform.system().lower(),
            "architecture": {"x86_64": "amd64", "aarch64": "arm64"}.get(
                platform.machine().lower(), platform.machine().lower()
            ),
            "requiredWorkerCapabilities": {
                "agentControl": True,
                "cdp": True,
                "stateCollector": True,
            },
            "maximumAttempts": 3,
        },
        tenant="platform-control",
        roles="PLATFORM_ADMIN",
        actor_id="validation-farm",
    ),
    200,
    "start Build-bound Runtime Validation",
)
validation_claim = require_status(
    request(
        "POST",
        "/api/v1/enterprise/runtime-validation-jobs:claim",
        {
            "browserEngine": "chromium",
            "browserVersions": [environment_facts["browserVersion"]],
            "operatingSystem": "macos" if platform.system() == "Darwin" else platform.system().lower(),
            "architecture": {"x86_64": "amd64", "aarch64": "arm64"}.get(
                platform.machine().lower(), platform.machine().lower()
            ),
            "capabilities": {
                "agentControl": True,
                "cdp": True,
                "stateCollector": True,
            },
        },
        tenant="platform-control",
        roles="VALIDATION_WORKER",
        actor_id="validation-worker-real-url",
    ),
    200,
    "claim Build-bound Runtime Validation",
)
if validation_claim["validation"]["validationId"] != validation["validationId"]:
    raise AssertionError(f"claimed a different Runtime Validation: {validation_claim}")
require_status(
    request(
        "POST",
        f"/api/v1/enterprise/runtime-validation-jobs/{validation['validationId']}:start",
        {"claimToken": validation_claim["claimToken"]},
        tenant="platform-control",
        roles="VALIDATION_WORKER",
        actor_id="validation-worker-real-url",
    ),
    200,
    "start claimed Runtime Validation job",
)
validation = require_status(
    request(
        "POST",
        f"/api/v1/enterprise/runtime-validation-jobs/{validation['validationId']}:complete",
        {
            "claimToken": validation_claim["claimToken"],
            "result": {
                "requiredTests": REPLAY_GATE.required_tests(),
                "requiredFailures": 0,
                "optionalTests": 0,
                "optionalFailures": 0,
                "declaredCapabilities": {
                    "agentControl": True,
                    "cdp": True,
                    "stateCollector": True,
                },
                "observedCapabilities": {
                    "agentControl": True,
                    "cdp": True,
                    "stateCollector": True,
                },
                "optionalFailureCodes": [],
                "personaConsistent": True,
            },
        },
        tenant="platform-control",
        roles="VALIDATION_WORKER",
        actor_id="validation-worker-real-url",
    ),
    200,
    "complete Build-bound Runtime Validation",
)
if (
    validation["state"] != "PASSED"
    or validation["job"]["state"] != "COMMITTED"
    or len(validation["evidenceHash"]) != 64
):
    raise AssertionError(f"Runtime Validation evidence is incomplete: {validation}")
print(
    json.dumps(
        {
            "status": "PASS",
            "datasetId": DATASET["datasetId"],
            "datasetDigest": f"sha256:{DATASET_DIGEST}",
            "environmentDigest": environment_digest,
            "browserVersion": environment_facts["browserVersion"],
            "validationId": validation["validationId"],
            "validationEvidenceHash": validation["evidenceHash"],
            "sessionId": session_id,
            "publicUrls": [url for _, url, _ in sites] + [form_case["url"]],
            "controlFixture": control_url,
            "challengeFixture": challenge_url,
            "opaqueChallengeFixture": OPAQUE_CASE["url"],
            "verifiedControls": [
                "NAVIGATE",
                "READ",
                "TYPE_TEXT",
                "SCROLL",
                "CLICK_TARGET",
                "AUTOMATIC_SINGLE_CLICK_CHALLENGE",
                "AUTOMATIC_OPAQUE_FRAME_SINGLE_CLICK_CHALLENGE",
            ],
            "failClosed": ["CROSS_DOMAIN_CLICK", "NON_ALLOWLISTED_PLAN"],
        },
        sort_keys=True,
    )
)
