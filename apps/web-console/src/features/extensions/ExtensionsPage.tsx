import {
  Activity,
  Braces,
  FlaskConical,
  Puzzle,
  ShieldAlert,
} from 'lucide-react';
import { useSearchParams } from 'react-router';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import { useExtensionProfiles } from '@/features/nodes/capacityQueries';
import { cn } from '@/shared/lib/utils';
import type { ExtensionProfileView } from '@/types/capacity';
import { RecoveryContractWorkspace } from './RecoveryContractWorkspace';

export function ExtensionsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const view =
    searchParams.get('view') === 'recovery' ? 'recovery' : 'profiles';
  const query = useExtensionProfiles(view === 'profiles');
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
        title="扩展与应用"
        subtitle={
          view === 'profiles'
            ? 'Extension Weight、Probation、P95 观测与隔离调度'
            : '版本化 Application Ready Gate、恢复证据与有界自动动作'
        }
      />
      <main className="p-4 sm:p-6">
        <nav
          aria-label="扩展与应用工作区"
          className="mb-4 flex border-b border-border-default"
        >
          <WorkspaceTab
            active={view === 'profiles'}
            icon={<Puzzle size={14} />}
            label="扩展资源画像"
            detail="PROFILE / TELEMETRY"
            onClick={() => setSearchParams({ view: 'profiles' })}
          />
          <WorkspaceTab
            active={view === 'recovery'}
            icon={<Braces size={14} />}
            label="应用恢复契约"
            detail="READY GATE / RECOVERY"
            onClick={() => setSearchParams({ view: 'recovery' })}
          />
        </nav>

        {view === 'profiles' ? (
          <>
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
          </>
        ) : (
          <RecoveryContractWorkspace />
        )}
      </main>
    </div>
  );
}

function WorkspaceTab({
  active,
  icon,
  label,
  detail,
  onClick,
}: {
  active: boolean;
  icon: React.ReactNode;
  label: string;
  detail: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={cn(
        'relative flex min-h-14 items-center gap-3 px-4 text-left transition-colors',
        active
          ? 'bg-surface-1 text-accent'
          : 'text-text-muted hover:bg-surface-1 hover:text-text-secondary'
      )}
    >
      {icon}
      <span>
        <span className="block text-[11px] font-semibold">{label}</span>
        <span className="mt-0.5 block font-mono text-[8px] tracking-[0.08em]">
          {detail}
        </span>
      </span>
      {active && (
        <span className="absolute inset-x-0 bottom-[-1px] h-[2px] bg-accent" />
      )}
    </button>
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
        <SmallStat label="Sampling" value={extension.samplingTier} />
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
        <KeyValue
          label="Sampling budget"
          value={`${extension.samplingCpuBudgetMillis}m CPU`}
        />
        <KeyValue
          label="Next sample"
          value={
            extension.nextSampleAt
              ? new Date(extension.nextSampleAt).toLocaleTimeString()
              : 'DUE NOW'
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
