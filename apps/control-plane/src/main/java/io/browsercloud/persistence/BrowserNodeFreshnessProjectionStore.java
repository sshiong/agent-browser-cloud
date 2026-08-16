package io.browsercloud.persistence;

import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomically deduplicates Browser Node heartbeat transitions and publishes SSE invalidations. */
@Repository
public class BrowserNodeFreshnessProjectionStore {
  private final JdbcTemplate jdbc;

  public BrowserNodeFreshnessProjectionStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public int projectTransitions(Instant freshAfter, Instant observedAt) {
    var projected =
        jdbc.queryForObject(
            """
            WITH observed AS (
              SELECT
                node_id,
                CASE WHEN last_heartbeat_at >= ? THEN 'FRESH' ELSE 'STALE' END AS freshness_state
              FROM browser_nodes
            ), transitions AS (
              INSERT INTO browser_node_freshness_projections(
                node_id, freshness_state, observed_at, transitioned_at
              )
              SELECT node_id, freshness_state, ?, ?
              FROM observed
              ON CONFLICT (node_id) DO UPDATE
              SET freshness_state = EXCLUDED.freshness_state,
                  observed_at = EXCLUDED.observed_at,
                  transitioned_at = EXCLUDED.transitioned_at
              WHERE browser_node_freshness_projections.freshness_state
                    IS DISTINCT FROM EXCLUDED.freshness_state
              RETURNING node_id, transitioned_at
            ), events AS (
              INSERT INTO workspace_overview_events(
                tenant_id, change_type, entity_id, occurred_at
              )
              SELECT NULL, 'BROWSER_NODE', node_id, transitioned_at
              FROM transitions
              RETURNING stream_sequence
            )
            SELECT count(*) FROM events
            """,
            Integer.class,
            Timestamp.from(freshAfter),
            Timestamp.from(observedAt),
            Timestamp.from(observedAt));
    return projected == null ? 0 : projected;
  }
}
