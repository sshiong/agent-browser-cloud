-- Independent, site-scoped Proxy challenge evidence.
--
-- Only confirmed anti-automation Challenge classifications are eligible.  The ledger stores a
-- normalized site hash rather than a URL or page content.  Routing applies a short quarantine only
-- after the same Binding/Provider is observed in at least three distinct Sessions, avoiding a
-- single CAPTCHA or one noisy Session becoming a permanent blacklist.

CREATE TABLE proxy_route_site_challenges (
    challenge_event_id    TEXT PRIMARY KEY REFERENCES challenge_events(challenge_event_id),
    tenant_id             TEXT NOT NULL,
    session_id            TEXT NOT NULL,
    binding_profile_id    TEXT NOT NULL,
    provider_id           TEXT NOT NULL,
    site_domain_hash      TEXT NOT NULL,
    challenge_type        TEXT NOT NULL,
    detected_at           TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_proxy_route_site_challenge_binding
      FOREIGN KEY (binding_profile_id, tenant_id)
      REFERENCES proxy_binding_profiles(binding_profile_id, tenant_id),
    CONSTRAINT fk_proxy_route_site_challenge_session
      FOREIGN KEY (session_id, tenant_id)
      REFERENCES sessions(id, tenant_id),
    CONSTRAINT chk_proxy_route_site_challenge_hash
      CHECK (site_domain_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_proxy_route_site_challenge_type
      CHECK (challenge_type IN (
        'SINGLE_CLICK', 'OPAQUE_FRAME_SINGLE_CLICK', 'IMAGE_SELECTION', 'PUZZLE', 'MULTI_ROUND'
      ))
);

CREATE INDEX idx_proxy_route_site_challenge_lookup
  ON proxy_route_site_challenges(
    tenant_id, site_domain_hash, binding_profile_id, provider_id, detected_at DESC
  );

ALTER TABLE session_proxy_binding_assignments
  ADD COLUMN routing_site_domain_hash TEXT;

ALTER TABLE session_proxy_binding_assignments
  ADD CONSTRAINT chk_session_proxy_binding_routing_site_hash
    CHECK (routing_site_domain_hash IS NULL OR routing_site_domain_hash ~ '^[a-f0-9]{64}$')
    NOT VALID;

ALTER TABLE session_proxy_binding_assignments
  VALIDATE CONSTRAINT chk_session_proxy_binding_routing_site_hash;

COMMENT ON TABLE proxy_route_site_challenges IS
  'Minimal independent anti-automation Challenge evidence for temporary site-scoped Proxy quarantine';
COMMENT ON COLUMN session_proxy_binding_assignments.routing_site_domain_hash IS
  'Optional normalized site hint hash used for reproducible site-aware AUTO route admission';
