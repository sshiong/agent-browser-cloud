import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';
import { extensions } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const securityColors: Record<string, string> = {
  standard: 'text-success bg-success/15',
  high_risk: 'text-warning bg-warning/15',
  privileged: 'text-danger bg-danger/15',
  unknown: 'text-text-muted bg-surface-3',
};

export function ExtensionsPage() {
  return (
    <div>
      <TopContextBar
        title="扩展与应用"
        subtitle="管理浏览器扩展、权限与资源消耗"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="grid grid-cols-4 gap-4">
            {extensions.map((ext) => (
              <div
                key={ext.id}
                className="group rounded-[10px] border border-border-subtle bg-surface-1 p-4 transition-colors hover:border-border-default"
              >
                <div className="mb-3 flex items-start justify-between">
                  <div className="flex h-10 w-10 items-center justify-center rounded-lg bg-surface-2 text-[20px]">
                    {ext.icon}
                  </div>
                  <span
                    className={cn(
                      'rounded-full px-2 py-0.5 text-[10px] font-medium',
                      securityColors[ext.securityClass]
                    )}
                  >
                    {ext.securityClass === 'standard' && '标准'}
                    {ext.securityClass === 'high_risk' && '高风险'}
                    {ext.securityClass === 'privileged' && '特权'}
                    {ext.securityClass === 'unknown' && '未知'}
                  </span>
                </div>
                <h4 className="text-[13px] font-medium text-text-primary">
                  {ext.name}
                </h4>
                <p className="mb-2 text-[11px] text-text-muted">
                  {ext.description}
                </p>
                <div className="flex items-center justify-between text-[11px]">
                  <span className="text-text-muted">v{ext.version}</span>
                  <span className="text-text-secondary">
                    {ext.installedSessions} 会话
                  </span>
                </div>
                <div className="mt-3 flex items-center justify-between">
                  <div className="flex items-center gap-1">
                    <span className="text-[10px] text-text-muted">资源</span>
                    <div className="h-1 w-16 overflow-hidden rounded-full bg-surface-3">
                      <div
                        className={cn(
                          'h-full rounded-full',
                          ext.resourceWeight > 30
                            ? 'bg-danger'
                            : ext.resourceWeight > 15
                              ? 'bg-warning'
                              : 'bg-accent'
                        )}
                        style={{
                          width: `${Math.min(ext.resourceWeight * 2, 100)}%`,
                        }}
                      />
                    </div>
                  </div>
                  <button
                    className={cn(
                      'rounded-md px-2.5 py-1 text-[11px] font-medium transition-colors',
                      ext.installed
                        ? 'bg-surface-3 text-text-secondary hover:bg-danger/15 hover:text-danger'
                        : 'bg-accent-soft text-accent hover:bg-accent/20'
                    )}
                  >
                    {ext.installed ? '卸载' : '安装'}
                  </button>
                </div>
              </div>
            ))}
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
