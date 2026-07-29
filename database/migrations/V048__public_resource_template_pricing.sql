-- Keep L0-L5 as an internal scheduler compatibility dimension while public pricing uses templates.
-- The trigger preserves N-1 writers that still populate only resource_class during rolling upgrades.

ALTER TABLE enterprise_cost_rates
    ADD COLUMN resource_template TEXT;

UPDATE enterprise_cost_rates
SET resource_template = CASE resource_class
    WHEN 'L0' THEN 'suspended-v1'
    WHEN 'L1' THEN 'standard-lite-v1'
    WHEN 'L2' THEN 'standard-v1'
    WHEN 'L3' THEN 'interactive-v1'
    WHEN 'L4' THEN 'heavy-v1'
    WHEN 'L5' THEN 'native-standard-v1'
END;

CREATE OR REPLACE FUNCTION sync_enterprise_cost_resource_template()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    NEW.resource_template := CASE NEW.resource_class
        WHEN 'L0' THEN 'suspended-v1'
        WHEN 'L1' THEN 'standard-lite-v1'
        WHEN 'L2' THEN 'standard-v1'
        WHEN 'L3' THEN 'interactive-v1'
        WHEN 'L4' THEN 'heavy-v1'
        WHEN 'L5' THEN 'native-standard-v1'
    END;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_enterprise_cost_resource_template
BEFORE INSERT OR UPDATE OF resource_class, resource_template
ON enterprise_cost_rates
FOR EACH ROW
EXECUTE FUNCTION sync_enterprise_cost_resource_template();

ALTER TABLE enterprise_cost_rates
    ALTER COLUMN resource_template SET NOT NULL;

ALTER TABLE enterprise_cost_rates
    ADD CONSTRAINT chk_enterprise_cost_resource_template
    CHECK (
        resource_template IN (
            'suspended-v1',
            'standard-lite-v1',
            'standard-v1',
            'interactive-v1',
            'heavy-v1',
            'native-standard-v1'
        )
    ) NOT VALID;

ALTER TABLE enterprise_cost_rates
    VALIDATE CONSTRAINT chk_enterprise_cost_resource_template;

CREATE INDEX idx_enterprise_cost_template_lookup
ON enterprise_cost_rates(region, resource_template, effective_at DESC);

UPDATE enterprise_cost_rates
SET pricing_version = CASE pricing_version
    WHEN 'local-l1-v1' THEN 'local-standard-lite-v1'
    WHEN 'local-l2-v1' THEN 'local-standard-v1'
    WHEN 'local-l3-v1' THEN 'local-interactive-v1'
    WHEN 'local-l4-v1' THEN 'local-heavy-v1'
    WHEN 'local-l5-v1' THEN 'local-native-standard-v1'
    ELSE pricing_version
END;

UPDATE session_resource_policies
SET cost_pricing_version = CASE cost_pricing_version
    WHEN 'local-l1-v1' THEN 'local-standard-lite-v1'
    WHEN 'local-l2-v1' THEN 'local-standard-v1'
    WHEN 'local-l3-v1' THEN 'local-interactive-v1'
    WHEN 'local-l4-v1' THEN 'local-heavy-v1'
    WHEN 'local-l5-v1' THEN 'local-native-standard-v1'
    ELSE cost_pricing_version
END;

UPDATE session_resource_cost_snapshots
SET pricing_version = CASE pricing_version
    WHEN 'local-l1-v1' THEN 'local-standard-lite-v1'
    WHEN 'local-l2-v1' THEN 'local-standard-v1'
    WHEN 'local-l3-v1' THEN 'local-interactive-v1'
    WHEN 'local-l4-v1' THEN 'local-heavy-v1'
    WHEN 'local-l5-v1' THEN 'local-native-standard-v1'
    ELSE pricing_version
END;

COMMENT ON COLUMN enterprise_cost_rates.resource_template IS
    'Public-safe internal template identifier; L0-L5 resource_class remains rollout compatibility only';
