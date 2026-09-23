ALTER TABLE proxy_allocations
  ADD COLUMN provider_endpoint_id TEXT,
  ADD COLUMN provider_adapter_type TEXT DEFAULT 'CONFIGURED_HTTP';

ALTER TABLE proxy_allocations
  ADD CONSTRAINT proxy_allocations_provider_endpoint_id_check
    CHECK (provider_endpoint_id IS NULL OR length(provider_endpoint_id) BETWEEN 1 AND 256) NOT VALID,
  ADD CONSTRAINT proxy_allocations_provider_adapter_type_check
    CHECK (provider_adapter_type IS NULL OR provider_adapter_type IN ('CONFIGURED_HTTP')) NOT VALID;

ALTER TABLE proxy_allocations
  VALIDATE CONSTRAINT proxy_allocations_provider_endpoint_id_check;

ALTER TABLE proxy_allocations
  VALIDATE CONSTRAINT proxy_allocations_provider_adapter_type_check;

COMMENT ON COLUMN proxy_allocations.provider_endpoint_id IS
  '供应商 Adapter 返回的端点身份；与控制面 allocation_id 分离，供幂等 health/rotate/release/usage 使用；N-1 写入暂允许 NULL';

COMMENT ON COLUMN proxy_allocations.provider_adapter_type IS
  '创建端点的 Adapter 类型；不得由 Browser Runtime 覆盖';
