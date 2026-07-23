import { Search, Bell, Sun, User, Wifi } from 'lucide-react';
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus';

interface TopContextBarProps {
  title: string;
  subtitle?: string;
}

export function TopContextBar({ title, subtitle }: TopContextBarProps) {
  const isOnline = useOnlineStatus();

  return (
    <header className="flex h-[56px] items-center justify-between border-b border-border-subtle bg-surface-1 px-6">
      {/* Left: Page Title */}
      <div className="flex items-center gap-4">
        <div>
          <h1 className="text-[15px] font-semibold text-text-primary">
            {title}
          </h1>
          {subtitle && (
            <p className="text-[12px] text-text-muted">{subtitle}</p>
          )}
        </div>
      </div>

      {/* Right: Actions */}
      <div className="flex items-center gap-2">
        {/* Connection Status */}
        <div className="flex items-center gap-1.5 rounded-md border border-border-subtle px-2.5 py-1.5">
          {isOnline ? (
            <Wifi size={12} className="text-success" />
          ) : (
            <span className="h-1.5 w-1.5 rounded-full bg-danger" />
          )}
          <span className="text-[11px] text-text-secondary">
            {isOnline ? '网络在线' : '网络离线'}
          </span>
        </div>

        {/* Search */}
        <button
          type="button"
          aria-label="全局搜索（尚未实现）"
          title="全局搜索（尚未实现）"
          disabled
          className="flex h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45"
        >
          <Search size={16} />
        </button>

        {/* Notifications */}
        <button
          type="button"
          aria-label="通知中心（尚未实现）"
          title="通知中心（尚未实现）"
          disabled
          className="relative flex h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45"
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
          className="flex h-8 w-8 cursor-not-allowed items-center justify-center rounded-md text-text-muted opacity-45"
        >
          <Sun size={16} />
        </button>

        {/* User Avatar */}
        <button
          type="button"
          aria-label="用户菜单（尚未实现）"
          title="用户菜单（尚未实现）"
          disabled
          className="flex h-8 w-8 cursor-not-allowed items-center justify-center rounded-full bg-accent/15 text-accent opacity-60"
        >
          <User size={14} />
        </button>
      </div>
    </header>
  );
}
