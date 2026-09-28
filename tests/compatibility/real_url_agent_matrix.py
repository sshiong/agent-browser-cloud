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
from urllib.parse import urlsplit

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "validation"))
from replay_gate import ReplayGate


BASE_URL = sys.argv[1].rstrip("/")
TENANT = "tenant-real-url"
DATASET_PATH = pathlib.Path(sys.argv[2])
DATASET_BYTES = DATASET_PATH.read_bytes()
DATASET = json.loads(DATASET_BYTES)
REPLAY_GATE = ReplayGate(DATASET)
OPAQUE_CASE = REPLAY_GATE.cases["synthetic-opaque-frame-single-click"]
SPA_URL = REPLAY_GATE.cases["public-playwright-todomvc-spa"]["url"]
COMMERCE_URL = REPLAY_GATE.cases["public-saucedemo-cart"]["url"]
IDP_URL = REPLAY_GATE.cases["public-duende-idp-login"]["url"]
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
    raise AssertionError(f"timed out polling {path}: {diagnostic(last)}")


def diagnostic(value):
    if not isinstance(value, dict):
        return value
    if "taskId" in value:
        return {
            key: value.get(key)
            for key in ("taskId", "state", "blockedReason", "lastError", "challengeEventId")
        } | {
            "steps": [
                (item.get("toolId"), item.get("status"), item.get("reasonCode"))
                for item in value.get("memory", {}).get("executionHistory", [])
            ]
        }
    if "targets" in value:
        return {
            key: value.get(key)
            for key in (
                "sessionId", "url", "stateVersion", "targetRevision", "stateQuality",
                "freshness", "pageActivity", "networkQuietMillis", "pageStability",
            )
        }
    return value


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
        raise AssertionError(f"Agent task {label} ended unexpectedly: {diagnostic(result)}")
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


def wait_for_named_target(session_id, name, role="button", timeout=45, require_stable=False):
    deadline = time.monotonic() + timeout
    last = None
    while time.monotonic() < deadline:
        status, state = request("GET", f"/api/v1/sessions/{session_id}/state")
        if status not in {200, 204}:
            raise AssertionError(f"poll named target {name}: HTTP {status}: {state}")
        if status == 200:
            last = state
            if state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"} and (
                not require_stable or (
                    state.get("pageActivity") == "STABLE"
                    and state.get("freshness") == "FRESH"
                )
            ):
                target = next(
                    (
                        target
                        for target in state.get("targets", [])
                        if (role is None or target.get("role") == role)
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
        f"url={(last or {}).get('url')} title={(last or {}).get('title')} "
        f"revision={(last or {}).get('targetRevision')} "
        f"buttons={[(target.get('name'), target.get('visible'), target.get('enabled'), target.get('inViewport')) for target in (last or {}).get('targets', []) if target.get('role') == 'button']} "
        f"textboxes={[(target.get('name'), target.get('value'), target.get('visible'), target.get('enabled'), target.get('inViewport')) for target in (last or {}).get('targets', []) if target.get('role') == 'textbox']} "
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

def run_public_spa(session_id):
    spa_case = REPLAY_GATE.cases["public-playwright-todomvc-spa"]
    spa_url = spa_case["url"]
    spa_domain = spa_case["allowedDomains"][0]
    spa_opened = create_execute_task(
        session_id,
        {
            "goal": "Open the public TodoMVC browser-testing SPA",
            "startUrl": spa_url,
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-spa-open",
    )
    require_verified(spa_opened, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    try:
        spa_state, new_todo = wait_for_named_target(session_id, "What needs to be done?", role="textbox", timeout=30)
    except AssertionError:
        # The public demo occasionally leaves its React root empty on first load.
        # One normal Agent navigation retries that external page initialization.
        spa_reopened = create_execute_task(
            session_id,
            {
                "goal": "Retry loading the public TodoMVC SPA after an empty first render",
                "startUrl": spa_url,
                "allowedDomains": [spa_domain],
                "maxActions": 8,
                "replanBudget": 1,
            },
            "public-spa-reopen",
        )
        require_verified(spa_reopened, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
        spa_state, new_todo = wait_for_named_target(session_id, "What needs to be done?", role="textbox", timeout=30)
    if new_todo.get("sensitive") or not spa_state.get("url", "").startswith(spa_url):
        raise AssertionError(f"public SPA entry was not an actionable test page: {new_todo}")
    if any(target.get("name") == "Toggle Todo" for target in spa_state["targets"]):
        raise AssertionError("public SPA started with a preexisting todo in the isolated Browser Profile")
    spa_marker = "agent-browser-public-spa-" + uuid.uuid4().hex[:12]
    spa_typed = create_execute_task(
        session_id,
        {
            "goal": "Enter a harmless marker into the public TodoMVC SPA",
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "TYPE_TEXT",
                "targetRef": new_todo["targetRef"],
                "targetRevision": spa_state["targetRevision"],
                "value": spa_marker,
                "dataClass": "PUBLIC",
            }],
        },
        "public-spa-type",
    )
    require_verified(spa_typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
    if spa_marker in json.dumps(spa_typed):
        raise AssertionError("public SPA marker leaked into Agent task response")
    spa_state, new_todo = wait_for_named_target(session_id, "What needs to be done?", role="textbox")
    if new_todo.get("value") != spa_marker:
        raise AssertionError(f"public SPA controlled input did not retain typed marker: {new_todo}")
    spa_added = create_execute_task(
        session_id,
        {
            "goal": "Add the public test todo using the exact current textbox",
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "PRESS_KEY",
                "targetRef": new_todo["targetRef"],
                "targetRevision": spa_state["targetRevision"],
                "key": "Enter",
            }],
        },
        "public-spa-add",
    )
    require_verified(spa_added, ["GET_CURRENT_STATE", "PRESS_KEY", "GET_URL", "GET_PAGE_SUMMARY"])
    spa_state = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and any(target.get("name") == "Toggle Todo" for target in state.get("targets", [])),
    )
    todo_checkbox = next(target for target in spa_state["targets"] if target.get("name") == "Toggle Todo")
    if todo_checkbox.get("checked") is not False:
        raise AssertionError(f"new public SPA todo was not active: {todo_checkbox}")
    spa_state, toggle_label = wait_for_named_target(session_id, "Mark all as complete", role=None)
    spa_checked = create_execute_task(
        session_id,
        {
            "goal": "Complete the only public test todo through its visible associated label",
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": toggle_label["targetRef"],
                "targetRevision": spa_state["targetRevision"],
            }],
        },
        "public-spa-check",
    )
    require_verified(spa_checked, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and any(target.get("name") == "Toggle Todo" and target.get("checked") is True for target in state.get("targets", [])),
    )
    spa_state, completed_filter = wait_for_named_target(session_id, "Completed", role="link")
    spa_filtered = create_execute_task(
        session_id,
        {
            "goal": "Open the completed filter in the public SPA",
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": completed_filter["targetRef"],
                "targetRevision": spa_state["targetRevision"],
            }],
        },
        "public-spa-completed-route",
    )
    require_verified(spa_filtered, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    completed_url = spa_url + spa_case["expectedRoute"]
    completed_state = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("url") == completed_url
        and any(target.get("name") == "Toggle Todo" and target.get("checked") is True for target in state.get("targets", [])),
    )
    away = create_execute_task(
        session_id,
        {
            "goal": "Leave the public SPA so its React document is discarded",
            "startUrl": "https://example.com/",
            "allowedDomains": ["example.com"],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-spa-leave",
    )
    require_verified(away, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    returned = create_execute_task(
        session_id,
        {
            "goal": "Reopen the completed SPA route and verify browser-local recovery",
            "startUrl": completed_url,
            "allowedDomains": [spa_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-spa-return",
    )
    require_verified(returned, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    recovered_state = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("stateVersion", 0) > completed_state["stateVersion"]
        and state.get("url") == completed_url
        and any(target.get("name") == "Toggle Todo" and target.get("checked") is True for target in state.get("targets", [])),
    )
    if recovered_state["title"] != "React • TodoMVC":
        raise AssertionError(f"public SPA title changed after recovery: {recovered_state['title']}")
    REPLAY_GATE.pass_case("public-playwright-todomvc-spa")


def run_public_commerce(session_id):
    commerce_case = REPLAY_GATE.cases["public-saucedemo-cart"]
    commerce_url = commerce_case["url"]
    commerce_domain = commerce_case["allowedDomains"][0]
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
            },
            actor_id="public-commerce-operator",
            roles="TENANT_OPERATOR",
        ),
        200,
        "enable bounded public demo secret input",
    )
    opened = create_execute_task(
        session_id,
        {
            "goal": "Open the public Sauce Labs automation demo login",
            "startUrl": commerce_url,
            "allowedDomains": [commerce_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-commerce-open",
    )
    require_verified(opened, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    login_state, _ = wait_for_named_target(session_id, "Login", role="button")
    for purpose, value in (("USERNAME", "standard_user"), ("PASSWORD", "secret_sauce")):
        if purpose == "USERNAME":
            target = next(
                (item for item in login_state["targets"] if item.get("role") == "textbox"
                 and item.get("name") == "Username" and item.get("visible") and item.get("enabled")),
                None,
            )
        else:
            target = next(
                (item for item in login_state["targets"] if item.get("role") == "textbox"
                 and item.get("sensitive") is True and item.get("visible") and item.get("enabled")),
                None,
            )
        if target is None:
            raise AssertionError(f"public commerce {purpose.lower()} target unavailable")
        secret = require_status(
            request(
                "POST",
                f"/api/v1/sessions/{session_id}/agent-input-secrets",
                {"purpose": purpose, "value": value},
                f"public-commerce-{purpose.lower()}-{uuid.uuid4().hex}",
                actor_id="public-commerce-operator",
                roles="TENANT_OPERATOR",
            ),
            201,
            f"create one-time public commerce {purpose.lower()}",
        )
        if "value" in secret or secret.get("consumed") is not False:
            raise AssertionError("public commerce secret response was not write-only")
        typed = create_execute_task(
            session_id,
            {
                "goal": f"Enter the published demo {purpose.lower()} through one-time input",
                "allowedDomains": [commerce_domain],
                "maxActions": 8,
                "replanBudget": 1,
                "actions": [{
                    "toolId": "TYPE_TEXT",
                    "targetRef": target["targetRef"],
                    "targetRevision": login_state["targetRevision"],
                    "secretId": secret["secretId"],
                    "dataClass": "CREDENTIAL",
                }],
            },
            f"public-commerce-{purpose.lower()}",
        )
        require_verified(typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
        if value in json.dumps(typed):
            raise AssertionError("public commerce credential leaked in Agent task response")
        login_state = wait_for(
            f"/api/v1/sessions/{session_id}/state",
            lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
            and state.get("stateVersion", 0) > login_state["stateVersion"]
            and state.get("url") == commerce_url,
        )
        if purpose == "PASSWORD" and value in json.dumps(login_state):
            raise AssertionError("public commerce password leaked in Browser State")
    login_button = next(
        (item for item in login_state["targets"] if item.get("role") == "button"
         and item.get("name") == "Login" and item.get("visible") and item.get("enabled")),
        None,
    )
    if login_button is None:
        raise AssertionError("public commerce Login button unavailable after secret input")
    logged_in = create_execute_task(
        session_id,
        {
            "goal": "Sign in to the public automation demo",
            "allowedDomains": [commerce_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": login_button["targetRef"],
                "targetRevision": login_state["targetRevision"],
            }],
        },
        "public-commerce-login",
    )
    require_verified(logged_in, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("url") == commerce_url + "inventory.html"
        and any(item.get("name") == "Sort products" for item in state.get("targets", [])),
    )
    detail_url = commerce_url + "inventory-item.html?id=4"
    detail = create_execute_task(
        session_id,
        {
            "goal": "Open the public demo's Backpack product detail",
            "startUrl": detail_url,
            "allowedDomains": [commerce_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-commerce-detail",
    )
    require_verified(detail, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    detail_state, add_button = wait_for_named_target(session_id, "Add to cart", role="button")
    if detail_state.get("url") != detail_url:
        raise AssertionError(f"public commerce detail route changed: {detail_state.get('url')}")
    added = create_execute_task(
        session_id,
        {
            "goal": "Add the published demo Backpack item to the cart",
            "allowedDomains": [commerce_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": add_button["targetRef"],
                "targetRevision": detail_state["targetRevision"],
            }],
        },
        "public-commerce-add",
    )
    require_verified(added, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    cart_state, cart_button = wait_for_named_target(session_id, "Cart, 1 items", role="button")
    opened_cart = create_execute_task(
        session_id,
        {
            "goal": "Inspect the public demo cart without starting checkout",
            "allowedDomains": [commerce_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": cart_button["targetRef"],
                "targetRevision": cart_state["targetRevision"],
            }],
        },
        "public-commerce-cart",
    )
    require_verified(opened_cart, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    cart_result = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("url") == commerce_url.rstrip("/") + commerce_case["expectedCartPath"]
        and any(item.get("name") == "View details for Sauce Labs Backpack" for item in state.get("targets", []))
        and any(item.get("name") == "Checkout" for item in state.get("targets", [])),
    )
    if cart_result.get("title") != "Swag Labs":
        raise AssertionError(f"public commerce cart title changed: {cart_result.get('title')}")
    REPLAY_GATE.pass_case("public-saucedemo-cart")


def run_public_idp(session_id):
    idp_case = REPLAY_GATE.cases["public-duende-idp-login"]
    idp_domain = idp_case["allowedDomains"][0]
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
            },
            actor_id="public-idp-operator",
            roles="TENANT_OPERATOR",
        ),
        200,
        "enable bounded public IdP demo secret input",
    )
    opened = create_execute_task(
        session_id,
        {
            "goal": "Open the published Duende IdentityServer demo login",
            "startUrl": idp_case["url"],
            "allowedDomains": [idp_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        "public-idp-open",
    )
    require_verified(opened, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    login_state, _ = wait_for_named_target(
        session_id, "Username", role="textbox", require_stable=True
    )
    if urlsplit(login_state.get("url", "")).hostname != idp_domain:
        raise AssertionError("public IdP login escaped its exact allowed host")
    for purpose, value in (("USERNAME", "bob"), ("PASSWORD", "bob")):
        for attempt in range(3):
            login_state, _ = wait_for_named_target(
                session_id, "Username", role="textbox", require_stable=True
            )
            if urlsplit(login_state.get("url", "")).hostname != idp_domain:
                raise AssertionError("public IdP login moved to another host before input")
            target = next(
                (
                    item for item in login_state["targets"]
                    if item.get("role") == "textbox"
                    and item.get("visible") and item.get("enabled")
                    and (
                        item.get("name") == "Username" if purpose == "USERNAME"
                        else item.get("sensitive") is True
                    )
                ),
                None,
            )
            if target is None:
                raise AssertionError(f"public IdP {purpose.lower()} target unavailable")
            secret = require_status(
                request(
                    "POST",
                    f"/api/v1/sessions/{session_id}/agent-input-secrets",
                    {"purpose": purpose, "value": value},
                    f"public-idp-{purpose.lower()}-{uuid.uuid4().hex}",
                    actor_id="public-idp-operator",
                    roles="TENANT_OPERATOR",
                ),
                201,
                f"create one-time public IdP {purpose.lower()} input",
            )
            if "value" in secret or secret.get("consumed") is not False:
                raise AssertionError("public IdP secret response was not write-only")
            typed = create_execute_task(
                session_id,
                {
                    "goal": f"Enter the published demo {purpose.lower()} through one-time input",
                    "allowedDomains": [idp_domain],
                    "maxActions": 8,
                    "replanBudget": 1,
                    "actions": [{
                        "toolId": "TYPE_TEXT",
                        "targetRef": target["targetRef"],
                        "targetRevision": login_state["targetRevision"],
                        "secretId": secret["secretId"],
                        "dataClass": "CREDENTIAL",
                    }],
                },
                f"public-idp-{purpose.lower()}-{attempt}",
                terminal_states=("COMPLETED", "FAILED"),
            )
            if typed["state"] == "COMPLETED":
                require_verified(typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
                break
            if typed.get("lastError") != "STATE_STALE" or any(
                result.get("toolId") == "TYPE_TEXT" and result.get("status") == "VERIFIED"
                for result in typed.get("executionResults", [])
            ):
                raise AssertionError(f"public IdP {purpose.lower()} failed: {typed.get('lastError')}")
            if attempt == 2:
                raise AssertionError(f"public IdP {purpose.lower()} stayed stale after bounded retries")
            time.sleep(0.5)
        login_state = wait_for(
            f"/api/v1/sessions/{session_id}/state",
            lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
            and state.get("stateVersion", 0) > login_state["stateVersion"]
            and urlsplit(state.get("url", "")).hostname == idp_domain
            and urlsplit(state.get("url", "")).path == "/Account/Login",
        )
    revealed = create_execute_task(
        session_id,
        {
            "goal": "Reveal the published IdP demo Login button",
            "allowedDomains": [idp_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{"toolId": "SCROLL", "scrollDeltaY": 350}],
        },
        "public-idp-reveal-submit",
    )
    require_verified(revealed, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
    login_state, submit = wait_for_named_target(session_id, "Login", role="button")
    submitted = create_execute_task(
        session_id,
        {
            "goal": "Submit the published Duende demo account and verify its claims page",
            "allowedDomains": [idp_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": submit["targetRef"],
                "targetRevision": login_state["targetRevision"],
            }],
        },
        "public-idp-submit",
    )
    require_verified(submitted, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and urlsplit(state.get("url", "")).hostname == idp_domain
        and urlsplit(state.get("url", "")).path == idp_case["expectedPath"]
        and any(
            "bob Logout" in re.sub(r"\s+", " ", item.get("name") or "")
            for item in state.get("targets", [])
        ),
    )
    REPLAY_GATE.pass_case("public-duende-idp-login")


if os.environ.get("REAL_URL_COMMERCE_ONLY") == "true":
    run_public_commerce(session_id)
    print(json.dumps({"publicCommerce": "verified", "cases": sorted(REPLAY_GATE.passed)}))
    sys.exit(0)

if os.environ.get("REAL_URL_IDP_ONLY") == "true":
    run_public_idp(session_id)
    print(json.dumps({"publicIdp": "verified", "cases": sorted(REPLAY_GATE.passed)}))
    sys.exit(0)


if os.environ.get("REAL_URL_SPA_ONLY") == "true":
    run_public_spa(session_id)
    print(json.dumps({"publicSpa": "verified", "cases": sorted(REPLAY_GATE.passed)}))
    sys.exit(0)

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

# The practice site publishes these values for automation exercises. They are never placed in
# the replay dataset or an Agent plan; even test credentials use the write-only Secret API.
practice_username = "practice"
practice_password = "SuperSecretPassword!"
practice_domain = "practice.expandtesting.com"
practice_url = "https://practice.expandtesting.com/login"
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
        },
        actor_id="public-practice-operator",
        roles="TENANT_OPERATOR",
    ),
    200,
    "enable bounded practice-login secret input",
)


def practice_state(after_version=None, path="/login", timeout=45):
    return wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("url", "").split("?", 1)[0].endswith(path)
        and (path != "/login" or any(
            item.get("role") == "textbox" and item.get("name") == "Username"
            for item in state.get("targets", [])
        ))
        and (path != "/secure" or any(
            "Logout" in (item.get("name") or "") for item in state.get("targets", [])
        ))
        and (after_version is None or state.get("stateVersion", 0) > after_version),
        timeout=timeout,
    )


def practice_target(state, role, name):
    target = next(
        (
            item for item in state.get("targets", [])
            if item.get("role") == role
            and (item.get("sensitive") is True if name == "<sensitive>" else item.get("name") == name)
            and item.get("visible") and item.get("enabled")
        ),
        None,
    )
    if target is None:
        raise AssertionError(
            f"practice login target {role}/{name} unavailable: "
            f"{[(item.get('role'), item.get('name'), item.get('sensitive'), item.get('visible'), item.get('enabled'), item.get('inViewport')) for item in state.get('targets', [])]}"
        )
    return target


def practice_secret(purpose, value, label):
    result = require_status(
        request(
            "POST",
            f"/api/v1/sessions/{session_id}/agent-input-secrets",
            {"purpose": purpose, "value": value},
            f"real-practice-{label}-secret-{uuid.uuid4().hex}",
            actor_id="public-practice-operator",
            roles="TENANT_OPERATOR",
        ),
        201,
        f"create one-time practice {purpose} secret",
    )
    if "value" in result or result.get("consumed") is not False:
        raise AssertionError(f"write-only practice secret was exposed or consumed: {result}")
    return result["secretId"]


for label, password_value, expected_path in (
    ("invalid-password", "wrong-public-practice-password", "/login"),
    ("success", practice_password, "/secure"),
):
    landing = create_execute_task(
        session_id,
        {
            "goal": "Open the site's public automation-practice login page",
            "startUrl": practice_url,
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        f"practice-{label}-navigate",
    )
    require_verified(landing, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("pageActivity") == "STABLE"
        and state.get("freshness") == "FRESH"
        and urlsplit(state.get("url", "")).hostname == practice_domain,
    )
    revealed = create_execute_task(
        session_id,
        {
            "goal": "Reveal the practice login form below the page header",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{"toolId": "SCROLL", "scrollDeltaY": 500}],
        },
        f"practice-{label}-scroll",
    )
    require_verified(revealed, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
    login_state = practice_state()
    for purpose, value, name in (
        ("USERNAME", practice_username, "Username"),
        ("PASSWORD", password_value, "<sensitive>"),
    ):
        target = practice_target(login_state, "textbox", name)
        if purpose == "PASSWORD" and target.get("sensitive") is not True:
            raise AssertionError("practice password field was not classified as sensitive")
        secret_id = practice_secret(purpose, value, f"{label}-{purpose.lower()}")
        typed = create_execute_task(
            session_id,
            {
                "goal": f"Enter the site's published practice {purpose.lower()} through one-time input",
                "allowedDomains": [practice_domain],
                "maxActions": 8,
                "replanBudget": 1,
                "actions": [{
                    "toolId": "TYPE_TEXT",
                    "targetRef": target["targetRef"],
                    "targetRevision": login_state["targetRevision"],
                    "secretId": secret_id,
                    "dataClass": "CREDENTIAL",
                }],
            },
            f"practice-{label}-{purpose.lower()}",
        )
        require_verified(typed, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
        if purpose == "PASSWORD" and value in json.dumps(typed):
            raise AssertionError(f"practice {purpose.lower()} leaked in Agent task response")
        login_state = practice_state(after_version=login_state["stateVersion"])
        if purpose == "PASSWORD" and value in json.dumps(login_state):
            raise AssertionError(f"practice {purpose.lower()} leaked in Browser State")
    submit = practice_target(login_state, "button", "Login")
    submitted = create_execute_task(
        session_id,
        {
            "goal": "Submit the public practice account and observe the site's result",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": submit["targetRef"],
                "targetRevision": login_state["targetRevision"],
            }],
        },
        f"practice-{label}-submit",
    )
    require_verified(submitted, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    result_state = practice_state(after_version=login_state["stateVersion"], path=expected_path)
    if result_state.get("url", "").split("?", 1)[0] != f"https://{practice_domain}{expected_path}":
        raise AssertionError(f"practice {label} ended at unexpected URL: {result_state.get('url')}")
    if label == "invalid-password":
        result_state = wait_for(
            f"/api/v1/sessions/{session_id}/state",
            lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
            and state.get("url", "").split("?", 1)[0] == f"https://{practice_domain}/login"
            and any(
                target.get("role") == "alert" and "password is invalid" in (target.get("name") or "").lower()
                for target in state.get("targets", [])
            ),
        )
    if label == "success" and not any(
        "Logout" in (target.get("name") or "") for target in result_state.get("targets", [])
    ):
        raise AssertionError("practice success page did not expose Logout")
    REPLAY_GATE.pass_case(f"public-expandtesting-login-{label}")

if os.environ.get("REAL_URL_LOGIN_ONLY") == "true":
    print(json.dumps({"practiceLogin": "verified", "cases": sorted(REPLAY_GATE.passed)}))
    sys.exit(0)

# The practice server keeps the successful login cookie. Log out through the Browser so the
# following OTP cases exercise their unauthenticated entry point instead of a redirect.
logged_out = create_execute_task(
    session_id,
    {
        "goal": "End the public practice login before testing the separate OTP flow",
        "startUrl": f"https://{practice_domain}/logout",
        "allowedDomains": [practice_domain],
        "maxActions": 8,
        "replanBudget": 1,
    },
    "practice-login-logout",
)
require_verified(logged_out, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
wait_for(
    f"/api/v1/sessions/{session_id}/state",
    lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
    and state.get("url", "").split("?", 1)[0] != f"https://{practice_domain}/secure",
)

# This separate public practice flow publishes a fixed mailbox and OTP. The test never reads
# a real inbox and never places the six-digit code in an Agent plan or Replay catalog.
practice_email = "practice@expandtesting.com"
practice_otp = "214365"
otp_url = "https://practice.expandtesting.com/otp-login"
for label, code, expected_path in (
    ("invalid", "111111", "/otp-verification"),
    ("success", practice_otp, "/secure"),
):
    landing = create_execute_task(
        session_id,
        {
            "goal": "Open the site's public OTP automation-practice page",
            "startUrl": otp_url,
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
        },
        f"practice-otp-{label}-navigate",
    )
    require_verified(landing, ["NAVIGATE", "GET_CURRENT_STATE", "GET_URL", "GET_PAGE_SUMMARY"])
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("pageActivity") == "STABLE"
        and state.get("freshness") == "FRESH"
        and state.get("url", "").split("?", 1)[0] == otp_url,
    )
    revealed = create_execute_task(
        session_id,
        {
            "goal": "Reveal the public OTP practice email form",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{"toolId": "SCROLL", "scrollDeltaY": 500}],
        },
        f"practice-otp-{label}-scroll-email",
    )
    require_verified(revealed, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
    email_state = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("freshness") == "FRESH"
        and state.get("pageActivity") == "STABLE"
        and state.get("url", "").split("?", 1)[0] == otp_url
        and any(target.get("name") == "Your Email Address" and target.get("visible") for target in state.get("targets", [])),
    )
    email_target = practice_target(email_state, "textbox", "Your Email Address")
    email_secret = practice_secret("USERNAME", practice_email, f"otp-{label}-email")
    typed_email = create_execute_task(
        session_id,
        {
            "goal": "Enter the site's published practice mailbox through one-time input",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "TYPE_TEXT",
                "targetRef": email_target["targetRef"],
                "targetRevision": email_state["targetRevision"],
                "secretId": email_secret,
                "dataClass": "CREDENTIAL",
            }],
        },
        f"practice-otp-{label}-email",
    )
    require_verified(typed_email, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
    email_state = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("freshness") == "FRESH"
        and state.get("pageActivity") == "STABLE"
        and state.get("stateVersion", 0) > email_state["stateVersion"]
        and any(target.get("name") == "Your Email Address" and target.get("visible") for target in state.get("targets", [])),
    )
    # Its id contains "otp", so the Browser State intentionally redacts the button name.
    send = practice_target(email_state, "button", "<sensitive>")
    sent = create_execute_task(
        session_id,
        {
            "goal": "Request the site's published practice OTP flow",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": send["targetRef"],
                "targetRevision": email_state["targetRevision"],
            }],
        },
        f"practice-otp-{label}-send",
    )
    require_verified(sent, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    otp_stage = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("url", "").split("?", 1)[0] == otp_url
        and state.get("title") == "OTP Verification page for Automation Testing Practice"
        and any(target.get("sensitive") is True and target.get("role") == "textbox" for target in state.get("targets", [])),
    )
    otp_target = next(
        target for target in otp_stage["targets"]
        if target.get("sensitive") is True and target.get("role") == "textbox"
    )
    if not otp_target.get("visible"):
        scrolled = create_execute_task(
            session_id,
            {
                "goal": "Reveal the public OTP verification field",
                "allowedDomains": [practice_domain],
                "maxActions": 8,
                "replanBudget": 1,
                "actions": [{"toolId": "SCROLL", "scrollDeltaY": 500}],
            },
            f"practice-otp-{label}-scroll-code",
        )
        require_verified(scrolled, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
        otp_stage = wait_for(
            f"/api/v1/sessions/{session_id}/state",
            lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
            and state.get("freshness") == "FRESH"
            and state.get("pageActivity") == "STABLE"
            and state.get("title") == "OTP Verification page for Automation Testing Practice"
            and any(target.get("sensitive") is True and target.get("visible") for target in state.get("targets", [])),
        )
    wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("freshness") == "FRESH"
        and state.get("pageActivity") == "STABLE"
        and state.get("title") == "OTP Verification page for Automation Testing Practice",
    )
    revealed_submit = create_execute_task(
        session_id,
        {
            "goal": "Reveal the site's public OTP verification submit button",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{"toolId": "SCROLL", "scrollDeltaY": 160}],
        },
        f"practice-otp-{label}-scroll-submit",
        terminal_states=("COMPLETED", "WAITING_FOR_HUMAN"),
    )
    code_entered = False
    if revealed_submit["state"] == "WAITING_FOR_HUMAN":
        challenge_event_id = revealed_submit.get("challengeEventId")
        otp_challenge_state = wait_for(
            f"/api/v1/sessions/{session_id}/state",
            lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
            and any(target.get("sensitive") is True and target.get("visible") for target in state.get("targets", [])),
        )
        otp_challenge_target = practice_target(otp_challenge_state, "textbox", "<sensitive>")
        timeline = require_status(
            request("GET", f"/api/v1/sessions/{session_id}/challenges"),
            200,
            "read practice OTP Challenge timeline",
        )
        challenge = next(
            (event for event in timeline.get("items", []) if event.get("challengeEventId") == challenge_event_id),
            None,
        )
        if challenge is None or challenge.get("suspectedType") != "OTP" or challenge.get("targetRef") != otp_challenge_target["targetRef"]:
            raise AssertionError(f"practice OTP Challenge did not bind the exact sensitive target: {challenge}")
        otp_secret = practice_secret("OTP", code, f"otp-{label}-code")
        response = require_status(
            request(
                "POST",
                f"/api/v1/challenges/{challenge_event_id}/input-responses",
                {"secretId": otp_secret},
                f"real-practice-otp-{label}-response-{uuid.uuid4().hex}",
                actor_id="public-practice-operator",
                roles="TENANT_OPERATOR",
            ),
            202,
            f"respond to practice OTP {label} Challenge",
        )
        if response.get("purpose") != "OTP" or response.get("taskId") != revealed_submit["taskId"]:
            raise AssertionError(f"practice OTP response was not bound to original Task: {response}")
        revealed_submit = wait_for(
            f"/api/v1/agent-tasks/{revealed_submit['taskId']}",
            lambda task: task.get("state") in {"COMPLETED", "FAILED", "BLOCKED"},
            timeout=90,
        )
        if revealed_submit.get("state") != "COMPLETED":
            raise AssertionError(f"practice OTP {label} Task did not resume: {revealed_submit}")
        code_entered = True
    require_verified(revealed_submit, ["GET_CURRENT_STATE", "SCROLL", "GET_URL", "GET_PAGE_SUMMARY"])
    otp_stage = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("freshness") == "FRESH"
        and state.get("pageActivity") == "STABLE"
        and state.get("title") == "OTP Verification page for Automation Testing Practice"
        and any(target.get("sensitive") is True and target.get("visible") for target in state.get("targets", []))
        and any(target.get("name") == "Verify OTP Code" and target.get("visible") for target in state.get("targets", [])),
    )
    if not code_entered:
        otp_target = practice_target(otp_stage, "textbox", "<sensitive>")
        otp_secret = practice_secret("OTP", code, f"otp-{label}-code")
        typed_otp = create_execute_task(
            session_id,
            {
                "goal": "Enter the site's published practice code through one-time OTP input",
                "allowedDomains": [practice_domain],
                "maxActions": 8,
                "replanBudget": 1,
                "actions": [{
                    "toolId": "TYPE_TEXT",
                    "targetRef": otp_target["targetRef"],
                    "targetRevision": otp_stage["targetRevision"],
                    "secretId": otp_secret,
                    "dataClass": "OTP",
                }],
            },
            f"practice-otp-{label}-code",
        )
        require_verified(typed_otp, ["GET_CURRENT_STATE", "TYPE_TEXT", "GET_URL", "GET_PAGE_SUMMARY"])
        if code in json.dumps(typed_otp):
            raise AssertionError("practice OTP leaked in Agent task response")
    elif code in json.dumps(revealed_submit):
        raise AssertionError("practice OTP leaked in resumed Agent task response")
    submit_state, submit_button = wait_for_named_target(
        session_id, "Verify OTP Code", require_stable=True
    )
    submitted = create_execute_task(
        session_id,
        {
            "goal": "Submit the site's published practice OTP and observe its result",
            "allowedDomains": [practice_domain],
            "maxActions": 8,
            "replanBudget": 1,
            "actions": [{
                "toolId": "CLICK_TARGET",
                "targetRef": submit_button["targetRef"],
                "targetRevision": submit_state["targetRevision"],
            }],
        },
        f"practice-otp-{label}-submit",
    )
    require_verified(submitted, ["GET_CURRENT_STATE", "CLICK_TARGET", "GET_URL", "GET_PAGE_SUMMARY"])
    result = wait_for(
        f"/api/v1/sessions/{session_id}/state",
        lambda state: state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
        and state.get("stateVersion", 0) > otp_stage["stateVersion"]
        and state.get("url", "").split("?", 1)[0] == f"https://{practice_domain}{expected_path}"
        and (label != "invalid" or state.get("title") == "OTP Page page for Automation Testing Practice")
        and (label != "success" or any("Logout" in (target.get("name") or "") for target in state.get("targets", []))),
    )
    if code in json.dumps(result):
        raise AssertionError("practice OTP leaked in final Browser State")
    REPLAY_GATE.pass_case(f"public-expandtesting-otp-{label}")

if os.environ.get("REAL_URL_OTP_ONLY") == "true":
    print(json.dumps({"practiceOtp": "verified", "cases": sorted(REPLAY_GATE.passed)}))
    sys.exit(0)

run_public_spa(session_id)
run_public_commerce(session_id)
run_public_idp(session_id)

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
deadline = time.monotonic() + 90
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
example_state = wait_for(
    f"/api/v1/sessions/{session_id}/state",
    lambda state: state.get("url") == "https://example.com/"
    and state.get("stateQuality") in {"COMPLETE", "DEPTH_LIMITED"}
    and state.get("freshness") == "FRESH"
    and state.get("pageActivity") == "STABLE"
    and any(
        target.get("role") == "link"
        and target.get("visible")
        and target.get("enabled")
        for target in state.get("targets", [])
    ),
)
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
    raise AssertionError(
        f"cross-domain click did not reach the domain guard: {diagnostic(failed_click)}"
    )
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
            "publicUrls": [url for _, url, _ in sites] + [form_case["url"], practice_url, otp_url, SPA_URL, COMMERCE_URL, IDP_URL],
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
