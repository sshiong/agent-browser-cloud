#!/usr/bin/env python3
"""Read-only loopback snapshot/inspect gate with exact stale-cursor recovery."""

import argparse
import json
import re
import sys
import urllib.error
import urllib.request
from urllib.parse import urlsplit


class PerceptionReadError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def inspect_current_target(base_url, tenant_id):
    try:
        parsed = urlsplit(base_url)
        valid = (parsed.scheme == "http" and parsed.hostname in {"localhost", "127.0.0.1", "::1"}
                 and parsed.port is not None and not parsed.username and not parsed.password
                 and not parsed.query and not parsed.fragment
                 and re.fullmatch(r"/api/v1/sessions/[a-zA-Z0-9_-]{1,128}/agent-browser", parsed.path)
                 and re.fullmatch(r"[a-zA-Z0-9_.:-]{1,128}", tenant_id))
    except ValueError:
        valid = False
    if not valid:
        raise PerceptionReadError("PERCEPTION_READ_SCOPE_INVALID")
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    headers = {"X-Tenant-Id": tenant_id, "X-Roles": "TENANT_VIEWER",
               "Accept": "application/json", "Content-Type": "application/json"}

    def read(path, body=None):
        request = urllib.request.Request(base_url + path, headers=headers,
                                         data=None if body is None else json.dumps(body).encode(),
                                         method="GET" if body is None else "POST")
        try:
            with opener.open(request, timeout=10) as response:
                payload = response.read(2_097_153)
                if response.status != 200 or len(payload) > 2_097_152:
                    raise PerceptionReadError("PERCEPTION_READ_RESPONSE_INVALID")
                return json.loads(payload)
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read(65_537)
            rejected = None
            if len(payload) <= 65_536:
                try:
                    rejected = json.loads(payload)
                except (ValueError, UnicodeDecodeError):
                    pass
            stale = (path == "/inspect" and error.code == 409
                     and isinstance(rejected, dict)
                     and rejected.get("code") == "AGENT_BROWSER_PERCEPTION_REJECTED"
                     and isinstance(rejected.get("details"), dict)
                     and rejected["details"].get("reason") == "STATE_CURSOR_STALE")
            if stale:
                return None
            raise PerceptionReadError(f"PERCEPTION_READ_HTTP_REJECTED status={error.code} reason=OTHER") from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise PerceptionReadError("PERCEPTION_READ_TRANSPORT_FAILED") from None
        except (ValueError, UnicodeDecodeError):
            raise PerceptionReadError("PERCEPTION_READ_RESPONSE_INVALID") from None

    identity = None
    for attempt in range(1, 4):
        snapshot = read("/snapshot")
        try:
            cursor = snapshot["stateCursor"]
            state = snapshot["state"]
            tab = state["activeTabId"]
            targets = [item for item in state["targets"] if item["visible"] is True
                       and item.get("elementId") and item.get("role")]
            if not isinstance(cursor, str) or cursor.count(":") != 2 or not targets:
                raise ValueError()
            if identity is None:
                identity = (targets[0]["elementId"], targets[0]["role"], tab)
            selected = next((item for item in targets if (item["elementId"], item["role"], tab) == identity), None)
            if selected is None:
                raise PerceptionReadError("PERCEPTION_READ_IDENTITY_CHANGED")
            if not re.fullmatch(r"[a-zA-Z][a-zA-Z0-9_-]{0,63}", identity[1]):
                raise ValueError()
            inspected = read("/inspect", {"stateCursor": cursor, "elementIds": [identity[0]]})
            if inspected is None:
                print(f"PERCEPTION_READ_CURSOR_STALE attempt={attempt} max_attempts=3", file=sys.stderr)
                continue
            if inspected["stateCursor"] != cursor or len(inspected["targets"]) != 1:
                raise ValueError()
            target = inspected["targets"][0]
            if target["elementId"] != identity[0] or not all(
                    isinstance(target[key], bool) for key in ("interactive", "inViewport", "occluded")):
                raise ValueError()
            return identity[1]
        except (KeyError, TypeError, ValueError):
            raise PerceptionReadError("PERCEPTION_READ_RESPONSE_INVALID") from None
    raise PerceptionReadError("PERCEPTION_READ_CURSOR_RETRY_EXHAUSTED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("base_url")
    parser.add_argument("tenant_id")
    args = parser.parse_args()
    try:
        print(inspect_current_target(args.base_url, args.tenant_id))
    except PerceptionReadError as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
