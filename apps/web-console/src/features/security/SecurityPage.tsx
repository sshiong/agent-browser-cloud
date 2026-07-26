import { useMemo, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  FileKey2,
  Fingerprint,
  LockKeyhole,
  Plus,
  RefreshCw,
  Search,
  ShieldCheck,
  X,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import {
  useAuditEvents,
  useBreakGlassRequests,
  useCreateBreakGlassRequest,
  useTransitionBreakGlassRequest,
} from './platformQueries';
import { cn } from '@/shared/lib/utils';
import { DEFAULT_ACTOR_ID, DEFAULT_TENANT_ID } from '@/api/session';
import type {
  BreakGlassRequestView,
  CreateBreakGlassRequest,
} from '@/types/platform';

export function SecurityPage() {
  const query = useAuditEvents();
  const breakGlass = useBreakGlassRequests();
  const createBreakGlass = useCreateBreakGlassRequest();
  const transitionBreakGlass = useTransitionBreakGlassRequest();
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

        <BreakGlassWorkspace
          items={breakGlass.data?.items ?? []}
          loading={breakGlass.isLoading}
          error={breakGlass.error}
          mutationError={createBreakGlass.error ?? transitionBreakGlass.error}
          creating={createBreakGlass.isPending}
          transitioning={transitionBreakGlass.isPending}
          onCreate={(input) => createBreakGlass.mutateAsync(input)}
          onTransition={(requestId, transition) =>
            transitionBreakGlass.mutateAsync({ requestId, transition })
          }
          onRetry={() => breakGlass.refetch()}
        />

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

function BreakGlassWorkspace({
  items,
  loading,
  error,
  mutationError,
  creating,
  transitioning,
  onCreate,
  onTransition,
  onRetry,
}: {
  items: BreakGlassRequestView[];
  loading: boolean;
  error: Error | null;
  mutationError: Error | null;
  creating: boolean;
  transitioning: boolean;
  onCreate: (input: CreateBreakGlassRequest) => Promise<unknown>;
  onTransition: (
    requestId: string,
    transition: 'approve' | 'reject' | 'revoke' | 'review'
  ) => Promise<unknown>;
  onRetry: () => void;
}) {
  const [requesting, setRequesting] = useState(false);
  const [form, setForm] = useState<CreateBreakGlassRequest>({
    ticketId: '',
    reason: '',
    resourceType: 'TENANT',
    resourceId: DEFAULT_TENANT_ID,
    requestedScope: 'INCIDENT_RESPONSE',
    durationMinutes: 30,
  });

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    await onCreate(form);
    setRequesting(false);
    setForm((current) => ({ ...current, ticketId: '', reason: '' }));
  }

  return (
    <section className="mt-4 border border-border-subtle bg-surface-1">
      <header className="flex flex-col gap-3 border-b border-border-subtle bg-surface-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <LockKeyhole size={15} className="text-warning" />
          <div>
            <h2 className="text-[12px] font-semibold text-text-primary">
              Break-glass 紧急访问
            </h2>
            <p className="mt-0.5 text-[10px] text-text-muted">
              双人审批 · 最长 60 分钟 · 自动撤销 · 全链路审计
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setRequesting((value) => !value)}
          className="inline-flex h-8 items-center justify-center gap-1.5 border border-warning/40 px-3 text-[11px] font-medium text-warning hover:bg-warning/8"
        >
          {requesting ? <X size={12} /> : <Plus size={12} />}
          {requesting ? '取消申请' : '申请紧急访问'}
        </button>
      </header>

      {requesting && (
        <form
          onSubmit={(event) => void submit(event)}
          className="grid gap-3 border-b border-border-subtle bg-warning/[0.025] p-4 lg:grid-cols-12"
        >
          <label className="lg:col-span-3">
            <span className="field-label">工单 ID</span>
            <input
              required
              className="field-input"
              value={form.ticketId}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  ticketId: event.target.value,
                }))
              }
              placeholder="INC-2026-001"
            />
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">资源类型</span>
            <select
              className="field-input"
              value={form.resourceType}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  resourceType: event.target
                    .value as CreateBreakGlassRequest['resourceType'],
                }))
              }
            >
              {['TENANT', 'SESSION', 'PROFILE', 'AUDIT', 'RUNTIME'].map(
                (value) => (
                  <option key={value}>{value}</option>
                )
              )}
            </select>
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">资源 ID</span>
            <input
              required
              className="field-input font-mono"
              value={form.resourceId}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  resourceId: event.target.value,
                }))
              }
            />
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">授权范围</span>
            <select
              className="field-input"
              value={form.requestedScope}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  requestedScope: event.target
                    .value as CreateBreakGlassRequest['requestedScope'],
                }))
              }
            >
              {[
                'INCIDENT_RESPONSE',
                'SECURE_DEBUG',
                'READ_SENSITIVE_STATE',
                'AUDIT_EXPORT',
              ].map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-9">
            <span className="field-label">访问原因（20–500 字符）</span>
            <input
              required
              minLength={20}
              maxLength={500}
              className="field-input"
              value={form.reason}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  reason: event.target.value,
                }))
              }
              placeholder="说明事件、排查目标和为何普通权限不足"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="field-label">分钟</span>
            <input
              type="number"
              min={5}
              max={60}
              required
              className="field-input"
              value={form.durationMinutes}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  durationMinutes: Number(event.target.value),
                }))
              }
            />
          </label>
          <div className="flex items-end lg:col-span-2">
            <button
              type="submit"
              disabled={creating}
              className="h-9 w-full bg-warning px-3 text-[11px] font-semibold text-surface-0 disabled:opacity-50"
            >
              {creating ? '提交中…' : '提交双人审批'}
            </button>
          </div>
        </form>
      )}

      {mutationError && (
        <p
          role="alert"
          className="border-b border-danger/30 bg-danger/8 px-4 py-2 text-[11px] text-danger"
        >
          {mutationError.message}
        </p>
      )}

      {loading ? (
        <LoadingRows rows={3} />
      ) : error ? (
        <ErrorState
          error={error}
          onRetry={onRetry}
          title="无法读取 Break-glass 请求"
        />
      ) : items.length === 0 ? (
        <EmptyState
          title="没有紧急访问请求"
          description="普通运维路径不需要 Break-glass；只有生产事件处理才应发起限时授权。"
        />
      ) : (
        <div className="divide-y divide-border-subtle">
          {items.map((item) => (
            <BreakGlassRow
              key={item.requestId}
              item={item}
              busy={transitioning}
              onTransition={onTransition}
            />
          ))}
        </div>
      )}
    </section>
  );
}

function BreakGlassRow({
  item,
  busy,
  onTransition,
}: {
  item: BreakGlassRequestView;
  busy: boolean;
  onTransition: (
    requestId: string,
    transition: 'approve' | 'reject' | 'revoke' | 'review'
  ) => Promise<unknown>;
}) {
  const stateTone = {
    REQUESTED: 'text-warning',
    ACTIVE: 'text-danger',
    REJECTED: 'text-text-muted',
    REVOKED: 'text-text-muted',
    EXPIRED: 'text-text-muted',
  }[item.state];
  const ownRequest = item.requestedBy === DEFAULT_ACTOR_ID;

  return (
    <article className="grid gap-3 px-4 py-3 lg:grid-cols-[170px_1fr_180px_auto] lg:items-center">
      <div>
        <p className="font-mono text-[10px] text-accent">{item.ticketId}</p>
        <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
          {item.requestId}
        </p>
      </div>
      <div className="min-w-0">
        <p className="truncate text-[11px] text-text-primary">
          {item.resourceType}/{item.resourceId}
          <span className="mx-2 text-border-strong">·</span>
          <span className="font-mono text-warning">{item.requestedScope}</span>
        </p>
        <p className="mt-1 truncate text-[10px] text-text-muted">
          {item.reason}
        </p>
      </div>
      <div className="text-[10px] text-text-muted">
        <p>
          申请 {item.requestedBy}
          {item.approvedBy ? ` · 审批 ${item.approvedBy}` : ''}
        </p>
        <p className="mt-1 font-mono">
          到期 {new Date(item.expiresAt).toLocaleTimeString()}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2 lg:justify-end">
        <span className={cn('font-mono text-[10px] font-semibold', stateTone)}>
          {item.state}
        </span>
        {item.state === 'REQUESTED' && !ownRequest && (
          <>
            <ActionButton
              label="审批"
              disabled={busy}
              onClick={() => onTransition(item.requestId, 'approve')}
            />
            <ActionButton
              label="拒绝"
              disabled={busy}
              onClick={() => onTransition(item.requestId, 'reject')}
            />
          </>
        )}
        {item.state === 'REQUESTED' && ownRequest && (
          <span className="text-[9px] text-text-muted">等待另一位管理员</span>
        )}
        {item.state === 'ACTIVE' && (
          <ActionButton
            label="立即撤销"
            disabled={busy}
            danger
            onClick={() => onTransition(item.requestId, 'revoke')}
          />
        )}
        {['REJECTED', 'REVOKED', 'EXPIRED'].includes(item.state) &&
          !item.reviewedAt && (
            <ActionButton
              label="完成复核"
              disabled={busy}
              onClick={() => onTransition(item.requestId, 'review')}
            />
          )}
        {item.reviewedAt && (
          <span className="text-[9px] text-success">REVIEWED</span>
        )}
      </div>
    </article>
  );
}

function ActionButton({
  label,
  disabled,
  danger = false,
  onClick,
}: {
  label: string;
  disabled: boolean;
  danger?: boolean;
  onClick: () => Promise<unknown>;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={() => void onClick()}
      className={cn(
        'h-7 border px-2 text-[10px] disabled:opacity-50',
        danger
          ? 'border-danger/40 text-danger hover:bg-danger/8'
          : 'border-border-default text-text-secondary hover:text-text-primary'
      )}
    >
      {label}
    </button>
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
