#!/usr/bin/env python3
"""Exercise the actual Java heartbeat SQL against an owned PostgreSQL instance."""
import concurrent.futures
from pathlib import Path
import re
import secrets
import subprocess
import time


def run(args, **kwargs):
    return subprocess.run(args, capture_output=True, text=True, timeout=60, **kwargs)


if __import__('platform').system() == 'Darwin':
    assert 'Running' in run(['orbctl', 'status']).stdout
    assert run(['docker', 'context', 'show']).stdout.strip() == 'orbstack'
    assert 'OS=OrbStack' in run(['docker', 'info', '--format', 'OS={{.OperatingSystem}}']).stdout
name = 'agentbrowser-heartbeat-' + secrets.token_hex(8)
source = Path('apps/control-plane/src/main/java/io/browsercloud/infrastructure/NodeCommandDispatchClaimService.java').read_text()
query = re.search(r'INSERT INTO coordinator_dispatch_workers\(.*?"""', source, re.S)[0][:-3].strip()


def heartbeat(ms, worker='worker'):
    stamp = f"TIMESTAMPTZ '2026-11-12 00:00:00Z' + INTERVAL '{ms} milliseconds'"
    values = {'workerId': "'" + worker + "'", 'now': stamp,
              'leaseUntil': '(' + stamp + "+ INTERVAL '3 seconds')",
              'renewAfter': '(' + stamp + "- INTERVAL '1 second')",
              'minimumLeaseUntil': '(' + stamp + "+ INTERVAL '2 seconds')"}
    return re.sub(r':([A-Za-z]+)', lambda m: values[m[1]], query) + ';'


def sql(text):
    result = run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1'], input=text)
    assert result.returncode == 0, result.stderr
    return result.stdout.strip()


try:
    assert run(['docker', 'run', '-d', '--name', name, '-e', 'POSTGRES_HOST_AUTH_METHOD=trust', 'postgres:17']).returncode == 0
    for _ in range(120):
        if run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']).returncode == 0:
            break
        time.sleep(.25)
    else:
        raise AssertionError('POSTGRES_NOT_READY')
    sql('''CREATE TABLE coordinator_dispatch_workers(worker_id TEXT PRIMARY KEY,started_at TIMESTAMPTZ,heartbeat_at TIMESTAMPTZ,lease_until TIMESTAMPTZ);
    CREATE TABLE writes(n INT);
    CREATE FUNCTION count_write() RETURNS TRIGGER LANGUAGE plpgsql AS $$ BEGIN INSERT INTO writes VALUES(1); RETURN NEW; END $$;
    CREATE TRIGGER count_write AFTER INSERT OR UPDATE ON coordinator_dispatch_workers FOR EACH ROW EXECUTE FUNCTION count_write();''')
    sql(heartbeat(0))
    sql(''.join(heartbeat(i) for i in range(1, 1000)))
    assert sql('SELECT count(*) FROM writes') == '1', 'fresh scans must not rewrite the lease'
    sql(heartbeat(1000))
    assert sql('SELECT count(*) FROM writes') == '2'
    # A delayed/older caller must not shorten an existing lease.
    sql(heartbeat(500))
    assert sql('SELECT count(*) FROM writes') == '2'
    # An expired lease recovers, and concurrent lanes renew it once.
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        list(pool.map(lambda _: sql(heartbeat(5000)), range(8)))
    assert sql('SELECT count(*) FROM writes') == '3'
    assert sql("SELECT lease_until = TIMESTAMPTZ '2026-11-12 00:00:08Z' FROM coordinator_dispatch_workers") == 't'
    # No process-local cache: unregister and re-register immediately works.
    sql("DELETE FROM coordinator_dispatch_workers WHERE worker_id='worker';" + heartbeat(5001))
    assert sql('SELECT count(*) FROM writes') == '4'
    print('dispatch_heartbeat_postgres=true fresh_scans=999 writes=0 concurrent_lanes=8 renewals=1 expired_and_unregister_recovery=true')
finally:
    removed = run(['docker', 'rm', '-f', '-v', name])
    assert removed.returncode == 0, 'owned PostgreSQL cleanup failed'
