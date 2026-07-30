import { User, Wifi, LogOut } from 'lucide-react';
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus';
import { useAuth } from '@/auth/AuthProvider';
import { GlobalSearchDialog } from '@/features/search/GlobalSearchDialog';
import { WorkspaceNotificationCenter } from '@/features/notifications/WorkspaceNotificationCenter';
import { ThemeSwitcher } from '@/features/theme/ThemeSwitcher';

interface TopContextBarProps {
  title: string;
  subtitle?: string;
  globalOnly?: boolean;
}

export function TopContextBar({
  title,
  subtitle,
  globalOnly = false,
}: TopContextBarProps) {
  const isOnline = useOnlineStatus();
  const auth = useAuth();

  return (
    <header className="flex h-[56px] items-center justify-between gap-2 border-b border-border-subtle bg-surface-1 px-3 sm:px-6">
      <div className="flex min-w-0 items-center gap-3">
        {globalOnly && (
          <span className="hidden h-5 items-center border-r border-border-subtle pr-3 font-mono text-[10px] uppercase tracking-[0.16em] text-text-muted lg:flex">
            Workspace
          </span>
        )}
        <div className="min-w-0">
          {globalOnly ? (
            <p className="truncate text-[12px] font-medium text-text-secondary">
              {title}
            </p>
          ) : (
            <h1 className="truncate text-[15px] font-semibold text-text-primary">
              {title}
            </h1>
          )}
          {subtitle && (
            <p className="hidden truncate text-[11px] text-text-muted sm:block">
              {subtitle}
            </p>
          )}
        </div>
      </div>

      {/* Right: Actions */}
      <div className="flex shrink-0 items-center gap-1 sm:gap-2">
        {/* Connection Status */}
        <div className="flex items-center gap-1.5 rounded-md border border-border-subtle px-2.5 py-1.5">
          {isOnline ? (
            <Wifi size={12} className="text-success" />
          ) : (
            <span className="h-1.5 w-1.5 rounded-full bg-danger" />
          )}
          <span className="hidden text-[11px] text-text-secondary sm:inline">
            {isOnline ? 'Control Plane 在线' : '网络离线'}
          </span>
        </div>

        <GlobalSearchDialog />

        <WorkspaceNotificationCenter />

        <ThemeSwitcher />

        <div
          className="hidden max-w-[230px] items-center gap-2 border-l border-border-subtle pl-3 sm:flex"
          title={`${auth.identity?.actorId} · ${auth.identity?.roles.join(', ')}`}
        >
          <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-accent/15 text-accent">
            <User size={14} />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-[11px] text-text-secondary">
              {auth.identity?.actorId}
            </span>
            <span className="block truncate font-mono text-[9px] text-text-muted">
              {auth.identity?.tenantId}
            </span>
          </span>
          {auth.mode === 'oidc' && (
            <button
              type="button"
              onClick={() => void auth.logout()}
              aria-label="退出登录"
              title="退出登录"
              className="flex h-8 w-8 shrink-0 items-center justify-center text-text-muted hover:text-danger"
            >
              <LogOut size={14} />
            </button>
          )}
        </div>
      </div>
    </header>
  );
}
