import { Activity, FlaskConical, Puzzle, ShieldAlert } from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import { useExtensionProfiles } from '@/features/nodes/capacityQueries';
import { cn } from '@/shared/lib/utils';
import type { ExtensionProfileView } from '@/types/capacity';

export function ExtensionsPage() {
  const query = useExtensionProfiles();
  const extensions = query.data?.items ?? [];
  const probation = extensions.filter(
    (extension) => extension.profileState === 'PROBATION'
  ).length;
  const privileged = extensions.filter(
    (extension) => extension.privileged
  ).length;
  const samples = extensions.reduce(
    (total, extension) => total + extension.samples,
    0
  );

  return (
    <div>
      <TopContextBar
        title="扩展资源画像"
        subtitle="Extension Weight、Probation、P95 观测与隔离调度"
      />
      <main className="p-4 sm:p-6">
        <section className="grid border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-4">
          <Metric
            icon={Puzzle}
            label="Profiles"
            value={String(extensions.length)}
          />
          <Metric
            icon={FlaskConical}
            label="Probation"
            value={String(probation)}
            tone={probation > 0 ? 'warning' : 'success'}
          />
          <Metric
            icon={ShieldAlert}
            label="Privileged"
            value={String(privileged)}
            tone={privileged > 0 ? 'warning' : 'accent'}
          />
          <Metric icon={Activity} label="Samples" value={String(samples)} />
        </section>

        <section className="mt-4">
          {query.isLoading ? (
            <div className="border border-border-subtle bg-surface-1">
              <LoadingPanel label="正在读取 Extension Profile" />
            </div>
          ) : query.isError ? (
            <div className="border border-border-subtle bg-surface-1">
              <ErrorState
                error={query.error}
                onRetry={() => query.refetch()}
                title="无法加载扩展资源画像"
              />
            </div>
          ) : extensions.length === 0 ? (
            <div className="border border-border-subtle bg-surface-1">
              <EmptyState
                title="没有已登记的 Extension Profile"
                description="未知扩展仍可请求 Session，但会自动进入 Probation、提升 Resource Class，并限制每个 Node 的并发数。"
              />
            </div>
          ) : (
            <div className="grid gap-4 md:grid-cols-2 2xl:grid-cols-3">
              {extensions.map((extension) => (
                <ExtensionCard
                  key={extension.extensionId}
                  extension={extension}
                />
              ))}
            </div>
          )}
        </section>
      </main>
    </div>
  );
}

function ExtensionCard({ extension }: { extension: ExtensionProfileView }) {
  const risk = extension.privileged
    ? 'PRIVILEGED'
    : extension.crypto || extension.web3
      ? 'HIGH RISK'
      : 'STANDARD';
  const totalStaticWeight =
    extension.staticCpuWeight +
    extension.staticMemoryWeight +
    extension.startupWeight +
    extension.pageInjectionWeight +
    extension.serviceWorkerWeight +
    extension.cryptoWeight +
    extension.networkWeight;

  return (
    <article className="border border-border-subtle bg-surface-1 p-4 sm:p-5">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-[13px] font-semibold text-text-primary">
            {extension.displayName}
          </h2>
          <p className="mt-1 truncate font-mono text-[10px] text-text-muted">
            {extension.extensionId}
          </p>
        </div>
        <span
          className={cn(
            'shrink-0 border px-2 py-0.5 text-[9px] font-semibold',
            extension.profileState === 'CERTIFIED'
              ? 'border-success/25 bg-success/10 text-success'
              : extension.profileState === 'DISABLED'
                ? 'border-danger/25 bg-danger/10 text-danger'
                : 'border-warning/25 bg-warning/10 text-warning'
          )}
        >
          {extension.profileState}
        </span>
      </div>

      <div className="mt-4 grid grid-cols-3 gap-px bg-border-subtle">
        <SmallStat label="Risk" value={risk} />
        <SmallStat
          label="Multiplier"
          value={`${extension.observedMultiplier.toFixed(2)}×`}
        />
        <SmallStat
          label="Confidence"
          value={`${Math.round(extension.confidence * 100)}%`}
        />
      </div>

      <div className="mt-4">
        <div className="mb-1 flex items-center justify-between text-[10px]">
          <span className="text-text-muted">Static weight total</span>
          <span className="font-mono text-text-secondary">
            {totalStaticWeight}
          </span>
        </div>
        <div className="h-1.5 bg-surface-3">
          <span
            className={cn(
              'block h-full',
              totalStaticWeight >= 500
                ? 'bg-danger'
                : totalStaticWeight >= 250
                  ? 'bg-warning'
                  : 'bg-accent'
            )}
            style={{ width: `${Math.min(totalStaticWeight / 7, 100)}%` }}
          />
        </div>
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-2 border-t border-border-subtle pt-3 text-[10px]">
        <KeyValue
          label="CPU / Memory weight"
          value={`${extension.staticCpuWeight} / ${extension.staticMemoryWeight}`}
        />
        <KeyValue
          label="Startup / Injection"
          value={`${extension.startupWeight} / ${extension.pageInjectionWeight}`}
        />
        <KeyValue
          label="P95 CPU"
          value={
            extension.p95CpuMillis === undefined
              ? 'NO SAMPLE'
              : `${extension.p95CpuMillis}m`
          }
        />
        <KeyValue
          label="P95 Memory"
          value={
            extension.p95MemoryMib === undefined
              ? 'NO SAMPLE'
              : `${extension.p95MemoryMib} MiB`
          }
        />
      </dl>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {extension.web3 && <Tag label="WEB3" />}
        {extension.crypto && <Tag label="CRYPTO" />}
        {extension.serviceWorker && <Tag label="SERVICE WORKER" />}
        {extension.privileged && <Tag label="ISOLATED PLACEMENT" danger />}
      </div>
    </article>
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

function KeyValue({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-text-muted">{label}</dt>
      <dd className="mt-0.5 font-mono text-text-secondary">{value}</dd>
    </div>
  );
}

function Tag({ label, danger = false }: { label: string; danger?: boolean }) {
  return (
    <span
      className={cn(
        'border px-2 py-0.5 font-mono text-[8px]',
        danger
          ? 'border-danger/25 bg-danger/10 text-danger'
          : 'border-border-default bg-surface-2 text-text-muted'
      )}
    >
      {label}
    </span>
  );
}
