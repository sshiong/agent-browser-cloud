import { Ban, Cable, Network, ShieldCheck } from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { useProxyOverview } from '@/features/proxies/proxyQueries';
import { cn } from '@/shared/lib/utils';
import type { ProxyAllocationView } from '@/types/proxy';

export function ProxiesPage() {
  const query = useProxyOverview();
  const provider = query.data?.provider;
  const active =
    query.data?.allocations.filter((item) =>
      ['ALLOCATED', 'BOUND'].includes(item.state)
    ).length ?? 0;
  const verified =
    query.data?.allocations.filter((item) => item.state === 'BOUND').length ??
    0;

  return (
    <div>
      <TopContextBar
        title="代理与出口"
        subtitle="Static Provider、Session Allocation 与已验证出口"
      />

      <main className="p-4 sm:p-6">
        {query.isError ? (
          <section className="border border-border-subtle bg-surface-1">
            <ErrorState
              error={query.error}
              onRetry={() => query.refetch()}
              title="无法加载代理状态"
            />
          </section>
        ) : (
          <>
            <section className="mb-4 grid border border-border-subtle bg-border-subtle sm:grid-cols-3">
              <Metric
                icon={<Cable size={15} />}
                label="Active allocations"
                value={String(active)}
              />
              <Metric
                icon={<ShieldCheck size={15} />}
                label="Verified exits"
                value={String(verified)}
              />
              <Metric
                icon={<Ban size={15} />}
                label="Direct fallback"
                value={
                  provider?.directFallbackAllowed ? 'LOCAL OVERRIDE' : 'DENIED'
                }
                warning={Boolean(provider?.directFallbackAllowed)}
              />
            </section>

            <section className="mb-4 border border-border-subtle bg-surface-1">
              <div className="flex flex-col gap-4 p-4 sm:flex-row sm:items-center sm:justify-between">
                <div className="flex min-w-0 items-start gap-3">
                  <span className="flex h-9 w-9 shrink-0 items-center justify-center bg-accent-soft text-accent">
                    <Network size={17} />
                  </span>
                  <div className="min-w-0">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="font-mono text-[13px] font-semibold text-text-primary">
                        {provider?.providerId ?? 'Loading provider'}
                      </h2>
                      {provider && (
                        <span
                          className={cn(
                            'border px-2 py-0.5 text-[10px] font-semibold',
                            provider.state === 'CONFIGURED'
                              ? 'border-success/25 bg-success/10 text-success'
                              : 'border-warning/25 bg-warning/10 text-warning'
                          )}
                        >
                          {provider.state}
                        </span>
                      )}
                    </div>
                    <p className="mt-1 truncate font-mono text-[11px] text-text-muted">
                      {provider?.endpoint || '未配置 Static Proxy Endpoint'}
                    </p>
                  </div>
                </div>
                {provider && (
                  <dl className="grid grid-cols-2 gap-x-8 gap-y-2 text-[11px]">
                    <div>
                      <dt className="text-text-muted">Protocol</dt>
                      <dd className="font-mono text-text-secondary">
                        {provider.type}
                      </dd>
                    </div>
                    <div>
                      <dt className="text-text-muted">Expected exit</dt>
                      <dd className="font-mono text-text-secondary">
                        {provider.expectedExitIp || '—'}
                      </dd>
                    </div>
                  </dl>
                )}
              </div>
            </section>

            <section className="overflow-hidden border border-border-subtle bg-surface-1">
              <div className="border-b border-border-subtle bg-surface-2 px-4 py-3">
                <h2 className="text-[12px] font-semibold text-text-primary">
                  Allocation ledger
                </h2>
                <p className="mt-0.5 text-[10px] text-text-muted">
                  Runtime 启动前分配，Node 出口验证后进入 BOUND，停止后自动
                  RELEASED。
                </p>
              </div>
              {query.isLoading ? (
                <LoadingRows rows={5} />
              ) : !query.data?.allocations.length ? (
                <EmptyState
                  title="尚无代理分配"
                  description="Session 启动时会自动申请 Static Proxy；未验证出口前 Runtime 不会启动。"
                />
              ) : (
                <>
                  <div className="hidden overflow-x-auto md:block">
                    <table className="w-full min-w-[920px]">
                      <thead>
                        <tr className="border-b border-border-subtle bg-surface-2">
                          {[
                            'Allocation',
                            'Session',
                            'State',
                            'Observed exit',
                            'Location / ASN',
                            'Verified',
                          ].map((label) => (
                            <th
                              key={label}
                              className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted"
                            >
                              {label}
                            </th>
                          ))}
                        </tr>
                      </thead>
                      <tbody>
                        {query.data.allocations.map((allocation) => (
                          <AllocationRow
                            key={allocation.allocationId}
                            allocation={allocation}
                          />
                        ))}
                      </tbody>
                    </table>
                  </div>
                  <div className="divide-y divide-border-subtle md:hidden">
                    {query.data.allocations.map((allocation) => (
                      <AllocationCard
                        key={allocation.allocationId}
                        allocation={allocation}
                      />
                    ))}
                  </div>
                </>
              )}
            </section>
          </>
        )}
      </main>
    </div>
  );
}

function Metric({
  icon,
  label,
  value,
  warning = false,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  warning?: boolean;
}) {
  return (
    <div className="flex min-h-20 items-center gap-3 bg-surface-1 px-4 py-3">
      <span
        className={cn(
          'flex h-8 w-8 items-center justify-center',
          warning ? 'bg-warning/10 text-warning' : 'bg-accent-soft text-accent'
        )}
      >
        {icon}
      </span>
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          {label}
        </p>
        <p
          className={cn(
            'mt-0.5 font-mono text-[15px] font-semibold',
            warning ? 'text-warning' : 'text-text-primary'
          )}
        >
          {value}
        </p>
      </div>
    </div>
  );
}

function AllocationRow({ allocation }: { allocation: ProxyAllocationView }) {
  return (
    <tr className="border-b border-border-subtle last:border-b-0 hover:bg-surface-2/60">
      <MonoCell value={allocation.allocationId} />
      <MonoCell value={allocation.sessionId} />
      <td className="px-4 py-3">
        <StateChip state={allocation.state} />
      </td>
      <MonoCell value={allocation.exitIp ?? '—'} />
      <td className="px-4 py-3 text-[11px] text-text-secondary">
        {allocation.country && allocation.asn
          ? `${allocation.country} / ${allocation.asn}`
          : '尚未验证'}
      </td>
      <td className="px-4 py-3 text-[11px] text-text-muted">
        {allocation.verifiedAt ? formatDate(allocation.verifiedAt) : '—'}
      </td>
    </tr>
  );
}

function AllocationCard({ allocation }: { allocation: ProxyAllocationView }) {
  return (
    <article className="p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate font-mono text-[11px] text-text-primary">
            {allocation.allocationId}
          </p>
          <p className="truncate font-mono text-[10px] text-text-muted">
            {allocation.sessionId}
          </p>
        </div>
        <StateChip state={allocation.state} />
      </div>
      <p className="mt-3 font-mono text-[12px] text-text-secondary">
        {allocation.exitIp ?? 'Exit pending'}
      </p>
      <p className="mt-1 text-[10px] text-text-muted">
        {allocation.country && allocation.asn
          ? `${allocation.country} / ${allocation.asn}`
          : 'Provider 尚未完成出口验证'}
      </p>
    </article>
  );
}

function MonoCell({ value }: { value: string }) {
  return (
    <td className="max-w-[220px] truncate px-4 py-3 font-mono text-[11px] text-text-secondary">
      {value}
    </td>
  );
}

function StateChip({ state }: { state: ProxyAllocationView['state'] }) {
  const colors = {
    ALLOCATED: 'border-warning/25 bg-warning/10 text-warning',
    BOUND: 'border-success/25 bg-success/10 text-success',
    RELEASED: 'border-border-default bg-surface-2 text-text-muted',
    FAILED: 'border-danger/25 bg-danger/10 text-danger',
  };
  return (
    <span
      className={cn(
        'inline-flex border px-2 py-0.5 font-mono text-[10px] font-semibold',
        colors[state]
      )}
    >
      {state}
    </span>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
