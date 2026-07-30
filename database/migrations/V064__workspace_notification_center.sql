-- Durable workspace notifications derived atomically from high-signal audit events.
--
-- This migration is deliberately additive and does not backfill the historical audit ledger.
-- New notifications begin at deployment time, avoiding an unbounded scan/write of audit_events.
-- Existing application versions remain compatible because the trigger is self-contained.

CREATE TABLE workspace_notifications (
    notification_id   TEXT PRIMARY KEY,
    tenant_id         TEXT NOT NULL,
    audit_sequence_no BIGINT NOT NULL,
    session_id        TEXT,
    event_type        TEXT NOT NULL,
    category          TEXT NOT NULL,
    severity          TEXT NOT NULL,
    resource_type     TEXT,
    resource_id       TEXT,
    action            TEXT NOT NULL,
    result            TEXT NOT NULL,
    request_id        TEXT,
    created_at        TIMESTAMPTZ NOT NULL,
    expires_at        TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_workspace_notifications_tenant_sequence
      UNIQUE (tenant_id, audit_sequence_no)
);

ALTER TABLE workspace_notifications
  ADD CONSTRAINT chk_workspace_notification_sequence
  CHECK (audit_sequence_no > 0) NOT VALID,
  ADD CONSTRAINT chk_workspace_notification_category
  CHECK (category IN ('SECURITY', 'RESOURCE', 'AGENT', 'RELEASE', 'SYSTEM')) NOT VALID,
  ADD CONSTRAINT chk_workspace_notification_severity
  CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')) NOT VALID,
  ADD CONSTRAINT chk_workspace_notification_expiry
  CHECK (expires_at > created_at) NOT VALID;

ALTER TABLE workspace_notifications
  VALIDATE CONSTRAINT chk_workspace_notification_sequence;
ALTER TABLE workspace_notifications
  VALIDATE CONSTRAINT chk_workspace_notification_category;
ALTER TABLE workspace_notifications
  VALIDATE CONSTRAINT chk_workspace_notification_severity;
ALTER TABLE workspace_notifications
  VALIDATE CONSTRAINT chk_workspace_notification_expiry;

CREATE INDEX idx_workspace_notifications_feed
  ON workspace_notifications (tenant_id, audit_sequence_no DESC);

CREATE INDEX idx_workspace_notifications_expiry
  ON workspace_notifications (expires_at);

CREATE TABLE workspace_notification_read_cursors (
    tenant_id          TEXT NOT NULL,
    actor_id           TEXT NOT NULL,
    last_read_sequence BIGINT NOT NULL DEFAULT 0,
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (tenant_id, actor_id)
);

ALTER TABLE workspace_notification_read_cursors
  ADD CONSTRAINT chk_workspace_notification_read_sequence
  CHECK (last_read_sequence >= 0) NOT VALID;

ALTER TABLE workspace_notification_read_cursors
  VALIDATE CONSTRAINT chk_workspace_notification_read_sequence;

CREATE FUNCTION append_workspace_notification()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    notification_category TEXT;
    notification_severity TEXT;
BEGIN
    IF NOT (
        upper(NEW.result) IN (
          'FAILED', 'REJECTED', 'DENIED', 'ABORTED', 'EXPIRED', 'REVOKED', 'CRITICAL'
        )
        OR NEW.event_type ~
          '(_FAILED|_FAILURE|_REJECTED|_DENIED|_ABORTED|_EXPIRED|_REVOKED|_REQUESTED|_APPROVED|_COMPLETED)$'
        OR NEW.action ~
          '(_FAILED|_FAILURE|_REJECTED|_DENIED|_ABORTED|_EXPIRED|_REVOKED|_REQUESTED|_APPROVED|_COMPLETED)$'
        OR NEW.event_type LIKE 'BREAK_GLASS_%'
        OR NEW.action LIKE 'BREAK_GLASS_%'
        OR NEW.event_type LIKE 'KEY_ROTATION_%'
        OR NEW.action LIKE 'KEY_ROTATION_%'
        OR NEW.event_type LIKE 'RUNTIME_RELEASE_%'
        OR NEW.action LIKE 'RUNTIME_RELEASE_%'
        OR NEW.event_type LIKE 'RECOVERY_CONTRACT_APPROVAL_%'
        OR NEW.action LIKE 'RECOVERY_CONTRACT_APPROVAL_%'
        OR NEW.event_type LIKE 'EVIDENCE_ACCESS_%'
        OR NEW.action LIKE 'EVIDENCE_ACCESS_%'
        OR NEW.event_type LIKE 'MAXIMUM_%'
        OR NEW.action LIKE 'MAXIMUM_%'
        OR NEW.event_type LIKE 'MIGRATION_%'
        OR NEW.action LIKE 'MIGRATION_%'
        OR NEW.event_type LIKE 'HUMAN_CONFIRMATION_%'
        OR NEW.action LIKE 'HUMAN_CONFIRMATION_%'
        OR NEW.event_type LIKE 'HUMAN_HANDOFF_%'
        OR NEW.action LIKE 'HUMAN_HANDOFF_%'
        OR NEW.event_type IN ('SECURITY_EVENT', 'PAUSED_BY_RESOURCE_POLICY')
        OR NEW.action IN ('SECURITY_EVENT', 'PAUSED_BY_RESOURCE_POLICY')
    ) THEN
        RETURN NEW;
    END IF;

    notification_category :=
      CASE
        WHEN (NEW.event_type || '_' || NEW.action) LIKE '%BREAK_GLASS_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%SECURE_DEBUG_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%KEY_ROTATION_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%EVIDENCE_ACCESS_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%SECURITY%'
          THEN 'SECURITY'
        WHEN (NEW.event_type || '_' || NEW.action) LIKE '%RESOURCE%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%MIGRATION%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%HIBERNATE%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%MAXIMUM_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%NODE_%'
          THEN 'RESOURCE'
        WHEN (NEW.event_type || '_' || NEW.action) LIKE '%AGENT_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%HUMAN_CONFIRMATION_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%HUMAN_HANDOFF_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%TOOL_%'
          THEN 'AGENT'
        WHEN (NEW.event_type || '_' || NEW.action) LIKE '%RUNTIME_RELEASE_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%RECOVERY_GAMEDAY_%'
          OR (NEW.event_type || '_' || NEW.action) LIKE '%AUDIT_EXPORT_%'
          THEN 'RELEASE'
        ELSE 'SYSTEM'
      END;

    notification_severity :=
      CASE
        WHEN upper(NEW.result) IN ('FAILED', 'CRITICAL')
          OR NEW.event_type LIKE '%_FAILED'
          OR NEW.action LIKE '%_FAILED'
          OR NEW.event_type LIKE '%_FAILURE'
          OR NEW.action LIKE '%_FAILURE'
          OR NEW.event_type LIKE '%CRITICAL%'
          OR NEW.action LIKE '%CRITICAL%'
          THEN 'CRITICAL'
        WHEN upper(NEW.result) IN ('REJECTED', 'DENIED', 'ABORTED', 'EXPIRED', 'REVOKED')
          OR NEW.event_type LIKE 'MAXIMUM_%'
          OR NEW.action LIKE 'MAXIMUM_%'
          OR NEW.event_type LIKE '%_REQUESTED'
          OR NEW.action LIKE '%_REQUESTED'
          OR NEW.event_type IN ('SECURITY_EVENT', 'PAUSED_BY_RESOURCE_POLICY')
          OR NEW.action IN ('SECURITY_EVENT', 'PAUSED_BY_RESOURCE_POLICY')
          THEN 'WARNING'
        ELSE 'INFO'
      END;

    INSERT INTO workspace_notifications (
        notification_id,
        tenant_id,
        audit_sequence_no,
        session_id,
        event_type,
        category,
        severity,
        resource_type,
        resource_id,
        action,
        result,
        request_id,
        created_at,
        expires_at
    )
    VALUES (
        'ntf_' || substring(NEW.event_id FROM 5),
        NEW.tenant_id,
        NEW.sequence_no,
        NEW.session_id,
        NEW.event_type,
        notification_category,
        notification_severity,
        NEW.resource_type,
        NEW.resource_id,
        NEW.action,
        NEW.result,
        NEW.request_id,
        NEW.created_at,
        NEW.created_at + INTERVAL '90 days'
    );
    RETURN NEW;
END;
$$;

CREATE TRIGGER append_workspace_notification
AFTER INSERT ON audit_events
FOR EACH ROW
EXECUTE FUNCTION append_workspace_notification();

COMMENT ON TABLE workspace_notifications IS
  'Tenant-scoped 90-day high-signal notification projection derived from immutable audit events';
COMMENT ON TABLE workspace_notification_read_cursors IS
  'Per-actor monotonic read cursor for the shared Web and Tauri notification center';
