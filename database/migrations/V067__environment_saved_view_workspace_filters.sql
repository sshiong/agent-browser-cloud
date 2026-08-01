-- Persist Workspace Group/Tag filters in tenant-authoritative Environment Saved Views.
--
-- This is an additive expand migration. Existing rows and older clients resolve to
-- no Group filter, no Tag filters and ANY matching without a backfill or rewrite.

ALTER TABLE environment_saved_views
  ADD COLUMN group_id TEXT,
  ADD COLUMN tag_ids JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN tag_match TEXT NOT NULL DEFAULT 'ANY';

CREATE OR REPLACE FUNCTION is_valid_environment_saved_view_tag_ids(candidate JSONB)
RETURNS BOOLEAN
LANGUAGE SQL
IMMUTABLE
STRICT
PARALLEL SAFE
AS $$
  SELECT CASE
    WHEN jsonb_typeof(candidate) <> 'array' THEN FALSE
    ELSE (
      SELECT
        count(*) <= 16
        AND count(*) = count(DISTINCT value)
        AND coalesce(bool_and(value ~ '^tag_[a-zA-Z0-9]{16,32}$'), TRUE)
      FROM jsonb_array_elements_text(candidate) AS element(value)
    )
  END
$$;

ALTER TABLE environment_saved_views
  ADD CONSTRAINT chk_environment_saved_view_group_id CHECK (
    group_id IS NULL OR group_id ~ '^grp_[a-zA-Z0-9]{16,32}$'
  ),
  ADD CONSTRAINT chk_environment_saved_view_tag_ids CHECK (
    is_valid_environment_saved_view_tag_ids(tag_ids)
  ),
  ADD CONSTRAINT chk_environment_saved_view_tag_match CHECK (
    tag_match IN ('ANY', 'ALL')
  ),
  ADD CONSTRAINT chk_environment_saved_view_tag_match_usage CHECK (
    CASE
      WHEN tag_match = 'ALL' AND jsonb_typeof(tag_ids) = 'array'
        THEN jsonb_array_length(tag_ids) > 1
      ELSE tag_match = 'ANY'
    END
  );

CREATE UNIQUE INDEX uq_workspace_groups_id_tenant_saved_view
  ON workspace_groups (group_id, tenant_id);

ALTER TABLE environment_saved_views
  ADD CONSTRAINT fk_environment_saved_view_group
    FOREIGN KEY (group_id, tenant_id)
      REFERENCES workspace_groups(group_id, tenant_id)
      ON DELETE SET NULL (group_id)
    NOT VALID;

ALTER TABLE environment_saved_views
  VALIDATE CONSTRAINT fk_environment_saved_view_group;

COMMENT ON COLUMN environment_saved_views.group_id IS
  'Optional tenant-validated Workspace Group filter';

COMMENT ON COLUMN environment_saved_views.tag_ids IS
  'Ordered, unique tenant-validated Workspace Tag filter IDs (maximum 16)';

COMMENT ON COLUMN environment_saved_views.tag_match IS
  'Workspace Tag matching mode; ALL is meaningful only for two or more tags';
