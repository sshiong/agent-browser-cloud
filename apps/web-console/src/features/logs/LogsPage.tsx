import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';
import { timelineEvents } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const severityColors: Record<string, string> = {
  info: 'text-accent',
  warning: 'text-warning',
  error: 'text-danger',
  critical: 'text-danger',
};

const severityBg: Record<string, string> = {
  info: 'bg-accent/10',
  warning: 'bg-warning/10',
  error: 'bg-danger/10',
  critical: 'bg-danger/10',
};

export function LogsPage() {
  return (
    <div>
      <TopContextBar
        title="运行日志"
        subtitle="实时查看系统事件、错误与审计日志"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
            <div className="flex items-center justify-between border-b border-border-subtle bg-surface-2 px-4 py-3">
              <div className="flex items-center gap-2">
                <span className="h-2 w-2 rounded-full bg-success animate-pulse" />
                <span className="text-[12px] text-text-secondary">实时</span>
              </div>
              <div className="flex items-center gap-2">
                <button className="rounded-md bg-surface-3 px-2.5 py-1 text-[11px] text-text-muted transition-colors hover:text-text-secondary">
                  暂停
                </button>
                <button className="rounded-md bg-surface-3 px-2.5 py-1 text-[11px] text-text-muted transition-colors hover:text-text-secondary">
                  导出
                </button>
              </div>
            </div>
            <div className="max-h-[600px] overflow-y-auto">
              {timelineEvents.map((event) => (
                <div
                  key={event.id}
                  className="flex items-start gap-4 border-b border-border-subtle px-4 py-2.5 transition-colors hover:bg-surface-2"
                >
                  <span className="shrink-0 font-mono text-[11px] text-text-muted">
                    {event.time}
                  </span>
                  <span
                    className={cn(
                      'shrink-0 rounded px-1.5 py-0.5 text-[10px] font-medium uppercase',
                      severityBg[event.severity],
                      severityColors[event.severity]
                    )}
                  >
                    {event.severity}
                  </span>
                  <span className="shrink-0 w-[100px] text-[12px] text-text-secondary">
                    {event.component}
                  </span>
                  <span className="flex-1 text-[12px] text-text-primary">
                    {event.event}
                  </span>
                  {event.sessionName && (
                    <span className="shrink-0 text-[11px] text-text-muted">
                      {event.sessionName}
                    </span>
                  )}
                  {event.details && (
                    <span className="max-w-[300px] truncate font-mono text-[11px] text-text-muted">
                      {event.details}
                    </span>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
