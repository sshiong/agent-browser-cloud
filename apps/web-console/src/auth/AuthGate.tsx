import { LogIn, ShieldAlert } from 'lucide-react';
import { Navigate } from 'react-router';
import { LoadingPanel } from '@/components/feedback/AsyncStates';
import { useAuth } from './AuthProvider';
import type { PlatformRole } from './runtimeIdentity';

export function AuthGate({ children }: { children: React.ReactNode }) {
  const auth = useAuth();
  if (auth.loading) return <LoadingPanel label="正在恢复安全会话" />;
  if (auth.error) {
    const retryable =
      auth.mode === 'oidc' && !auth.error.startsWith('生产 OIDC 未配置');
    return (
      <AuthMessage
        title="身份配置或登录失败"
        description={auth.error}
        action={
          retryable ? (
            <button
              type="button"
              onClick={() => void auth.login().catch(() => undefined)}
              className="mt-4 inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas"
            >
              <LogIn size={14} />
              重新登录
            </button>
          ) : null
        }
      />
    );
  }
  if (!auth.authenticated) {
    return (
      <AuthMessage
        title="需要登录"
        description="使用组织 OIDC 身份登录后，控制台才会请求租户资源。"
        action={
          <button
            type="button"
            onClick={() => void auth.login().catch(() => undefined)}
            className="mt-4 inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas"
          >
            <LogIn size={14} />
            使用 OIDC 登录
          </button>
        }
      />
    );
  }
  return children;
}

export function RequireRoles({
  roles,
  children,
}: {
  roles: PlatformRole[];
  children: React.ReactNode;
}) {
  const auth = useAuth();
  return auth.hasAnyRole(roles) ? (
    children
  ) : (
    <Navigate to="/unauthorized" replace />
  );
}

export function UnauthorizedPage() {
  return (
    <AuthMessage
      title="权限不足"
      description="当前 OIDC 身份没有访问该工作区所需的角色。服务端仍会独立执行 RBAC 校验。"
    />
  );
}

function AuthMessage({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="grid min-h-screen place-items-center bg-canvas p-6">
      <div className="w-full max-w-lg border border-border-subtle bg-surface-1 p-8 text-center">
        <ShieldAlert size={24} className="mx-auto text-warning" />
        <h1 className="mt-4 text-[18px] font-semibold text-text-primary">
          {title}
        </h1>
        <p className="mt-2 text-[12px] leading-6 text-text-muted">
          {description}
        </p>
        {action}
      </div>
    </div>
  );
}
