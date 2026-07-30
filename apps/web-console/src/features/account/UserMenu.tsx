import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Building2,
  ChevronDown,
  KeyRound,
  LoaderCircle,
  LogOut,
  Settings,
  ShieldCheck,
  TriangleAlert,
  UserRound,
} from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '@/auth/AuthProvider';
import type { PlatformRole } from '@/auth/runtimeIdentity';

const ROLE_LABELS: Record<PlatformRole, string> = {
  TENANT_VIEWER: '租户只读',
  TENANT_OPERATOR: '租户操作员',
  TENANT_ADMIN: '租户管理员',
  SECURITY_ADMIN: '安全管理员',
  PLATFORM_ADMIN: '平台管理员',
};

const SETTINGS_ROLES: PlatformRole[] = [
  'TENANT_ADMIN',
  'SECURITY_ADMIN',
  'PLATFORM_ADMIN',
];
const SECURITY_ROLES: PlatformRole[] = ['SECURITY_ADMIN', 'PLATFORM_ADMIN'];

export function UserMenu() {
  const auth = useAuth();
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);
  const [logoutError, setLogoutError] = useState<string | null>(null);
  const identity = auth.identity;
  const actorId = identity?.actorId ?? '身份不可用';
  const tenantId = identity?.tenantId ?? '未绑定租户';
  const canOpenSettings = auth.hasAnyRole(SETTINGS_ROLES);
  const canOpenSecurity = auth.hasAnyRole(SECURITY_ROLES);

  const logout = async () => {
    if (loggingOut) return;
    setLoggingOut(true);
    setLogoutError(null);
    try {
      await auth.logout();
    } catch (cause) {
      setLogoutError(
        cause instanceof Error ? cause.message : '无法跳转到身份提供商'
      );
      setLoggingOut(false);
    }
  };

  return (
    <DropdownMenu.Root
      onOpenChange={(open) => {
        if (open) setLogoutError(null);
      }}
    >
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          aria-label={`用户菜单：${actorId}`}
          title={`${actorId} · ${tenantId}`}
          className="group flex h-10 max-w-[224px] items-center gap-2 rounded-md text-left outline-none transition-colors hover:bg-surface-2 focus-visible:ring-2 focus-visible:ring-accent/55 data-[state=open]:bg-surface-2 md:h-9 sm:border-l sm:border-border-subtle sm:pl-3"
        >
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full border border-accent/25 bg-accent-soft text-accent">
            <UserRound size={14} />
          </span>
          <span className="hidden min-w-0 flex-1 sm:block">
            <span className="block truncate text-[11px] font-medium text-text-secondary">
              {actorId}
            </span>
            <span className="block truncate font-mono text-[9px] text-text-muted">
              {tenantId}
            </span>
          </span>
          <ChevronDown
            size={12}
            className="mr-1 hidden shrink-0 text-text-muted transition-transform group-data-[state=open]:rotate-180 sm:block"
          />
        </button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={8}
          collisionPadding={12}
          className="z-[60] w-[300px] border border-border-default bg-surface-1 p-1.5 shadow-[0_18px_52px_rgba(3,10,18,0.28)] outline-none data-[state=open]:animate-[theme-menu-enter_150ms_cubic-bezier(0.16,1,0.3,1)]"
        >
          <div className="border-b border-border-subtle px-2.5 pb-3 pt-2">
            <div className="flex items-start gap-2.5">
              <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-accent/25 bg-accent-soft text-accent">
                <UserRound size={16} />
              </span>
              <span className="min-w-0 flex-1">
                <span className="block truncate text-[11px] font-semibold text-text-primary">
                  {actorId}
                </span>
                <span className="mt-0.5 flex items-center gap-1 truncate font-mono text-[9px] text-text-muted">
                  <Building2 size={10} className="shrink-0" />
                  {tenantId}
                </span>
              </span>
              <span className="border border-border-subtle bg-surface-2 px-1.5 py-0.5 font-mono text-[8px] uppercase tracking-[0.12em] text-text-muted">
                {auth.mode === 'oidc' ? 'OIDC' : 'LOCAL'}
              </span>
            </div>

            <div className="mt-3">
              <p className="font-mono text-[8px] uppercase tracking-[0.14em] text-text-muted">
                当前权限
              </p>
              <div className="mt-1.5 flex flex-wrap gap-1">
                {identity?.roles.map((role) => (
                  <span
                    key={role}
                    title={role}
                    className="border border-border-subtle bg-surface-2 px-1.5 py-1 text-[8px] text-text-secondary"
                  >
                    {ROLE_LABELS[role]}
                  </span>
                ))}
              </div>
            </div>
          </div>

          {(canOpenSettings || canOpenSecurity) && (
            <div className="py-1.5">
              <DropdownMenu.Label className="px-2.5 py-1 font-mono text-[8px] uppercase tracking-[0.14em] text-text-muted">
                管理入口
              </DropdownMenu.Label>
              {canOpenSettings && (
                <MenuItem
                  icon={Settings}
                  label="Workspace 设置"
                  description="策略、集成与运行参数"
                  onSelect={() => navigate('/settings')}
                />
              )}
              {canOpenSecurity && (
                <MenuItem
                  icon={ShieldCheck}
                  label="安全中心"
                  description="密钥、调试与审计治理"
                  onSelect={() => navigate('/security')}
                />
              )}
            </div>
          )}

          <div className="border-t border-border-subtle pt-1.5">
            {auth.mode === 'oidc' ? (
              <DropdownMenu.Item
                disabled={loggingOut}
                onSelect={(event) => {
                  event.preventDefault();
                  void logout();
                }}
                className="flex min-h-11 items-center gap-2.5 px-2.5 text-danger outline-none transition-colors data-[disabled]:opacity-45 data-[highlighted]:bg-danger/8"
              >
                {loggingOut ? (
                  <LoaderCircle size={14} className="animate-spin" />
                ) : (
                  <LogOut size={14} />
                )}
                <span className="text-[10px] font-medium">
                  {loggingOut ? '正在退出…' : '退出登录'}
                </span>
              </DropdownMenu.Item>
            ) : (
              <div className="flex items-start gap-2.5 px-2.5 py-2.5">
                <KeyRound
                  size={13}
                  className="mt-0.5 shrink-0 text-text-muted"
                />
                <span>
                  <span className="block text-[9px] font-medium text-text-secondary">
                    本地开发身份
                  </span>
                  <span className="mt-0.5 block text-[8px] leading-4 text-text-muted">
                    由环境变量提供；生产环境不启用此模式。
                  </span>
                </span>
              </div>
            )}
          </div>

          {logoutError && (
            <div
              role="alert"
              className="mt-1.5 flex gap-2 border-t border-danger/25 bg-danger/8 px-2.5 py-2 text-[9px] leading-4 text-danger"
            >
              <TriangleAlert size={12} className="mt-0.5 shrink-0" />
              <span>退出失败：{logoutError}</span>
            </div>
          )}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}

function MenuItem({
  icon: Icon,
  label,
  description,
  onSelect,
}: {
  icon: typeof Settings;
  label: string;
  description: string;
  onSelect: () => void;
}) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className="grid min-h-[48px] grid-cols-[28px_minmax(0,1fr)] items-center gap-2.5 px-2.5 outline-none transition-colors data-[highlighted]:bg-surface-2"
    >
      <span className="flex h-7 w-7 items-center justify-center border border-border-subtle text-text-muted">
        <Icon size={14} />
      </span>
      <span className="min-w-0">
        <span className="block text-[10px] font-medium text-text-primary">
          {label}
        </span>
        <span className="mt-0.5 block truncate text-[9px] text-text-muted">
          {description}
        </span>
      </span>
    </DropdownMenu.Item>
  );
}
