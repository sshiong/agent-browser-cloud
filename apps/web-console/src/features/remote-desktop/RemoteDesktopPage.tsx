import { TopContextBar } from '@/components/layout/TopContextBar';
import { Monitor } from 'lucide-react';

export function RemoteDesktopPage() {
  return (
    <div>
      <TopContextBar title="远程桌面" subtitle="连接和管理远程浏览器桌面会话" />

      <div className="flex h-[calc(100vh-56px-48px)] items-center justify-center">
        <div className="text-center">
          <Monitor size={48} className="mx-auto mb-4 text-text-muted" />
          <h3 className="text-[16px] font-medium text-text-primary">
            选择一个运行中的环境
          </h3>
          <p className="mt-2 text-[13px] text-text-muted">
            从环境管理页面选择一个运行中的 Session 来打开远程桌面
          </p>
        </div>
      </div>
    </div>
  );
}
