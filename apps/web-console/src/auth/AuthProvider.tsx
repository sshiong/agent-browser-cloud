import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import {
  canOperate,
  canRead,
  hasAnyRole,
  setRuntimeIdentity,
  type PlatformRole,
  type RuntimeIdentity,
} from './runtimeIdentity';

type AuthMode = 'local' | 'oidc';

interface AuthContextValue {
  identity: RuntimeIdentity | null;
  mode: AuthMode;
  loading: boolean;
  error: string | null;
  authenticated: boolean;
  canRead: boolean;
  canOperate: boolean;
  hasAnyRole: (roles: PlatformRole[]) => boolean;
  login: () => Promise<void>;
  logout: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);
const allowedRoles = new Set<PlatformRole>([
  'TENANT_VIEWER',
  'TENANT_OPERATOR',
  'TENANT_ADMIN',
  'SECURITY_ADMIN',
  'PLATFORM_ADMIN',
]);
const authMode: AuthMode =
  import.meta.env.VITE_AUTH_MODE === 'local'
    ? 'local'
    : import.meta.env.VITE_AUTH_MODE === 'oidc'
      ? 'oidc'
      : import.meta.env.DEV
        ? 'local'
        : 'oidc';

let manager: UserManager | null = null;
let callbackPromise: Promise<User> | null = null;

function oidcManager() {
  if (manager) return manager;
  const authority = import.meta.env.VITE_OIDC_AUTHORITY?.trim();
  const clientId = import.meta.env.VITE_OIDC_CLIENT_ID?.trim();
  if (!authority || !clientId) return null;
  const origin = window.location.origin;
  manager = new UserManager({
    authority,
    client_id: clientId,
    redirect_uri: `${origin}/auth/callback`,
    post_logout_redirect_uri: origin,
    response_type: 'code',
    scope:
      import.meta.env.VITE_OIDC_SCOPE?.trim() ||
      'openid profile offline_access',
    automaticSilentRenew: true,
    monitorSession: true,
    loadUserInfo: false,
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  });
  return manager;
}

function normalizeRoles(value: unknown): PlatformRole[] {
  const values = Array.isArray(value)
    ? value
    : typeof value === 'string'
      ? value.split(/[,\s]+/)
      : [];
  return [
    ...new Set(
      values
        .map((role) => String(role).toUpperCase())
        .filter((role): role is PlatformRole =>
          allowedRoles.has(role as PlatformRole)
        )
    ),
  ];
}

function identityFromUser(user: User): RuntimeIdentity {
  const rolesClaim = import.meta.env.VITE_OIDC_ROLES_CLAIM?.trim() || 'roles';
  const tenantClaim =
    import.meta.env.VITE_OIDC_TENANT_CLAIM?.trim() || 'tenant_id';
  const claims = user.profile as Record<string, unknown>;
  const tenantId = String(claims[tenantClaim] ?? '').trim();
  if (!tenantId) {
    throw new Error(`OIDC Token 缺少租户 Claim：${tenantClaim}`);
  }
  const roles = normalizeRoles(claims[rolesClaim]);
  if (!canRead(roles)) {
    throw new Error(`OIDC Token 没有受支持的角色 Claim：${rolesClaim}`);
  }
  return {
    accessToken: user.access_token,
    actorId: String(claims.sub ?? user.profile.sub),
    tenantId,
    roles,
  };
}

function localIdentity(): RuntimeIdentity {
  const configuredRoles =
    import.meta.env.VITE_LOCAL_ROLES ||
    'TENANT_ADMIN,SECURITY_ADMIN,PLATFORM_ADMIN';
  return {
    actorId: import.meta.env.VITE_ACTOR_ID?.trim() || 'user-local',
    tenantId: import.meta.env.VITE_TENANT_ID?.trim() || 'tenant-local',
    roles: normalizeRoles(configuredRoles),
  };
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [identity, setIdentity] = useState<RuntimeIdentity | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const acceptUser = useCallback((user: User | null) => {
    try {
      const next = user && !user.expired ? identityFromUser(user) : null;
      setRuntimeIdentity(next);
      setIdentity(next);
      setError(null);
    } catch (cause) {
      setRuntimeIdentity(null);
      setIdentity(null);
      setError(cause instanceof Error ? cause.message : 'OIDC 身份无效');
    }
  }, []);

  useEffect(() => {
    if (authMode === 'local') {
      const next = localIdentity();
      setRuntimeIdentity(next);
      setIdentity(next);
      setLoading(false);
      return () => setRuntimeIdentity(null);
    }

    const oidc = oidcManager();
    if (!oidc) {
      setError(
        '生产 OIDC 未配置：需要 VITE_OIDC_AUTHORITY 与 VITE_OIDC_CLIENT_ID。'
      );
      setLoading(false);
      return;
    }
    const loaded = (user: User) => acceptUser(user);
    const unloaded = () => acceptUser(null);
    oidc.events.addUserLoaded(loaded);
    oidc.events.addUserUnloaded(unloaded);
    oidc.events.addAccessTokenExpired(unloaded);

    let active = true;
    void (async () => {
      try {
        const isCallback =
          window.location.pathname === '/auth/callback' &&
          new URLSearchParams(window.location.search).has('code');
        let user: User | null;
        if (isCallback) {
          callbackPromise ??= oidc.signinRedirectCallback();
          user = await callbackPromise;
          const state = user.state as { returnTo?: unknown } | undefined;
          const returnTo =
            typeof state?.returnTo === 'string' &&
            state.returnTo.startsWith('/') &&
            !state.returnTo.startsWith('//')
              ? state.returnTo
              : '/';
          window.history.replaceState({}, '', returnTo);
        } else {
          user = await oidc.getUser();
        }
        if (active) acceptUser(user);
      } catch (cause) {
        if (active) {
          setRuntimeIdentity(null);
          setIdentity(null);
          setError(cause instanceof Error ? cause.message : 'OIDC 登录失败');
        }
      } finally {
        if (active) setLoading(false);
      }
    })();
    return () => {
      active = false;
      oidc.events.removeUserLoaded(loaded);
      oidc.events.removeUserUnloaded(unloaded);
      oidc.events.removeAccessTokenExpired(unloaded);
    };
  }, [acceptUser]);

  const login = useCallback(async () => {
    const oidc = oidcManager();
    if (!oidc) throw new Error('OIDC 未配置');
    const returnTo = `${window.location.pathname}${window.location.search}`;
    await oidc.signinRedirect({ state: { returnTo } });
  }, []);

  const logout = useCallback(async () => {
    if (authMode === 'local') return;
    const oidc = oidcManager();
    if (!oidc) return;
    setRuntimeIdentity(null);
    setIdentity(null);
    await oidc.signoutRedirect();
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({
      identity,
      mode: authMode,
      loading,
      error,
      authenticated: Boolean(identity),
      canRead: identity ? canRead(identity.roles) : false,
      canOperate: identity ? canOperate(identity.roles) : false,
      hasAnyRole: (roles) =>
        identity ? hasAnyRole(identity.roles, roles) : false,
      login,
      logout,
    }),
    [error, identity, loading, login, logout]
  );
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

// The hook shares the provider's private context and intentionally lives beside it.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const value = useContext(AuthContext);
  if (!value) throw new Error('useAuth must be used inside AuthProvider');
  return value;
}
