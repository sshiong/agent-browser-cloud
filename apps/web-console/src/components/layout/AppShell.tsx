import { Outlet } from 'react-router';
import { Sidebar } from './Sidebar';
import { WifiOff } from 'lucide-react';
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus';

export function AppShell() {
  const isOnline = useOnlineStatus();

  return (
    <div className="flex h-screen overflow-hidden bg-canvas">
      <a
        href="#main-content"
        className="fixed left-3 top-3 z-50 -translate-y-20 bg-accent px-3 py-2 text-[12px] font-semibold text-canvas focus:translate-y-0"
      >
        跳到主要内容
      </a>
      <Sidebar />
      <div className="flex min-w-0 flex-1 flex-col">
        {!isOnline && (
          <div
            className="flex h-9 shrink-0 items-center justify-center gap-2 border-b border-danger/25 bg-danger/10 px-4 text-[11px] text-danger"
            role="status"
          >
            <WifiOff size={13} />
            网络已断开。只读缓存可能过期，危险写操作将在连接恢复前失败。
          </div>
        )}
        <main
          id="main-content"
          tabIndex={-1}
          className="min-w-0 flex-1 overflow-y-auto bg-grid"
        >
          <Outlet />
        </main>
      </div>
    </div>
  );
}
