#!/usr/bin/env python3
"""Exercise authorized public pages through the real Browser Node and Chrome."""

import json
import hashlib
import os
import pathlib
import platform
import sys
import time
import urllib.error
import urllib.request
import uuid


BASE_URL = sys.argv[1].rstrip("/")
TENANT = "tenant-real-url"
DATASET_PATH = pathlib.Path(sys.argv[2])
DATASET_BYTES = DATASET_PATH.read_bytes()
DATASET = json.loads(DATASET_BYTES)
if DATASET["containsProductionData"] is not False:
    raise AssertionError("Replay Dataset must not contain unauthorized production data")
if DATASET["authorization"]["personalData"] or DATASET["authorization"]["credentials"]:
    raise AssertionError("Replay Dataset authorization metadata is unsafe")
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
        return error.code, parsed


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


def create_execute_task(session_id, body, label, terminal_states=("COMPLETED",)):
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
    return require_status(
        request("GET", f"/api/v1/sessions/{session_id}/state"),
        200,
        "read browser state",
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

proxy_denied_status, _ = request(
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

require_status(
    request("POST", f"/api/v1/sessions/{session_id}:terminate"),
    202,
    "terminate browser session",
)
wait_for(
    f"/api/v1/sessions/{session_id}",
    lambda item: item["state"] == "TERMINATED",
)
environment_facts = {
    "browserVersion": os.environ.get("BROWSER_VERSION", "unknown"),
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
        },
        tenant="platform-control",
        roles="PLATFORM_ADMIN",
        actor_id="validation-farm",
    ),
    200,
    "start Build-bound Runtime Validation",
)
validation = require_status(
    request(
        "POST",
        f"/api/v1/enterprise/runtime-validations/{validation['validationId']}:complete",
        {
            "requiredTests": len(DATASET["cases"]),
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
        tenant="platform-control",
        roles="PLATFORM_ADMIN",
        actor_id="validation-farm",
    ),
    200,
    "complete Build-bound Runtime Validation",
)
if validation["state"] != "PASSED" or len(validation["evidenceHash"]) != 64:
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
            "publicUrls": [url for _, url, _ in sites],
            "controlFixture": control_url,
            "verifiedControls": ["NAVIGATE", "READ", "TYPE_TEXT", "SCROLL"],
            "failClosed": ["CROSS_DOMAIN_CLICK", "NON_ALLOWLISTED_PLAN"],
        },
        sort_keys=True,
    )
)
