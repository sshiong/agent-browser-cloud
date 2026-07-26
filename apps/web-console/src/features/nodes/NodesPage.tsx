import { Activity, Boxes, Gauge, ShieldCheck } from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import { HealthChip } from '@/components/ui/StatusChip';
import { useBrowserNodes } from './capacityQueries';
import { cn } from '@/shared/lib/utils';
import type { BrowserNodeView } from '@/types/capacity';
import type { HealthStatus } from '@/types';

export function NodesPage() {
  const query = useBrowserNodes();
  const nodes = query.data?.items ?? [];
  const ready = nodes.filter(
    (node) =>
      node.lifecycleState === 'READY' &&
      node.admissionState === 'OPEN' &&
      node.pressureState === 'NORMAL' &&
      !isHeartbeatStale(node)
  ).length;
  const reservedMemory = nodes.reduce(
    (total, node) => total + node.reservedMemoryMib,
    0
  );
  const certifiedMemory = nodes.reduce(
    (total, node) => total + node.certifiedMemoryMib,
    0
  );

  return (
    <div>
      <TopContextBar
        title="Browser Node"
        subtitle="认证容量、Placement 预留、PSI 压力与 Admission 状态"
      />
      <main className="p-4 sm:p-6">
        <section className="grid border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-4">
          <Metric
            icon={Boxes}
            label="Registered"
            value={String(nodes.length)}
          />
          <Metric
            icon={ShieldCheck}
            label="Admission open"
            value={`${ready}/${nodes.length}`}
            tone={ready === nodes.length ? 'success' : 'warning'}
          />
          <Metric
            icon={Activity}
            label="Active sessions"
            value={String(
              nodes.reduce((total, node) => total + node.activeSessions, 0)
            )}
          />
          <Metric
            icon={Gauge}
            label="Memory reserved"
            value={
              certifiedMemory > 0
                ? `${Math.round((reservedMemory / certifiedMemory) * 100)}%`
                : '0%'
            }
          />
        </section>

        <section className="mt-4">
          {query.isLoading ? (
            <div className="border border-border-subtle bg-surface-1">
              <LoadingPanel label="正在读取 Browser Node 容量" />
            </div>
          ) : query.isError ? (
            <div className="border border-border-subtle bg-surface-1">
              <ErrorState
                error={query.error}
                onRetry={() => query.refetch()}
                title="无法加载 Browser Node"
              />
            </div>
          ) : nodes.length === 0 ? (
            <div className="border border-border-subtle bg-surface-1">
              <EmptyState
                title="没有已注册的 Browser Node"
                description="生产 Node 必须通过 mTLS Capacity Heartbeat 登记认证容量，未登记节点不会参与 Placement。"
              />
            </div>
          ) : (
            <div className="grid gap-4 lg:grid-cols-2 2xl:grid-cols-3">
              {nodes.map((node) => (
                <NodeCard key={node.nodeId} node={node} />
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function NodeCard({ node }: { node: BrowserNodeView }) {
  const usablePercent = 100 - node.safetyMarginPercent;
  const memoryPercent = percent(
    node.reservedMemoryMib,
    node.certifiedMemoryMib
  );
  const cpuPercent = percent(node.reservedCpuMillis, node.certifiedCpuMillis);
  const pidPercent = percent(node.reservedPidCount, node.certifiedPidCount);
  const status = nodeHealth(node);

  return (
    <article className="border border-border-subtle bg-surface-1 p-4 sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate font-mono text-[13px] font-semibold text-text-primary">
            {node.nodeId}
          </h2>
          <p className="mt-1 truncate font-mono text-[10px] text-text-muted">
            {node.region} · {node.grpcTarget}
          </p>
        </div>
        <HealthChip status={status} />
      </div>

      <div className="mt-4 grid grid-cols-3 gap-px bg-border-subtle">
        <SmallStat label="Admission" value={node.admissionState} />
        <SmallStat label="Pressure" value={node.pressureState} />
        <SmallStat
          label="Sessions"
          value={`${node.activeSessions}/${node.maxSessions}`}
        />
      </div>

      <div className="mt-4 space-y-3">
        <CapacityBar
          label="CPU reservation"
          value={node.reservedCpuMillis}
          limit={node.certifiedCpuMillis}
          unit="m"
          percentValue={cpuPercent}
          safetyLimit={usablePercent}
        />
        <CapacityBar
          label="Memory reservation"
          value={node.reservedMemoryMib}
          limit={node.certifiedMemoryMib}
          unit=" MiB"
          percentValue={memoryPercent}
          safetyLimit={usablePercent}
        />
        <CapacityBar
          label="PID reservation"
          value={node.reservedPidCount}
          limit={node.certifiedPidCount}
          unit=""
          percentValue={pidPercent}
          safetyLimit={usablePercent}
        />
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-border-subtle pt-3 text-[10px]">
        <KeyValue
          label="Memory PSI some/full"
          value={`${node.memoryPsiSomeAvg10.toFixed(2)} / ${node.memoryPsiFullAvg10.toFixed(2)}`}
        />
        <KeyValue
          label="CPU / IO PSI"
          value={`${node.cpuPsiSomeAvg10.toFixed(2)} / ${node.ioPsiFullAvg10.toFixed(2)}`}
        />
        <KeyValue
          label="Isolation"
          value={node.isolationCapable ? 'CGROUP READY' : 'UNAVAILABLE'}
        />
        <KeyValue
          label="Heartbeat"
          value={relativeTime(node.lastHeartbeatAt)}
          warning={isHeartbeatStale(node)}
        />
      </dl>
      {node.pressureReason && (
        <p className="mt-3 border border-warning/25 bg-warning/8 px-3 py-2 font-mono text-[10px] text-warning">
          {node.pressureReason}
        </p>
      )}
    </article>
  );
}

function CapacityBar({
  label,
  value,
  limit,
  unit,
  percentValue,
  safetyLimit,
}: {
  label: string;
  value: number;
  limit: number;
  unit: string;
  percentValue: number;
  safetyLimit: number;
}) {
  return (
    <div>
      <div className="mb-1 flex items-center justify-between gap-3 text-[10px]">
        <span className="text-text-muted">{label}</span>
        <span className="font-mono text-text-secondary">
          {value}
          {unit} / {limit}
          {unit}
        </span>
      </div>
      <div className="relative h-1.5 overflow-hidden bg-surface-3">
        <span
          className="absolute inset-y-0 w-px bg-warning/70"
          style={{ left: `${safetyLimit}%` }}
        />
        <span
          className={cn(
            'block h-full',
            percentValue >= safetyLimit
              ? 'bg-danger'
              : percentValue >= safetyLimit - 15
                ? 'bg-warning'
                : 'bg-accent'
          )}
          style={{ width: `${Math.min(percentValue, 100)}%` }}
        />
      </div>
    </div>
  );
}

function Metric({
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
  return (
    <div className="flex min-h-20 items-center gap-3 bg-surface-1 px-4 py-3">
      <Icon
        size={16}
        className={
          tone === 'success'
            ? 'text-success'
            : tone === 'warning'
              ? 'text-warning'
              : 'text-accent'
        }
      />
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          {label}
        </p>
        <p className="mt-1 font-mono text-[17px] font-semibold text-text-primary">
          {value}
        </p>
      </div>
    </div>
  );
}

function SmallStat({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface-2 px-3 py-2">
      <p className="text-[9px] uppercase tracking-[0.1em] text-text-muted">
        {label}
      </p>
      <p className="mt-1 truncate font-mono text-[10px] font-semibold text-text-primary">
        {value}
      </p>
    </div>
  );
}

function KeyValue({
  label,
  value,
  warning = false,
}: {
  label: string;
  value: string;
  warning?: boolean;
}) {
  return (
    <div>
      <dt className="text-text-muted">{label}</dt>
      <dd
        className={cn(
          'mt-0.5 font-mono',
          warning ? 'text-warning' : 'text-text-secondary'
        )}
      >
        {value}
      </dd>
    </div>
  );
}

function percent(value: number, limit: number) {
  return limit > 0 ? Math.round((value / limit) * 100) : 0;
}

function isHeartbeatStale(node: BrowserNodeView) {
  return Date.now() - new Date(node.lastHeartbeatAt).getTime() > 60_000;
}

function nodeHealth(node: BrowserNodeView): HealthStatus {
  if (node.pressureState === 'CRITICAL') return 'critical';
  if (
    node.pressureState === 'DEGRADED' ||
    node.admissionState !== 'OPEN' ||
    node.lifecycleState !== 'READY' ||
    isHeartbeatStale(node)
  ) {
    return 'warning';
  }
  return 'healthy';
}

function relativeTime(value: string) {
  const seconds = Math.max(
    0,
    Math.round((Date.now() - new Date(value).getTime()) / 1000)
  );
  return seconds < 60 ? `${seconds}s ago` : `${Math.floor(seconds / 60)}m ago`;
}
