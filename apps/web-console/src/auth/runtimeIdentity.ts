export type PlatformRole =
  | 'TENANT_VIEWER'
  | 'TENANT_OPERATOR'
  | 'TENANT_ADMIN'
  | 'SECURITY_ADMIN'
  | 'PLATFORM_ADMIN';

export interface RuntimeIdentity {
  accessToken?: string;
  actorId: string;
  tenantId: string;
  roles: PlatformRole[];
}

let currentIdentity: RuntimeIdentity | null = null;

export function setRuntimeIdentity(identity: RuntimeIdentity | null) {
  currentIdentity = identity;
}

export function getRuntimeIdentity() {
  return currentIdentity;
}

export function canRead(roles: PlatformRole[]) {
  return roles.length > 0;
}

export function canOperate(roles: PlatformRole[]) {
  return roles.some((role) =>
    [
      'TENANT_OPERATOR',
      'TENANT_ADMIN',
      'SECURITY_ADMIN',
      'PLATFORM_ADMIN',
    ].includes(role)
  );
}

export function hasAnyRole(roles: PlatformRole[], required: PlatformRole[]) {
  return required.some((role) => roles.includes(role));
}
