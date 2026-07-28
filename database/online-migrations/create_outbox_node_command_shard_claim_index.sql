-- Online post-deploy operation. Do not place this statement in startup Flyway migrations:
-- Flyway's PostgreSQL advisory-lock transaction can make CREATE INDEX CONCURRENTLY wait on its
-- own migration snapshot. Run with a dedicated psql connection after V041 is applied.
--
-- Safe to retry and safe to omit temporarily: the dispatcher falls back to the existing
-- idx_outbox_events_unpublished partial index.

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_outbox_node_command_shard_claim
  ON outbox_events(coordinator_shard_id, next_attempt_at, created_at)
  WHERE published_at IS NULL
    AND dead_lettered_at IS NULL
    AND event_type = 'node.command.requested';
