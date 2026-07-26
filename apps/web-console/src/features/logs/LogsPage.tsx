import { useState } from 'react';
import { Pause, Play, RefreshCw } from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { useAuditEvents } from '@/features/security/platformQueries';
import type { AuditEventView } from '@/types/platform';

export function LogsPage() {
  const query = useAuditEvents();
  const events = query.data?.items ?? [];
  const [pausedEvents, setPausedEvents] = useState<AuditEventView[] | null>(
    null
  );
  const paused = pausedEvents !== null;

  function togglePaused() {
    setPausedEvents((snapshot) => (snapshot === null ? [...events] : null));
  }

  return (
    <div>
      <TopContextBar
        title="事件流"
        subtitle="来自 Control Plane 的已提交、脱敏审计事件"
      />
      <main className="p-4 sm:p-6">
        <section className="overflow-hidden border border-border-subtle bg-surface-1">
          <header className="flex items-center justify-between border-b border-border-subtle bg-surface-2 px-4 py-3">
            <div className="flex items-center gap-2">
              <span className="h-2 w-2 rounded-full bg-success" />
              <span className="text-[11px] text-text-secondary">
                {paused ? '视图已暂停' : '每 5 秒同步'}
              </span>
            </div>
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={togglePaused}
                className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[11px] text-text-secondary"
              >
                {paused ? <Play size={12} /> : <Pause size={12} />}
                {paused ? '继续' : '暂停视图'}
              </button>
              <button
                type="button"
                onClick={() => query.refetch()}
                className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[11px] text-text-secondary"
              >
                <RefreshCw size={12} />
                刷新
              </button>
            </div>
          </header>
          {query.isLoading ? (
            <LoadingRows rows={8} />
          ) : query.isError ? (
            <ErrorState error={query.error} onRetry={() => query.refetch()} />
          ) : events.length === 0 ? (
            <EmptyState
              title="暂无已提交事件"
              description="事件流只展示已经进入租户审计链的真实操作，不生成占位日志。"
            />
          ) : (
            <div className="max-h-[calc(100vh-220px)] overflow-auto">
              {(pausedEvents ?? events).map((event) => (
                <div
                  key={event.eventId}
                  className="grid min-w-[780px] grid-cols-[90px_170px_150px_1fr_100px] items-center gap-4 border-b border-border-subtle px-4 py-2.5 hover:bg-surface-2/60"
                >
                  <time className="font-mono text-[10px] text-text-muted">
                    {new Date(event.createdAt).toLocaleTimeString()}
                  </time>
                  <span className="truncate text-[11px] font-medium text-text-primary">
                    {event.eventType}
                  </span>
                  <span className="truncate font-mono text-[10px] text-accent">
                    {event.action}
                  </span>
                  <span className="truncate font-mono text-[10px] text-text-muted">
                    {event.sessionId ?? event.resourceId ?? event.eventId}
                  </span>
                  <span className="text-right text-[10px] font-semibold text-success">
                    {event.result}
                  </span>
                </div>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}
