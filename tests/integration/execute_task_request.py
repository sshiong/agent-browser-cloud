#!/usr/bin/env python3
"""Execute a loopback fixture Task with the API's transaction-abort retry contract."""

import argparse
import json
import os
import re
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import urlsplit


class ExecutionRequestError(RuntimeError):
    pass


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, response, code, message, headers, new_url):
        return None


def execute_task_request(url, tenant_id, idempotency_key, *, max_attempts=3,
                         timeout=20, sleep=time.sleep):
    try:
        parsed = urlsplit(url)
        port = parsed.port
    except ValueError:
        raise ExecutionRequestError("EXECUTE_REQUEST_SCOPE_INVALID") from None
    if (parsed.scheme not in {"http", "https"}
            or parsed.hostname not in {"localhost", "127.0.0.1", "::1"}
            or port is None or parsed.username or parsed.password
            or parsed.query or parsed.fragment
            or not re.fullmatch(r"/api/v1/agent-tasks/agt_[a-zA-Z0-9]{16,128}:execute", parsed.path)
            or not re.fullmatch(r"[a-zA-Z0-9_.:-]{1,128}", idempotency_key)
            or not re.fullmatch(r"[a-zA-Z0-9_.:-]{1,128}", tenant_id)
            or not 1 <= max_attempts <= 3 or not 0 < timeout <= 20):
        raise ExecutionRequestError("EXECUTE_REQUEST_SCOPE_INVALID")
    # The fixture's loopback API must not follow a redirect or use an environment proxy.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    headers = {"X-Tenant-Id": tenant_id, "Idempotency-Key": idempotency_key,
               "Accept": "application/json"}
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(url, headers=headers, method="POST")
        try:
            with opener.open(request, timeout=timeout) as response:
                payload = response.read(1_048_577)
                if response.status != 200 or len(payload) > 1_048_576:
                    raise ExecutionRequestError("EXECUTE_RESPONSE_INVALID")
                return payload
        except urllib.error.HTTPError as error:
            with error:
                payload = error.read(65_537)
            body = None
            if len(payload) <= 65_536:
                try:
                    body = json.loads(payload)
                except (ValueError, UnicodeDecodeError):
                    pass
            retry = (error.code == 503 and isinstance(body, dict)
                     and body.get("code") == "DATABASE_TRANSACTION_RETRY"
                     and isinstance(body.get("details"), dict)
                     and body["details"].get("retryable") is True)
            code = "DATABASE_TRANSACTION_RETRY" if retry else "OTHER"
            if retry and attempt < max_attempts:
                print(f"EXECUTE_TRANSACTION_RETRY attempt={attempt} max_attempts={max_attempts}",
                      file=sys.stderr)
                sleep(0.1 * attempt)
                continue
            raise ExecutionRequestError(
                f"EXECUTE_HTTP_REJECTED status={error.code} code={code} attempts={attempt}"
            ) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            # The outcome can be ambiguous. This helper never replays a transport failure.
            raise ExecutionRequestError(f"EXECUTE_TRANSPORT_FAILED attempts={attempt}") from None
    raise ExecutionRequestError("EXECUTE_RETRY_BUDGET_EXHAUSTED")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--tenant", required=True)
    parser.add_argument("--idempotency-key", required=True)
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    try:
        payload = execute_task_request(args.url, args.tenant, args.idempotency_key)
    except ExecutionRequestError as error:
        print(str(error), file=sys.stderr)
        return 1
    descriptor = os.open(args.output, os.O_WRONLY | os.O_CREAT | os.O_TRUNC | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "wb") as handle:
        os.fchmod(handle.fileno(), 0o600)
        handle.write(payload)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
