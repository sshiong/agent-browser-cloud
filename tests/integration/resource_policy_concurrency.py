#!/usr/bin/env python3
"""Force an uncommitted policy-version writer in the isolated smoke database."""

from concurrent.futures import ThreadPoolExecutor
import json
import re
import select
import subprocess
import sys
import time
from urllib.request import Request, urlopen


def main():
    container, port, session_id, cpu, memory = sys.argv[1:]
    # This fault injector must never target a developer's persistent Compose database.
    assert re.fullmatch(r"agentbrowser-postgres-it-[A-Za-z0-9_-]+", container)
    assert re.fullmatch(r"ses_[A-Za-z0-9]+", session_id)
    port, cpu, memory = int(port), int(cpu), int(memory)
    assert 0 < port < 65536 and cpu > 0 and memory > 0
    psql = ["docker", "exec", "-i", container, "psql", "-U", "browsercloud",
            "-d", "browsercloud", "-v", "ON_ERROR_STOP=1", "-qAt"]

    def query(sql):
        return subprocess.check_output(psql + ["-c", sql], text=True, timeout=10).strip()

    # Verify the actual Flyway database, not an in-memory substitute or stale entity assumption.
    assert query("SELECT data_type || ':' || is_nullable FROM information_schema.columns "
                 "WHERE table_schema='public' AND table_name='session_resource_policies' "
                 "AND column_name='version'") == "bigint:NO"
    assert query("SELECT count(*) FROM pg_constraint WHERE "
                 "conrelid='public.session_resource_policies'::regclass AND contype='p'") == "1"

    payload = json.dumps({"mode": "AUTO", "maximumCpuMillis": cpu,
                          "maximumMemoryMib": memory, "allowMigration": True,
                          "allowHibernate": True,
                          "onMaximumReached": "WAIT_SAFE_POINT_MIGRATE"}).encode()

    def patch():
        request = Request(
            f"http://localhost:{port}/api/v1/sessions/{session_id}/resource-policy",
            data=payload, method="PATCH", headers={
                "Content-Type": "application/json", "X-Tenant-Id": "tenant-integration",
                "X-Actor-Id": "dual-node-resource-policy", "X-Roles": "PLATFORM_ADMIN",
                "Idempotency-Key": "smoke-resource-policy-concurrent-001"})
        with urlopen(request, timeout=25) as response:
            assert response.status == 202, response.status
            return json.load(response)

    holder = subprocess.Popen(psql, stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    try:
        holder.stdin.write(
            "BEGIN; SET LOCAL idle_in_transaction_session_timeout='30s'; "
            "SET LOCAL statement_timeout='20s'; "
            "UPDATE session_resource_policies SET version=version+1 "
            f"WHERE session_id='{session_id}' AND tenant_id='tenant-integration' "
            "RETURNING pg_backend_pid();\n")
        holder.stdin.flush()
        assert select.select([holder.stdout], [], [], 20)[0], "policy writer did not acquire lock"
        holder_pid = int(holder.stdout.readline().strip())
        with ThreadPoolExecutor(max_workers=1) as executor:
            pending = executor.submit(patch)
            try:
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    # Synchronize on PostgreSQL's real wait graph, not machine-speed sleeps.
                    waiting = query(
                        "SELECT count(*) FROM pg_stat_activity WHERE "
                        f"{holder_pid}=ANY(pg_blocking_pids(pid)) "
                        "AND query ILIKE '%session_resource_policies%'")
                    if int(waiting) > 0:
                        break
                    assert not pending.done(), "PATCH did not wait for the concurrent writer"
                    time.sleep(0.05)
                else:
                    raise AssertionError("PATCH never waited on the policy row")
            finally:
                # Always release the injected lock before waiting on the HTTP future.
                holder.stdin.write("COMMIT;\n\\q\n")
                holder.stdin.flush()
            result = pending.result(timeout=25)
        assert result["state"] == "COMMITTED", result
        replay = patch()
        assert replay["operationId"] == result["operationId"]
        operation_id = result["operationId"]
        assert re.fullmatch(r"op_[A-Za-z0-9]+", operation_id)
        assert query("SELECT count(*) FROM session_resource_events "
                     f"WHERE operation_id='{operation_id}' AND event_type='POLICY_UPDATED'") == "1"
        print(json.dumps(result))
        print("resource_policy_concurrent_patch=true", file=sys.stderr)
    finally:
        if holder.poll() is None:
            holder.stdin.close()  # EOF also rolls back a transaction if setup failed.
        holder.wait(timeout=35)
        holder.stdout.close()


if __name__ == "__main__":
    main()
