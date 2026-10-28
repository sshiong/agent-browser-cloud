"""Owned PostgreSQL 17 migration/ordering gate; synthetic receipts are SQL fixtures only."""
import os
import secrets
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class CloudHoldPostgresTest(unittest.TestCase):
    container = None

    @classmethod
    def setUpClass(cls):
        if os.environ.get('RECORDING_CLOUD_HOLD_POSTGRES_GATE') != 'true':
            raise unittest.SkipTest('dedicated owned PostgreSQL gate only')
        if os.uname().sysname == 'Darwin':
            assert 'Running' in subprocess.check_output(['orbctl', 'status'], text=True)
            assert subprocess.check_output(['docker', 'context', 'show'], text=True).strip() == 'orbstack'
            assert 'OS=OrbStack' in subprocess.check_output(
                ['docker', 'info', '--format', 'OS={{.OperatingSystem}}'], text=True)
        cls.container = 'recording-cloud-hold-' + secrets.token_hex(10)
        with tempfile.TemporaryDirectory() as root:
            env = Path(root) / 'postgres.env'
            env.write_text('POSTGRES_PASSWORD=' + secrets.token_hex(24) + '\n')
            env.chmod(0o600)
            subprocess.run(['docker', 'run', '-d', '--rm', '--network', 'none', '--name', cls.container,
                            '--env-file', str(env), 'postgres:17'], check=True, stdout=subprocess.DEVNULL)
        for _ in range(120):
            if subprocess.run(['docker', 'exec', cls.container, 'pg_isready', '-h', '127.0.0.1', '-U', 'postgres'],
                              stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0:
                return
            time.sleep(.1)
        raise RuntimeError('OWNED_POSTGRES_START_FAILED')

    @classmethod
    def tearDownClass(cls):
        if cls.container:
            subprocess.run(['docker', 'rm', '-fv', cls.container], check=True, stdout=subprocess.DEVNULL)
            cls.container = None

    def sql(self, statement, error=None):
        result = subprocess.run(['docker', 'exec', '-i', self.container, 'psql', '-U', 'postgres',
                                 '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                                input='SET search_path TO ' + self.schema + ';\n' + statement,
                                text=True, capture_output=True)
        if error:
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(error, result.stderr)
            return ''
        self.assertEqual(result.returncode, 0, result.stderr)
        return result.stdout.strip()

    def setUp(self):
        self.schema = 'cloud_hold_' + secrets.token_hex(10)
        subprocess.run(['docker', 'exec', self.container, 'psql', '-U', 'postgres', '-X', '-q',
                        '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE SCHEMA ' + self.schema], check=True)
        def table(migration, name):
            text = (ROOT / 'database/migrations' / migration).read_text()
            start = text.index('CREATE TABLE ' + name + ' (')
            return text[start:text.index('\n);', start) + 3]
        setup = 'CREATE TABLE sessions(id TEXT PRIMARY KEY, tenant_id TEXT, profile_id TEXT);\n'
        setup += table('V019__enterprise_operations.sql', 'enterprise_retention_policies') + '\n'
        setup += table('V102__enterprise_overview_event_stream.sql', 'enterprise_overview_events') + '\n'
        for migration in ['V099__session_recording_manifest_projection.sql',
                          'V128__recording_retention_physical_deletion.sql',
                          'V134__recording_durable_deletion_authority.sql',
                          'V135__recording_cloud_hold_authority.sql']:
            setup += (ROOT / 'database/migrations' / migration).read_text() + '\n'
        self.sql('BEGIN;\n' + setup + '\nCOMMIT;')
        self.sql("INSERT INTO sessions VALUES ('ses_owned','tenant-owned','profile-owned');")
        self.recording('rec_owned')

    def recording(self, recording):
        self.sql(self.recording_sql(recording))

    def recording_sql(self, recording):
        assert recording.replace('_', '').isalnum()
        return """INSERT INTO session_recordings(
            recording_id,event_id,tenant_id,session_id,node_id,segment_count,frame_count,
            dropped_frames,redacted_frame_count,redacted_region_count,redaction_policy_version,
            manifest_object_key,manifest_sha256,manifest_bytes,started_at,ended_at,retention_until)
            VALUES ('%s','event_%s','tenant-owned','ses_owned','node-owned',1,1,0,1,1,1,
            'owned-fixture-manifest','%s',100,now()-interval '3 days',
            now()-interval '2 days',now()-interval '1 day');""" % (recording, recording, 'a'*64)

    def policy(self, wanted, cloud=True):
        self.sql("""INSERT INTO enterprise_retention_policies VALUES
            ('tenant-owned','REMOTE_DESKTOP_RECORDING',1,%s,'primary','owned-test',now(),%s)
            ON CONFLICT(tenant_id,data_class) DO UPDATE SET legal_hold=EXCLUDED.legal_hold,
            cloud_hold_required=EXCLUDED.cloud_hold_required;""" % (str(wanted).lower(), str(cloud).lower()))

    def complete(self, proof):
        assert proof in ('b', 'c', 'd')
        self.sql("""BEGIN;
            UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=attempt+1,lease_epoch=lease_epoch+1,started_at=now()
              WHERE operation_id=(SELECT active_operation_id FROM recording_cloud_hold_authorities WHERE recording_id='rec_owned');
            UPDATE recording_cloud_hold_jobs SET state='COMMITTED',completed_at=now(),proof_hash='%s',
              inventory_sha256='%s',object_version_count=3,failure_code=NULL WHERE state='EXECUTING';
            UPDATE recording_cloud_hold_authorities auth SET applied_mode=job.mode,applied_epoch=job.authority_epoch,
              proof_hash=job.proof_hash,inventory_sha256=job.inventory_sha256,object_version_count=job.object_version_count,
              completed_at=job.completed_at,active_operation_id=NULL,
              status=CASE WHEN auth.desired_epoch=job.authority_epoch THEN 'VERIFIED_'||job.mode ELSE 'PENDING' END
              FROM recording_cloud_hold_jobs job WHERE auth.active_operation_id=job.operation_id AND job.state='COMMITTED';
            SELECT recording_cloud_hold_advance('rec_owned'); COMMIT;""" % (proof*64, 'e'*64))

    def test_legacy_default_does_not_invent_cloud_protection(self):
        self.policy(True, False)
        self.assertEqual(self.sql('SELECT count(*) FROM recording_cloud_hold_jobs'), '0')
        self.assertEqual(self.sql('SELECT count(*) FROM recording_cloud_hold_authorities'), '0')
        self.policy(True)
        op = self.sql('SELECT operation_id FROM recording_cloud_hold_jobs')
        self.policy(True)
        self.assertEqual(self.sql('SELECT operation_id FROM recording_cloud_hold_jobs'), op)
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')
        self.assertEqual(self.sql('SELECT pending_count FROM recording_cloud_hold_rollups'), '1')

    def test_unknown_original_request_cannot_be_replaced_or_release_deletion(self):
        self.policy(True)
        original = self.sql('SELECT operation_id,authority_epoch,mode FROM recording_cloud_hold_jobs')
        self.sql("BEGIN; UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=1,lease_epoch=1,started_at=now(); "
                 "UPDATE recording_cloud_hold_jobs SET state='UNKNOWN'; "
                 "UPDATE recording_cloud_hold_authorities SET status='UNKNOWN'; COMMIT;")
        self.policy(False)
        self.assertEqual(self.sql('SELECT operation_id,authority_epoch,mode FROM recording_cloud_hold_jobs'), original)
        self.assertEqual(self.sql('SELECT desired_epoch,status FROM recording_cloud_hold_authorities'), '2|UNKNOWN')
        self.sql('UPDATE session_recordings SET legal_hold=false')
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')
        self.sql('UPDATE session_recordings SET deletion_authorized_at=now()', 'RECORDING_CLOUD_HOLD_NOT_RELEASED')
        self.sql("UPDATE recording_cloud_hold_jobs SET execution_budget_millis=1000", 'RECORDING_HOLD_REQUEST_IMMUTABLE')
        self.sql('DELETE FROM recording_cloud_hold_jobs', 'RECORDING_HOLD_JOB_IMMUTABLE')
        self.sql('UPDATE recording_cloud_hold_authorities SET active_operation_id=NULL', 'RECORDING_HOLD_ACTIVE_RESULT_UNKNOWN')
        self.sql("UPDATE recording_cloud_hold_authorities SET status='DELETED'", 'RECORDING_HOLD_DELETION_PROOF_REQUIRED')
        self.sql("UPDATE recording_cloud_hold_authorities SET applied_epoch=1,applied_mode='ON',proof_hash='"+'b'*64+
                 "',inventory_sha256='"+'c'*64+"',object_version_count=3,completed_at=now()",
                 'RECORDING_HOLD_COMMITTED_PROOF_REQUIRED')

        self.sql('UPDATE enterprise_retention_policies SET cloud_hold_required=false', 'RECORDING_CLOUD_HOLD_NOT_RELEASED')
        self.assertEqual(self.sql('SELECT unknown_count,pending_count FROM recording_cloud_hold_rollups'), '1|0')

    def test_off_requires_prior_proof_and_verified_current_epoch(self):
        self.policy(True)
        self.complete('b')
        self.assertEqual(self.sql('SELECT status FROM recording_cloud_hold_authorities'), 'VERIFIED_ON')
        self.policy(False)
        self.sql('UPDATE session_recordings SET legal_hold=false')
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')
        self.assertEqual(self.sql("SELECT mode,previous_proof_hash FROM recording_cloud_hold_jobs WHERE state='QUEUED'"), 'OFF|'+'b'*64)
        self.complete('c')
        self.assertEqual(self.sql('SELECT status FROM recording_cloud_hold_authorities'), 'VERIFIED_OFF')
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 'f')
        self.sql('UPDATE session_recordings SET deletion_authorized_at=now()')
        self.sql('UPDATE enterprise_retention_policies SET legal_hold=true', 'RECORDING_DELETION_ALREADY_AUTHORIZED')
        self.sql("UPDATE session_recordings SET deleted_at=now(),deletion_receipt_hash='"+'f'*64+
                 "',deletion_proof_hash='"+'d'*64+"',deleted_object_count=3")
        self.assertEqual(self.sql('SELECT status FROM recording_cloud_hold_authorities'), 'DELETED')
        self.assertEqual(self.sql('SELECT verified_off_count FROM recording_cloud_hold_rollups'), '0')


    def test_late_ingest_and_other_policy_classes_remain_scoped(self):
        self.policy(True)
        self.recording('rec_late')
        self.assertEqual(self.sql('SELECT count(*) FROM recording_cloud_hold_jobs'), '2')
        self.assertEqual(self.sql('SELECT pending_count FROM recording_cloud_hold_rollups'), '2')
        self.sql("INSERT INTO enterprise_retention_policies(tenant_id,data_class,retention_days,legal_hold,"
                 "residency_region,updated_by,updated_at) VALUES('tenant-owned','AUDIT',30,false,'primary','owned',now())")
        self.sql("UPDATE enterprise_retention_policies SET retention_days=40 WHERE data_class='AUDIT'")
        self.sql("DELETE FROM enterprise_retention_policies WHERE data_class='AUDIT'")
        self.assertEqual(self.sql("SELECT count(*) FROM enterprise_overview_events WHERE tenant_id='tenant-owned' AND change_type='RETENTION'"), '2')

    def test_policy_waits_for_inflight_ingest_and_projects_its_final_record(self):
        self.policy(True)
        name = 'owned_hold_ingest_' + secrets.token_hex(5)
        ingest = subprocess.Popen(['docker', 'exec', '-i', self.container, 'psql', '-U', 'postgres',
                                   '-X', '-qAt', '-v', 'ON_ERROR_STOP=1'],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        ingest.stdin.write('SET search_path TO ' + self.schema + "; SET application_name='" + name + "'; BEGIN;"
                           + self.recording_sql('rec_race') + 'SELECT pg_sleep(2); COMMIT;')
        ingest.stdin.close()
        try:
            sleeping = False
            for _ in range(30):
                if self.sql("SELECT count(*) FROM pg_stat_activity WHERE application_name='" + name
                            + "' AND wait_event='PgSleep'") == '1':
                    sleeping = True
                    break
                time.sleep(.02)
            self.assertTrue(sleeping, 'owned ingestion transaction did not reach its synchronization point')
            self.policy(False)  # Exclusive policy lock must wait for the shared ingestion lock.
            self.assertEqual(ingest.wait(timeout=10), 0, ingest.stderr.read())
        finally:
            if ingest.poll() is None:
                ingest.kill()
                ingest.wait(timeout=10)
            ingest.stdout.close()
            ingest.stderr.close()
        self.assertEqual(self.sql("SELECT count(*) FROM recording_cloud_hold_authorities "
                                  "WHERE desired_mode='OFF' AND desired_epoch=2"), '2')
        self.assertEqual(self.sql('SELECT count(*) FROM recording_cloud_hold_jobs'), '2')
        self.assertEqual(self.sql('SELECT count(*) FROM session_recordings WHERE legal_hold'), '2')

    def test_old_upsert_can_change_desired_policy_but_cannot_downgrade_cloud_hold(self):
        self.policy(True)
        self.sql("INSERT INTO enterprise_retention_policies(tenant_id,data_class,retention_days,legal_hold,"
                 "residency_region,updated_by,updated_at) VALUES('tenant-owned','REMOTE_DESKTOP_RECORDING',"
                 "1,false,'primary','old-owned',now()) ON CONFLICT(tenant_id,data_class) DO UPDATE "
                 "SET legal_hold=EXCLUDED.legal_hold")
        self.assertEqual(self.sql('SELECT cloud_hold_required,legal_hold FROM enterprise_retention_policies'), 't|f')
        self.assertEqual(self.sql('SELECT desired_mode,desired_epoch FROM recording_cloud_hold_authorities'), 'OFF|2')
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')
        self.sql("UPDATE enterprise_retention_policies SET data_class='AUDIT',cloud_hold_required=false",
                 'RECORDING_CLOUD_HOLD_POLICY_IDENTITY_IMMUTABLE')

    def test_unknown_off_is_not_superseded_by_a_new_on(self):
        self.policy(True)
        self.complete('b')
        self.policy(False)
        off = self.sql("SELECT operation_id,authority_epoch,mode FROM recording_cloud_hold_jobs WHERE state='QUEUED'")
        self.sql("BEGIN; UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=1,lease_epoch=1,started_at=now() WHERE state='QUEUED'; "
                 "UPDATE recording_cloud_hold_jobs SET state='UNKNOWN' WHERE state='EXECUTING'; "
                 "UPDATE recording_cloud_hold_authorities SET status='UNKNOWN'; COMMIT;")
        self.policy(True)
        self.assertEqual(self.sql("SELECT operation_id,authority_epoch,mode FROM recording_cloud_hold_jobs WHERE state='UNKNOWN'"), off)
        self.assertEqual(self.sql('SELECT desired_epoch,status FROM recording_cloud_hold_authorities'), '3|UNKNOWN')
        self.complete('c')  # Fixture models reconciliation of the exact old OFF receipt.
        self.assertEqual(self.sql("SELECT authority_epoch,mode,previous_proof_hash FROM recording_cloud_hold_jobs WHERE state='QUEUED'"), '3|ON|'+'c'*64)
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')
        self.assertEqual(self.sql('SELECT status FROM recording_cloud_hold_authorities'), 'PENDING')
        self.complete('d')
        self.assertEqual(self.sql('SELECT status FROM recording_cloud_hold_authorities'), 'VERIFIED_ON')

    def test_failed_proof_cannot_commit_or_erase_a_request(self):
        self.policy(True)
        self.sql("UPDATE recording_cloud_hold_jobs SET state='COMMITTED'", 'RECORDING_HOLD_REQUEST_IMMUTABLE')
        self.sql("UPDATE recording_cloud_hold_jobs SET state='EXECUTING',attempt=1,lease_epoch=1,started_at=now()")
        self.sql("UPDATE recording_cloud_hold_jobs SET state='COMMITTED',completed_at=now()", 'violates check constraint')
        self.assertEqual(self.sql('SELECT legal_hold FROM session_recordings'), 't')


if __name__ == '__main__':
    try:
        program = unittest.main(exit=False)
        valid = program.result.wasSuccessful()
        if os.environ.get('RECORDING_CLOUD_HOLD_POSTGRES_GATE') == 'true':
            valid = valid and program.result.testsRun >= 8 and not program.result.skipped
        raise SystemExit(0 if valid else 1)
    finally:
        if CloudHoldPostgresTest.container:
            CloudHoldPostgresTest.tearDownClass()
