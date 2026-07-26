import { afterEach, describe, expect, it } from 'vitest';
import {
  canOperate,
  canRead,
  getRuntimeIdentity,
  hasAnyRole,
  setRuntimeIdentity,
} from './runtimeIdentity';
import {
  currentActorId,
  currentTenantId,
  identityHeaders,
} from '@/api/session';

describe('runtime identity and role policy', () => {
  afterEach(() => setRuntimeIdentity(null));

  it('keeps tenant viewers read-only', () => {
    expect(canRead(['TENANT_VIEWER'])).toBe(true);
    expect(canOperate(['TENANT_VIEWER'])).toBe(false);
    expect(hasAnyRole(['TENANT_VIEWER'], ['SECURITY_ADMIN'])).toBe(false);
  });

  it('allows operator and administrative roles to control sessions', () => {
    expect(canOperate(['TENANT_OPERATOR'])).toBe(true);
    expect(canOperate(['TENANT_ADMIN'])).toBe(true);
    expect(canOperate(['SECURITY_ADMIN'])).toBe(true);
    expect(canOperate(['PLATFORM_ADMIN'])).toBe(true);
  });

  it('uses the authenticated token without accepting local identity headers', () => {
    setRuntimeIdentity({
      accessToken: 'oidc-token',
      actorId: 'oidc-user',
      tenantId: 'tenant-oidc',
      roles: ['TENANT_OPERATOR'],
    });

    expect(getRuntimeIdentity()?.tenantId).toBe('tenant-oidc');
    expect(currentTenantId()).toBe('tenant-oidc');
    expect(currentActorId()).toBe('oidc-user');
    expect(identityHeaders('tenant-forged', 'actor-forged')).toEqual({
      Authorization: 'Bearer oidc-token',
    });
  });

  it('propagates the selected local tenant, actor, and roles in development', () => {
    setRuntimeIdentity({
      actorId: 'local-user',
      tenantId: 'tenant-local-test',
      roles: ['TENANT_VIEWER'],
    });

    expect(identityHeaders()).toEqual({
      'X-Tenant-Id': 'tenant-local-test',
      'X-Actor-Id': 'local-user',
      'X-Roles': 'TENANT_VIEWER',
    });
  });
});
