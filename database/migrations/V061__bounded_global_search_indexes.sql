CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_sessions_global_search_trgm
    ON sessions
    USING GIN (
        (
            lower(
                id || ' ' ||
                profile_id || ' ' ||
                region || ' ' ||
                resource_class || ' ' ||
                state || ' ' ||
                coalesce(metadata->>'displayName', '')
            )
        ) gin_trgm_ops
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_profiles_global_search_trgm
    ON profiles
    USING GIN (
        (
            lower(
                profile_id || ' ' ||
                name || ' ' ||
                state || ' ' ||
                coalesce(description, '')
            )
        ) gin_trgm_ops
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workspace_groups_global_search_trgm
    ON workspace_groups
    USING GIN (
        (
            lower(
                group_id || ' ' ||
                name || ' ' ||
                coalesce(description, '')
            )
        ) gin_trgm_ops
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_workspace_tags_global_search_trgm
    ON workspace_tags
    USING GIN (
        (
            lower(
                tag_id || ' ' ||
                name || ' ' ||
                coalesce(description, '')
            )
        ) gin_trgm_ops
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_runtime_builds_global_search_trgm
    ON runtime_builds
    USING GIN (
        (
            lower(
                build_id || ' ' ||
                engine || ' ' ||
                version || ' ' ||
                platform || ' ' ||
                release_channel
            )
        ) gin_trgm_ops
    );

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_browser_nodes_global_search_trgm
    ON browser_nodes
    USING GIN (
        (
            lower(
                node_id || ' ' ||
                region || ' ' ||
                lifecycle_state || ' ' ||
                admission_state || ' ' ||
                pressure_state
            )
        ) gin_trgm_ops
    );

COMMENT ON INDEX idx_sessions_global_search_trgm IS
    'Bounded workspace search. Only governed Session identity fields are indexed; arbitrary metadata and browser content are excluded.';
