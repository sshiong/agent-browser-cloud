#!/usr/bin/env python3
"""Isolated PostgreSQL FK/audit lock canary; not a production capacity certificate."""

import concurrent.futures
import json
import queue
import re
import subprocess
import sys
import threading
import time
import uuid


class Client:
    def __init__(self, container, schema, name):
        self.process = subprocess.Popen(
            ["docker", "exec", "-i", "-e", "PGAPPNAME=" + name, container,
             "psql", "-X", "-qAt", "-U", "browsercloud", "-d", "browsercloud"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
        self.output = queue.Queue()

        def read():
            for line in self.process.stdout:
                self.output.put(line.rstrip("\n"))
            self.output.put("PROCESS_ENDED")

        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        try:
            status, _ = self.run(
                f"SET search_path={schema}; SET deadlock_timeout='100ms'; SET lock_timeout='5s';")
            if status != "00000":
                raise RuntimeError("AUDIT_LOCK_CANARY_SETUP_FAILED")
        except BaseException:
            self.process.stdin.close()
            if self.process.poll() is None:
                self.process.kill()
            self.process.wait(timeout=5)
            self.process.stdout.close()
            self.reader.join(timeout=1)
            raise

    def run(self, sql):
        self.process.stdin.write(sql + "\n\\echo PROOF_DONE :SQLSTATE\n")
        self.process.stdin.flush()
        rows = []
        deadline = time.monotonic() + 10
        while True:
            line = self.output.get(timeout=max(.001, deadline - time.monotonic()))
            if line.startswith("PROOF_DONE "):
                return line.split()[1], rows
            if line == "PROCESS_ENDED":
                raise RuntimeError("AUDIT_LOCK_CANARY_CLIENT_ENDED")
            rows.append(line)

    def close(self):
        self.process.stdin.write("ROLLBACK;\n\\q\n")
        self.process.stdin.flush()
        self.process.wait(timeout=5)
        self.process.stdin.close()
        self.process.stdout.close()
        self.reader.join(timeout=1)


def main(container):
    if not re.fullmatch(r"agentbrowser-postgres-it-[a-zA-Z0-9-]{1,96}", container):
        raise ValueError("AUDIT_LOCK_CANARY_CONTAINER_SCOPE_INVALID")
    schema = "audit_lock_fixture_" + uuid.uuid4().hex
    agent_name = schema + "_agent"
    clients = []
    try:
        # The image starts a temporary Unix-socket server during initialization. Do not
        # mistake that server's pg_isready/SELECT 1 for the final PID 1 PostgreSQL process.
        deadline = time.monotonic() + 40
        while time.monotonic() < deadline:
            final_process = subprocess.run(["docker", "exec", container, "cat", "/proc/1/comm"],
                capture_output=True, text=True, timeout=2)
            if final_process.returncode == 0 and final_process.stdout.strip() == "postgres":
                ready = subprocess.run(["docker", "exec", container, "psql", "-X", "-qAt",
                    "-U", "browsercloud", "-d", "browsercloud", "-c", "SELECT 1;"],
                    capture_output=True, text=True, timeout=2)
                if ready.returncode == 0 and ready.stdout.strip() == "1":
                    break
            time.sleep(.1)
        else:
            raise RuntimeError("AUDIT_LOCK_CANARY_FINAL_DATABASE_NOT_READY")
        for name in [agent_name, schema + "_lifecycle", schema + "_observer"]:
            clients.append(Client(container, schema, name))
        agent, lifecycle, observer = clients
        status, _ = observer.run(f"""
CREATE SCHEMA {schema};
CREATE TABLE sessions(id text PRIMARY KEY);
CREATE TABLE agent_tasks(task_id text PRIMARY KEY);
CREATE TABLE tenant_audit_heads(tenant_id text PRIMARY KEY, sequence_no bigint NOT NULL);
CREATE TABLE agent_execution_jobs(job_id text PRIMARY KEY,
 task_id text REFERENCES agent_tasks(task_id), session_id text REFERENCES sessions(id));
INSERT INTO sessions VALUES ('fixture-session');
INSERT INTO agent_tasks VALUES ('fixture-task');
INSERT INTO tenant_audit_heads VALUES ('fixture-tenant',0);
""")
        assert status == "00000", "AUDIT_LOCK_CANARY_SCHEMA_FAILED"

        def assert_agent_waiting():
            deadline = time.monotonic() + 3
            while time.monotonic() < deadline:
                status, rows = observer.run(
                    "SELECT count(*) FROM pg_stat_activity WHERE application_name='"
                    + agent_name + "' AND wait_event_type='Lock';")
                assert status == "00000"
                if rows == ["1"]:
                    return
                time.sleep(.01)
            raise AssertionError("AUDIT_LOCK_CANARY_FK_WAIT_NOT_OBSERVED")

        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            assert agent.run("BEGIN; SELECT tenant_id FROM tenant_audit_heads FOR UPDATE;")[0] == "00000"
            assert lifecycle.run("BEGIN; SELECT id FROM sessions FOR UPDATE;")[0] == "00000"
            insert = pool.submit(agent.run,
                "INSERT INTO agent_execution_jobs VALUES ('old-job','fixture-task','fixture-session');")
            assert_agent_waiting()
            audit = pool.submit(lifecycle.run, "SELECT tenant_id FROM tenant_audit_heads FOR UPDATE;")
            old_states = [insert.result(timeout=8)[0], audit.result(timeout=8)[0]]
            assert sorted(old_states) == ["00000", "40P01"], "AUDIT_LOCK_CANARY_OLD_ORDER_DID_NOT_DEADLOCK"
            agent.run("ROLLBACK;")
            lifecycle.run("ROLLBACK;")

            assert lifecycle.run("BEGIN; SELECT id FROM sessions FOR UPDATE;")[0] == "00000"
            assert agent.run("BEGIN;")[0] == "00000"
            insert = pool.submit(agent.run,
                "INSERT INTO agent_execution_jobs VALUES ('new-job','fixture-task','fixture-session');")
            assert_agent_waiting()
            assert lifecycle.run("SELECT tenant_id FROM tenant_audit_heads FOR UPDATE; "
                "UPDATE tenant_audit_heads SET sequence_no=sequence_no+1; COMMIT;")[0] == "00000"
            assert insert.result(timeout=8)[0] == "00000"
            assert agent.run("SELECT tenant_id FROM tenant_audit_heads FOR UPDATE; "
                "UPDATE tenant_audit_heads SET sequence_no=sequence_no+1; COMMIT;")[0] == "00000"
            status, rows = observer.run(
                "SELECT sequence_no,(SELECT count(*) FROM agent_execution_jobs) FROM tenant_audit_heads;")
            assert status == "00000" and rows == ["2|1"], "AUDIT_LOCK_CANARY_COMMIT_PROOF_INVALID"
            print(json.dumps({"audit_parent_lock_order": True, "old_order_deadlock": True,
                              "new_order_both_committed": True, "execution_jobs": 1,
                              "audit_sequence": 2}), flush=True)
    finally:
        for client in clients[:2]:
            try:
                client.close()
            except Exception:
                client.process.kill()
                client.process.wait(timeout=5)
        if len(clients) == 3:
            observer = clients[2]
            try:
                status, _ = observer.run(f"DROP SCHEMA IF EXISTS {schema} CASCADE;")
                assert status == "00000", "AUDIT_LOCK_CANARY_CLEANUP_FAILED"
            finally:
                observer.close()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("AUDIT_LOCK_CANARY_CONTAINER_REQUIRED")
    main(sys.argv[1])
