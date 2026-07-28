-- Tenant-owned Workspace Tags and authoritative Session assignments.
--
-- Session IDs and Tag IDs are globally unique today, but the composite unique
-- indexes and foreign keys below keep tenant isolation enforced by PostgreSQL
-- instead of relying only on application checks.

CREATE UNIQUE INDEX uq_sessions_id_tenant
  ON sessions (id, tenant_id);

CREATE TABLE workspace_tags (
  tag_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  name TEXT NOT NULL,
  description TEXT,
  color TEXT NOT NULL DEFAULT '#718096',
  created_by TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT chk_workspace_tags_id
    CHECK (tag_id ~ '^tag_[a-zA-Z0-9]{16,32}$'),
  CONSTRAINT chk_workspace_tags_name
    CHECK (char_length(btrim(name)) BETWEEN 1 AND 32),
  CONSTRAINT chk_workspace_tags_description
    CHECK (description IS NULL OR char_length(description) <= 256),
  CONSTRAINT chk_workspace_tags_color
    CHECK (color ~ '^#[0-9A-Fa-f]{6}$'),
  UNIQUE (tag_id, tenant_id)
);

CREATE UNIQUE INDEX uq_workspace_tags_tenant_name
  ON workspace_tags (tenant_id, lower(btrim(name)));

CREATE INDEX idx_workspace_tags_tenant_updated
  ON workspace_tags (tenant_id, updated_at DESC);

CREATE TABLE session_tag_assignments (
  assignment_id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL,
  session_id TEXT NOT NULL,
  tag_id TEXT NOT NULL,
  assigned_by TEXT NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT chk_session_tag_assignment_id
    CHECK (assignment_id ~ '^sta_[a-zA-Z0-9]{16,32}$'),
  CONSTRAINT fk_session_tag_assignment_session
    FOREIGN KEY (session_id, tenant_id)
      REFERENCES sessions(id, tenant_id) ON DELETE CASCADE,
  CONSTRAINT fk_session_tag_assignment_tag
    FOREIGN KEY (tag_id, tenant_id)
      REFERENCES workspace_tags(tag_id, tenant_id) ON DELETE CASCADE,
  UNIQUE (session_id, tag_id)
);

CREATE INDEX idx_session_tag_assignments_tenant_tag
  ON session_tag_assignments (tenant_id, tag_id, assigned_at DESC);

CREATE INDEX idx_session_tag_assignments_tenant_session
  ON session_tag_assignments (tenant_id, session_id, assigned_at DESC);

-- Preserve tags created by the previous Web wizard, which stored a validated
-- comma-separated list in sessions.metadata.tags. IDs are deterministic so
-- retries and restored databases produce the same authoritative resources.
WITH legacy_tags AS (
  SELECT DISTINCT ON (session.tenant_id, lower(btrim(value)))
    session.tenant_id,
    btrim(value) AS name
  FROM sessions session
  CROSS JOIN LATERAL regexp_split_to_table(
    coalesce(session.metadata ->> 'tags', ''),
    '\s*,\s*'
  ) value
  WHERE char_length(btrim(value)) BETWEEN 1 AND 32
  ORDER BY session.tenant_id, lower(btrim(value)), btrim(value)
)
INSERT INTO workspace_tags (
  tag_id,
  tenant_id,
  name,
  color,
  created_by,
  created_at,
  updated_at
)
SELECT
  'tag_legacy' || substr(md5(tenant_id || ':' || lower(name)), 1, 20),
  tenant_id,
  name,
  '#718096',
  'system:v035-backfill',
  now(),
  now()
FROM legacy_tags;

WITH legacy_assignments AS (
  SELECT DISTINCT ON (session.id, lower(btrim(value)))
    session.id AS session_id,
    session.tenant_id,
    btrim(value) AS name
  FROM sessions session
  CROSS JOIN LATERAL regexp_split_to_table(
    coalesce(session.metadata ->> 'tags', ''),
    '\s*,\s*'
  ) value
  WHERE char_length(btrim(value)) BETWEEN 1 AND 32
  ORDER BY session.id, lower(btrim(value)), btrim(value)
)
INSERT INTO session_tag_assignments (
  assignment_id,
  tenant_id,
  session_id,
  tag_id,
  assigned_by,
  assigned_at
)
SELECT
  'sta_legacy' || substr(md5(
    assignment.session_id || ':' || lower(assignment.name)
  ), 1, 20),
  assignment.tenant_id,
  assignment.session_id,
  tag.tag_id,
  'system:v035-backfill',
  now()
FROM legacy_assignments assignment
JOIN workspace_tags tag
  ON tag.tenant_id = assignment.tenant_id
 AND lower(tag.name) = lower(assignment.name)
ON CONFLICT (session_id, tag_id) DO NOTHING;

COMMENT ON TABLE workspace_tags IS
  'Tenant-owned reusable Workspace Tags';

COMMENT ON TABLE session_tag_assignments IS
  'Authoritative many-to-many Workspace Tag assignments for Sessions';
