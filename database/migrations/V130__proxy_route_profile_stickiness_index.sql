CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_proxy_route_profile_stickiness
  ON sessions(tenant_id, profile_id, created_at DESC)
  WHERE deleted_at IS NULL;
