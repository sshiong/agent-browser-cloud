import { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  FileKey2,
  Fingerprint,
  RefreshCw,
  Search,
  ShieldCheck,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { useAuditEvents } from './platformQueries';
import { cn } from '@/shared/lib/utils';

export function SecurityPage() {
  const query = useAuditEvents();
  const [search, setSearch] = useState('');
  const events = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return query.data?.items ?? [];
    return (query.data?.items ?? []).filter((event) =>
      [
        event.eventType,
        event.action,
        event.actorId,
        event.sessionId,
        event.result,
      ]
        .filter(Boolean)
        .some((value) => value?.toLowerCase().includes(needle))
    );
  }, [query.data?.items, search]);
  const securityEvents =
    query.data?.items.filter((event) =>
      /SECURITY|PROMPT|AUTH|ADMIN|KEY/.test(event.eventType)
    ).length ?? 0;
  const humanEvents =
    query.data?.items.filter((event) => event.eventType === 'HUMAN_GOVERNANCE')
      .length ?? 0;

  return (
    <div>
      <TopContextBar
        title="安全中心"
        subtitle="租户隔离的审计证据、治理动作与哈希链完整性"
      />
      <main className="p-4 sm:p-6">
        <section className="grid border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-4">
          <Signal
            icon={query.data?.chainValid ? ShieldCheck : AlertTriangle}
            label="审计链"
            value={query.data?.chainValid ? '完整' : '待验证'}
            tone={query.data?.chainValid ? 'success' : 'warning'}
          />
          <Signal
            icon={Fingerprint}
            label="链上事件"
            value={String(query.data?.total ?? 0)}
          />
          <Signal
            icon={FileKey2}
            label="安全 / 管理事件"
            value={String(securityEvents)}
            tone="warning"
          />
          <Signal
            icon={CheckCircle2}
            label="人工治理"
            value={String(humanEvents)}
          />
        </section>

        <section className="mt-4 border border-border-subtle bg-surface-1">
          <div className="flex flex-col gap-3 border-b border-border-subtle bg-surface-2 px-4 py-3 md:flex-row md:items-center md:justify-between">
            <label className="relative block w-full md:max-w-[380px]">
              <Search
                size={14}
                className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
              />
              <span className="sr-only">搜索审计事件</span>
              <input
                className="field-input pl-9"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="事件、动作、Actor 或 Session"
              />
            </label>
            <div className="flex min-w-0 items-center gap-3">
              <span
                className={cn(
                  'h-2 w-2 shrink-0 rounded-full',
                  query.data?.chainValid ? 'bg-success' : 'bg-warning'
                )}
              />
              <code
                className="max-w-[240px] truncate text-[10px] text-text-muted"
                title={query.data?.headHash ?? undefined}
              >
                HEAD {query.data?.headHash ?? '尚未形成'}
              </code>
              <button
                type="button"
                onClick={() => query.refetch()}
                className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[11px] text-text-secondary hover:text-text-primary"
              >
                <RefreshCw size={12} />
                刷新
              </button>
            </div>
          </div>

          {query.isLoading ? (
            <LoadingRows />
          ) : query.isError ? (
            <ErrorState
              error={query.error}
              onRetry={() => query.refetch()}
              title="无法读取安全审计"
            />
          ) : events.length === 0 ? (
            <EmptyState
              title={search ? '没有匹配的审计事件' : '审计链尚未产生事件'}
              description="执行 Session、Agent 或人工治理操作后，脱敏证据会追加到租户审计链。"
            />
          ) : (
            <div className="divide-y divide-border-subtle">
              {events.map((event) => (
                <article
                  key={event.eventId}
                  className="grid gap-2 px-4 py-3 hover:bg-surface-2/60 md:grid-cols-[150px_1fr_150px_120px]"
                >
                  <div>
                    <p className="font-mono text-[10px] text-text-muted">
                      #{event.sequenceNo} ·{' '}
                      {new Date(event.createdAt).toLocaleTimeString()}
                    </p>
                    <p className="mt-1 truncate font-mono text-[10px] text-text-muted">
                      {event.eventId}
                    </p>
                  </div>
                  <div className="min-w-0">
                    <p className="truncate text-[12px] font-medium text-text-primary">
                      {event.eventType}
                      <span className="mx-2 text-border-strong">/</span>
                      <span className="font-mono text-accent">
                        {event.action}
                      </span>
                    </p>
                    <p className="mt-1 truncate text-[10px] text-text-muted">
                      {event.sessionId ?? event.resourceId ?? 'tenant scope'}
                    </p>
                  </div>
                  <div className="text-[11px] text-text-secondary">
                    <p>{event.actorType}</p>
                    <p className="truncate font-mono text-[10px] text-text-muted">
                      {event.actorId ?? 'system'}
                    </p>
                  </div>
                  <div className="flex items-start justify-between gap-2 md:justify-end">
                    <span className="bg-success/12 px-2 py-0.5 text-[10px] font-semibold text-success">
                      {event.result}
                    </span>
                    {event.legalHold && (
                      <span className="bg-warning/12 px-2 py-0.5 text-[10px] text-warning">
                        HOLD
                      </span>
                    )}
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function Signal({
  icon: Icon,
  label,
  value,
  tone = 'accent',
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>;
  label: string;
  value: string;
  tone?: 'accent' | 'success' | 'warning';
}) {
  const tones = {
    accent: 'text-accent',
    success: 'text-success',
    warning: 'text-warning',
  };
  return (
    <div className="flex min-h-24 items-center gap-3 bg-surface-1 px-4 py-4">
      <Icon size={17} className={tones[tone]} />
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          {label}
        </p>
        <p className="mt-1 font-mono text-[18px] font-semibold text-text-primary">
          {value}
        </p>
      </div>
    </div>
  );
}
