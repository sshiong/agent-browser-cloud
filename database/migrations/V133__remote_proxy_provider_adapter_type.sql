ALTER TABLE proxy_allocations
  DROP CONSTRAINT proxy_allocations_provider_adapter_type_check;

ALTER TABLE proxy_allocations
  ADD CONSTRAINT proxy_allocations_provider_adapter_type_check
    CHECK (
      provider_adapter_type IS NULL
      OR provider_adapter_type IN ('CONFIGURED_HTTP', 'REMOTE_HTTP_V1')
    ) NOT VALID;

ALTER TABLE proxy_allocations
  VALIDATE CONSTRAINT proxy_allocations_provider_adapter_type_check;

COMMENT ON COLUMN proxy_allocations.provider_adapter_type IS
  '创建端点的 Adapter 类型；CONFIGURED_HTTP 为无状态固定出口，REMOTE_HTTP_V1 为隔离动态供应商网关；不得由 Browser Runtime 覆盖';
