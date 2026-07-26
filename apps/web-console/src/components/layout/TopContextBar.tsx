import { Search, Bell, Sun, User, Wifi, LogOut } from 'lucide-react';
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus';
import { useAuth } from '@/auth/AuthProvider';

interface TopContextBarProps {
  title: string;
  subtitle?: string;
}

export function TopContextBar({ title, subtitle }: TopContextBarProps) {
  const isOnline = useOnlineStatus();
  const auth = useAuth();

  return (
    <header className="flex h-[56px] items-center justify-between gap-2 border-b border-border-subtle bg-surface-1 px-3 sm:px-6">
      {/* Left: Page Title */}
      <div className="min-w-0">
        <div className="min-w-0">
          <h1 className="truncate text-[15px] font-semibold text-text-primary">
            {title}
          </h1>
          {subtitle && (
            <p className="hidden truncate text-[12px] text-text-muted sm:block">
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
            {isOnline ? '网络在线' : '网络离线'}
          </span>
        </div>

        {/* Search */}
        <button
          type="button"
          aria-label="全局搜索（尚未实现）"
          title="全局搜索（尚未实现）"
          disabled
          className="hidden h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45 md:flex"
        >
          <Search size={16} />
        </button>

        {/* Notifications */}
        <button
          type="button"
          aria-label="通知中心（尚未实现）"
          title="通知中心（尚未实现）"
          disabled
          className="relative hidden h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45 md:flex"
        >
          <Bell size={16} />
          <span className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-danger" />
        </button>

        {/* Theme Toggle */}
        <button
          type="button"
          aria-label="浅色主题（尚未实现）"
          title="浅色主题（尚未实现）"
          disabled
          className="hidden h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45 md:flex"
        >
          <Sun size={16} />
        </button>

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
