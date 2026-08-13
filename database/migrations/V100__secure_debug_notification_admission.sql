-- Admit Secure Debug privileged-access events into the workspace notification feed.
--
-- V064 already classifies every '%SECURE_DEBUG_%' event as the SECURITY category, and it admits
-- the sibling privileged-access families BREAK_GLASS_% and KEY_ROTATION_% by whole prefix. The
-- admission gate however never listed SECURE_DEBUG_%, so the two highest-signal events of that
-- family — SECURE_DEBUG_STARTED (ACTIVE) and SECURE_DEBUG_SNAPSHOT_ACCESSED (MINIMIZED) — carry
-- no high-signal suffix and were silently dropped: operators were notified when secure debug
-- access was denied, but never when it actually started or read a snapshot.
--
-- This migration only widens the admission gate. Classification and severity are unchanged, so
-- Secure Debug keeps the same INFO/WARNING convention already used by Break-glass. The feed is a
-- forward-only projection, so historical audit rows are deliberately not backfilled.

CREATE OR REPLACE FUNCTION append_workspace_notification()
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
        OR NEW.event_type LIKE 'SECURE_DEBUG_%'
        OR NEW.action LIKE 'SECURE_DEBUG_%'
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
