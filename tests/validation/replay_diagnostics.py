"""Bounded, value-free summaries for replay assertion failures.

Page text, URLs, API error text and secret references are deliberately omitted.
Unknown fields and strings never become diagnostic output, including hashes of OTPs.
"""


ENUMS = frozenset({
    "COMPLETE", "DEPTH_LIMITED", "PARTIAL", "UNAVAILABLE", "FRESH", "STALE",
    "STABLE", "ACTIVE", "CREATED", "RUNNING", "HIBERNATED", "TERMINATED",
    "PLANNED", "COMPLETED", "FAILED", "BLOCKED", "PAUSED", "VERIFIED",
    "REJECTED", "PENDING", "IN_PROGRESS", "ACCEPTED", "CONSUMED", "AVAILABLE",
    "STATE_QUALITY_NOT_EXECUTABLE", "STATE_STALE", "TARGET_REVISION_STALE",
    "NAVIGATE_RESPONSE_TIMEOUT", "NET_CONNECTION_REFUSED", "NET_NAME_NOT_RESOLVED",
    "GET_CURRENT_STATE", "NAVIGATE", "CLICK_TARGET", "TYPE_TEXT", "SCROLL",
    "GET_URL", "GET_PAGE_SUMMARY", "textbox", "button", "link", "checkbox",
    "loading", "interactive", "complete",
})
ENUM_FIELDS = frozenset({
    "state", "status", "stateQuality", "freshness", "pageActivity",
    "documentReadyState", "blockedReason", "reasonCode", "toolId", "role", "lastError",
})
BOOL_FIELDS = frozenset({
    "visible", "enabled", "inViewport", "interactive", "sensitive", "occluded",
    "networkEvidenceFresh", "domEvidenceFresh", "layoutEvidenceFresh",
    "focusEvidenceFresh", "routeEvidenceFresh",
})
NUMBER_FIELDS = frozenset({
    "stateVersion", "targetRevision", "networkQuietMillis", "pendingRequestCount",
    "domQuietMillis", "layoutQuietMillis", "focusQuietMillis", "routeQuietMillis",
})
STRUCTURE_FIELDS = frozenset({
    "targets", "pageStability", "executionResults", "executionHistory", "memory",
    "steps", "state", "challenges", "run", "items", "output",
})


def diagnostic(value, _depth=0):
    if _depth > 5:
        return {"truncated": True}
    if value is None or isinstance(value, bool):
        return value
    if isinstance(value, (int, float, str)):
        return {"redacted": True}
    if isinstance(value, (list, tuple)):
        return {"count": len(value), "items": [diagnostic(item, _depth + 1) for item in value[:8]]}
    if not isinstance(value, dict):
        return {"redacted": True}
    result = {}
    for key in ENUM_FIELDS:
        item = value.get(key)
        if isinstance(item, str) and item in ENUMS:
            result[key] = item
    for key in BOOL_FIELDS:
        if isinstance(value.get(key), bool):
            result[key] = value[key]
    for key in NUMBER_FIELDS:
        item = value.get(key)
        if isinstance(item, int) and not isinstance(item, bool) and 0 <= item <= 1_000_000_000_000:
            result[key] = item
    for key in ("httpStatus", "expectedHttpStatus"):
        item = value.get(key)
        if isinstance(item, int) and not isinstance(item, bool) and 100 <= item <= 599:
            result[key] = item
    for key in STRUCTURE_FIELDS:
        if isinstance(value.get(key), (dict, list, tuple)):
            result[key] = diagnostic(value[key], _depth + 1)
    return result or {"redacted": True}
