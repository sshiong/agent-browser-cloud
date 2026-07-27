import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import {
  UserManager,
  WebStorageStateStore,
  type INavigator,
  type IWindow,
  type NavigateParams,
  type NavigateResponse,
  type User,
} from 'oidc-client-ts';
import { PlatformSecureStateStore } from './PlatformSecureStateStore';
import { usePlatform } from '@/platform/PlatformProvider';
import type { PlatformAdapter } from '@/platform';
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
let managerPlatform: PlatformAdapter | null = null;
let callbackPromise: Promise<User> | null = null;

function oidcManager(platform: PlatformAdapter) {
  if (manager && managerPlatform === platform) return manager;
  const authority = import.meta.env.VITE_OIDC_AUTHORITY?.trim();
  const clientId = import.meta.env.VITE_OIDC_CLIENT_ID?.trim();
  if (!authority || !clientId) return null;
  const origin = window.location.origin;
  const redirectUri =
    import.meta.env.VITE_OIDC_REDIRECT_URI?.trim() ||
    (platform.desktop
      ? 'agentbrowsercloud://auth/callback'
      : `${origin}/auth/callback`);
  const postLogoutRedirectUri =
    import.meta.env.VITE_OIDC_POST_LOGOUT_REDIRECT_URI?.trim() ||
    (platform.desktop ? 'agentbrowsercloud://auth/logout' : origin);
  const userStore = platform.desktop
    ? new PlatformSecureStateStore(platform, 'user')
    : new WebStorageStateStore({ store: window.sessionStorage });
  const stateStore = platform.desktop
    ? new PlatformSecureStateStore(platform, 'state')
    : new WebStorageStateStore({ store: window.sessionStorage });
  const redirectNavigator = platform.desktop
    ? new DesktopRedirectNavigator(platform)
    : undefined;
  manager = new UserManager(
    {
      authority,
      client_id: clientId,
      redirect_uri: redirectUri,
      post_logout_redirect_uri: postLogoutRedirectUri,
      response_type: 'code',
      scope:
        import.meta.env.VITE_OIDC_SCOPE?.trim() ||
        'openid profile offline_access',
      automaticSilentRenew: true,
      monitorSession: !platform.desktop,
      loadUserInfo: false,
      userStore,
      stateStore,
    },
    redirectNavigator
  );
  managerPlatform = platform;
  return manager;
}

class DesktopRedirectNavigator implements INavigator {
  constructor(private readonly platform: PlatformAdapter) {}

  async prepare(): Promise<IWindow> {
    return new DesktopExternalWindow(this.platform);
  }

  async callback() {
    // The deep-link plugin forwards callbacks to AuthProvider.
  }
}

class DesktopExternalWindow implements IWindow {
  constructor(private readonly platform: PlatformAdapter) {}

  async navigate(params: NavigateParams): Promise<NavigateResponse> {
    await this.platform.openExternal(params.url);
    return { url: params.url };
  }

  close() {
    // The system browser owns its own lifecycle.
  }
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
  const platform = usePlatform();
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

    const oidc = oidcManager(platform);
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
    let unlistenDeepLinks: (() => void) | undefined;
    const processedDeepLinks = new Set<string>();
    void (async () => {
      try {
        let user: User | null;
        if (platform.desktop) {
          const processUrls = async (urls: string[]) => {
            for (const url of urls) {
              if (processedDeepLinks.has(url)) continue;
              processedDeepLinks.add(url);
              await processDesktopAuthCallback(
                oidc,
                url,
                acceptUser,
                () => active
              );
            }
          };
          unlistenDeepLinks = await platform.onOpenUrls((urls) => {
            if (!active) return;
            setLoading(true);
            void processUrls(urls)
              .catch((cause) => {
                if (active) {
                  setError(
                    cause instanceof Error
                      ? cause.message
                      : 'Desktop OIDC 回调失败'
                  );
                }
              })
              .finally(() => {
                if (active) setLoading(false);
              });
          });
          await processUrls(await platform.getInitialOpenUrls());
          user = await oidc.getUser();
        } else {
          const isCallback =
            window.location.pathname === '/auth/callback' &&
            new URLSearchParams(window.location.search).has('code');
          if (isCallback) {
            callbackPromise ??= oidc.signinRedirectCallback();
            user = await callbackPromise;
            window.history.replaceState({}, '', returnPath(user));
          } else {
            user = await oidc.getUser();
          }
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
      unlistenDeepLinks?.();
      oidc.events.removeUserLoaded(loaded);
      oidc.events.removeUserUnloaded(unloaded);
      oidc.events.removeAccessTokenExpired(unloaded);
    };
  }, [acceptUser, platform]);

  const login = useCallback(async () => {
    const oidc = oidcManager(platform);
    if (!oidc) throw new Error('OIDC 未配置');
    const returnTo = `${window.location.pathname}${window.location.search}`;
    await oidc.signinRedirect({ state: { returnTo } });
  }, [platform]);

  const logout = useCallback(async () => {
    if (authMode === 'local') return;
    const oidc = oidcManager(platform);
    if (!oidc) return;
    setRuntimeIdentity(null);
    setIdentity(null);
    await oidc.signoutRedirect();
  }, [platform]);

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

async function processDesktopAuthCallback(
  oidc: UserManager,
  value: string,
  acceptUser: (user: User | null) => void,
  isActive: () => boolean
) {
  const url = new URL(value);
  if (url.protocol !== 'agentbrowsercloud:' || url.hostname !== 'auth') return;
  if (url.pathname === '/callback') {
    const user = await oidc.signinRedirectCallback(value);
    if (isActive()) {
      acceptUser(user);
      window.history.replaceState({}, '', returnPath(user));
    }
    return;
  }
  if (url.pathname === '/logout') {
    await oidc.signoutRedirectCallback(value);
    if (isActive()) {
      acceptUser(null);
      window.history.replaceState({}, '', '/');
    }
  }
}

function returnPath(user: User) {
  const state = user.state as { returnTo?: unknown } | undefined;
  return typeof state?.returnTo === 'string' &&
    state.returnTo.startsWith('/') &&
    !state.returnTo.startsWith('//')
    ? state.returnTo
    : '/';
}
