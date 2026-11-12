#!/usr/bin/env python3
"""Owned PostgreSQL test for optional MCP connection quota foundation, no public API claim."""
import concurrent.futures
import os
from pathlib import Path
import secrets
import re
import subprocess
import tempfile
import time


def run(args, **kw):
    return subprocess.run(args, capture_output=True, text=True, timeout=60, **kw)


if os.uname().sysname == 'Darwin':
    assert 'Running' in run(['orbctl', 'status']).stdout
    assert run(['docker', 'context', 'show']).stdout.strip() == 'orbstack'
    info = run(['docker', 'info', '--format', 'Name={{.Name}} OS={{.OperatingSystem}} Server={{.ServerVersion}}'])
    assert info.returncode == 0 and 'OS=OrbStack' in info.stdout
    print(info.stdout.strip())
name = 'agentbrowser-mcp-quota-' + secrets.token_hex(10)
with tempfile.TemporaryDirectory(prefix='agentbrowser-mcp-quota-') as temp:
    env = Path(temp) / 'postgres.env'
    env.write_text('POSTGRES_PASSWORD=' + secrets.token_hex(24) + '\n')
    env.chmod(0o600)
    # Keep the temporary Unix-only server alive long enough to exercise startup fencing.
    init = Path(temp) / 'startup.sql'
    init.write_text('SELECT pg_sleep(3);\n')
    init.chmod(0o644)
    def sql(source, success=True):
        r = run(['docker', 'exec', '-i', name, 'psql', '-U', 'postgres', '-At', '-v', 'ON_ERROR_STOP=1', '-v', 'VERBOSITY=sqlstate'], input=source)
        code = re.search(r'ERROR:\s+([0-9A-Z]{5})\b', r.stderr)
        assert (r.returncode == 0) == success, 'MCP_QUOTA_SQL_EXPECTATION_FAILED sqlstate=' + (code[1] if code else 'unavailable')
        return r.stdout.strip()
    def insert(identity, key, running=False):
        return f"INSERT INTO agent_connection_environments(reservation_id,connection_id,tenant_id,request_key,request_sha256,state,running_reserved) VALUES('{identity}','conn','tenant','{key}','" + 'a'*64 + f"','CREATING',{str(running).lower()});"
    try:
        assert run(['docker', 'run', '-d', '--name', name, '--env-file', str(env),
            '--mount', f'type=bind,source={init},target=/docker-entrypoint-initdb.d/startup.sql,readonly', 'postgres:17']).returncode == 0
        # The image's temporary initdb server accepts Unix socket connections, then exits.
        # Only the final server listens on TCP; migrations must wait for that server.
        for _ in range(120):
            if run(['docker', 'exec', name, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres']).returncode == 0:
                break
            time.sleep(.25)
        else:
            raise AssertionError('MCP_QUOTA_POSTGRES_TCP_NOT_READY')
        schema = Path('database/migrations/V139__agent_connection_quota_authority.sql').read_text() + Path('database/migrations/V140__agent_connection_access_and_lifecycle.sql').read_text()
        sql("CREATE TABLE sessions(id TEXT PRIMARY KEY,tenant_id TEXT DEFAULT 'tenant',state TEXT DEFAULT 'CREATED',deleted_at TIMESTAMPTZ); BEGIN;" + schema + "COMMIT;")
        sql("INSERT INTO agent_connections(connection_id,tenant_id,display_name,principal_subject,enabled,maximum_environments,maximum_running,expires_at) VALUES('conn','tenant','Test','subject',true,1,1,now()+interval '1 day');")
        # Two separate real transactions race for the final environment slot.
        def contender(i):
            r = run(['docker','exec','-i',name,'psql','-U','postgres','-At','-v','ON_ERROR_STOP=1'], input='BEGIN;' + insert('res'+str(i),'key'+str(i),True) + 'SELECT pg_sleep(0.2); COMMIT;')
            return r.returncode
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            codes = list(executor.map(contender, [1,2]))
        assert sum(c == 0 for c in codes) == 1
        assert sql('SELECT count(*) FROM agent_connection_environments') == '1'
        # Revocation and lowered quotas must not prevent release of an existing reservation.
        sql("UPDATE agent_connections SET enabled=false,maximum_running=0,maximum_environments=0;")
        sql("UPDATE agent_connection_environments SET state='STOPPED',running_reserved=false;")
        sql("UPDATE agent_connection_environments SET state='DELETED';")
        sql("UPDATE agent_connection_environments SET state='CREATED';", False)
        sql(insert('new','new'), False)
        sql("UPDATE agent_connections SET enabled=true,maximum_environments=3,maximum_running=1;")
        sql(insert('active','active',True))
        sql(insert('stopped','stopped'))
        sql("UPDATE agent_connection_environments SET running_reserved=true WHERE reservation_id='stopped';", False)
        sql("UPDATE agent_connection_environments SET tenant_id='other' WHERE reservation_id='active';", False)
        sql("UPDATE agent_connection_environments SET state='STOPPED',running_reserved=false WHERE reservation_id='active';")
        sql(insert('stopped2','stopped2'))
        def starter(identity):
            r = run(['docker','exec','-i',name,'psql','-U','postgres','-At','-v','ON_ERROR_STOP=1'], input=f"BEGIN; UPDATE agent_connection_environments SET state='STARTING',running_reserved=true WHERE reservation_id='{identity}'; SELECT pg_sleep(0.2); COMMIT;")
            return r.returncode
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            codes = list(executor.map(starter, ['stopped','stopped2']))
        assert sum(c == 0 for c in codes) == 1
        assert sql("SELECT count(*) FROM agent_connection_environments WHERE running_reserved") == '1'
        print('agent_connection_quota_concurrent_starts=true contenders=2 winners=1')
        sql("UPDATE agent_connections SET expires_at=now()-interval '1 second';")
        sql(insert('expired','expired'), False)
        sql("TRUNCATE agent_connection_environments; UPDATE agent_connections SET enabled=true,expires_at=now()+interval '1 day',maximum_environments=2,maximum_running=1;")
        sql("INSERT INTO sessions(id) VALUES('ses_one'),('ses_two');")
        sql(insert('one','one') + insert('two','two'))
        sql("UPDATE agent_connection_environments SET session_id='ses_one' WHERE reservation_id='one'; UPDATE agent_connection_environments SET session_id='ses_two' WHERE reservation_id='two';")
        sql("UPDATE sessions SET state='STARTING' WHERE id='ses_one';")
        sql("UPDATE sessions SET state='STARTING' WHERE id='ses_two';", False)
        assert sql("SELECT state FROM sessions WHERE id='ses_two'") == 'CREATED'
        sql("UPDATE sessions SET state='RUNNING' WHERE id='ses_one'; UPDATE sessions SET state='HIBERNATING' WHERE id='ses_one';")
        sql("UPDATE sessions SET state='STARTING' WHERE id='ses_two';", False)
        sql("UPDATE sessions SET state='HIBERNATED' WHERE id='ses_one'; UPDATE sessions SET state='STARTING' WHERE id='ses_two'; UPDATE sessions SET state='FAILED' WHERE id='ses_two';")
        assert sql("SELECT running_reserved FROM agent_connection_environments WHERE reservation_id='two'") == 't'
        sql("UPDATE sessions SET state='TERMINATED' WHERE id='ses_two'; UPDATE sessions SET deleted_at=now() WHERE id='ses_one';")
        assert sql("SELECT state FROM agent_connection_environments WHERE reservation_id='one'") == 'DELETED'
        assert sql("SELECT count(*) FROM agent_connection_environments WHERE running_reserved") == '0'
        print('agent_connection_canonical_session_quota_and_release=true')
        print('agent_connection_quota_postgres=true concurrent_creates=2 winners=1')
        print('agent_connection_quota_revocation_release_and_identity=true')
    finally:
        cleaned = run(['docker','rm','-fv',name])
        assert cleaned.returncode == 0, 'MCP_QUOTA_CONTAINER_CLEANUP_FAILED'
        print('agent_connection_quota_owned_container_removed=true')
