-- Support bounded terminal participant history retention without scanning online leases.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_remote_desktop_participants_terminal_retention
    ON remote_desktop_participants(updated_at, connection_id)
    WHERE state IN ('REVOKED', 'DISCONNECTED');

COMMENT ON INDEX idx_remote_desktop_participants_terminal_retention IS
  'Bounded retention cleanup for terminal VNC participants; online and revoke-requested leases excluded';
