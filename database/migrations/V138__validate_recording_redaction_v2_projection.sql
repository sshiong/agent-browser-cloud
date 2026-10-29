-- V137 has committed its short ACCESS EXCLUSIVE DDL before this scan begins.
-- SHARE UPDATE EXCLUSIVE validation permits ordinary Recording INSERT/UPDATE traffic.
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '5min';
ALTER TABLE session_recordings VALIDATE CONSTRAINT chk_session_recording_manifest;
