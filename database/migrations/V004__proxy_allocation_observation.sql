ALTER TABLE proxy_allocations
    ADD COLUMN exit_ip TEXT,
    ADD COLUMN verified_at TIMESTAMPTZ,
    ADD COLUMN failure_reason TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();

CREATE UNIQUE INDEX uq_active_proxy_allocation_per_session
ON proxy_allocations(session_id)
WHERE state IN ('ALLOCATED', 'BOUND');

ALTER TABLE proxy_allocations
    ADD CONSTRAINT chk_proxy_allocation_state
        CHECK (state IN ('ALLOCATED', 'BOUND', 'RELEASED', 'FAILED'));
