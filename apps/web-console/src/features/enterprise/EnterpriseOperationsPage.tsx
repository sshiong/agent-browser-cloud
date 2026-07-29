import {
  BadgeCheck,
  CircleDollarSign,
  Globe2,
  RadioTower,
  ShieldCheck,
  TimerReset,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import { useEnterpriseOverview } from './enterpriseQueries';
import { cn } from '@/shared/lib/utils';

export function EnterpriseOperationsPage() {
  const query = useEnterpriseOverview();

  return (
    <div>
      <TopContextBar
        title="企业运营"
        subtitle="Runtime Validation、成本、SLA、合规证据与多 Region 灾备"
      />
      <main className="p-4 sm:p-6">
        {query.isLoading ? (
          <div className="border border-border-subtle bg-surface-1">
            <LoadingPanel label="正在读取企业运营状态" />
          </div>
        ) : query.isError ? (
          <div className="border border-border-subtle bg-surface-1">
            <ErrorState
              error={query.error}
              onRetry={() => query.refetch()}
              title="无法加载企业运营状态"
            />
          </div>
        ) : query.data ? (
          <EnterpriseOverview data={query.data} />
        ) : null}
      </main>
    </div>
  );
}

function EnterpriseOverview({
  data,
}: {
  data: NonNullable<ReturnType<typeof useEnterpriseOverview>['data']>;
}) {
  const latestValidation = data.validations[0];
  const latestGameDay = data.recoveryGameDays[0];
  const readyRegions = data.regions.filter(
    (region) =>
      region.admissionState === 'OPEN' ||
      region.admissionState === 'FAILOVER_READY'
  ).length;

  return (
    <div className="space-y-4">
      <section className="grid border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-6">
        <Metric
          icon={BadgeCheck}
          label="Runtime validation"
          value={latestValidation?.state ?? 'NOT RUN'}
          tone={tone(latestValidation?.state)}
        />
        <Metric
          icon={CircleDollarSign}
          label="Pricing models"
          value={String(data.costRates.length)}
        />
        <Metric
          icon={TimerReset}
          label="Error budget"
          value={data.errorBudget?.state ?? 'NOT SET'}
          tone={tone(data.errorBudget?.state)}
        />
        <Metric
          icon={ShieldCheck}
          label="Compliance"
          value={
            data.latestCompliance
              ? `${data.latestCompliance.passingControls}/${data.latestCompliance.controlCount}`
              : 'NOT RUN'
          }
          tone={
            data.latestCompliance &&
            data.latestCompliance.passingControls ===
              data.latestCompliance.controlCount
              ? 'success'
              : 'warning'
          }
        />
        <Metric
          icon={Globe2}
          label="Regions ready"
          value={`${readyRegions}/${data.regions.length}`}
          tone={readyRegions === data.regions.length ? 'success' : 'warning'}
        />
        <Metric
          icon={RadioTower}
          label="Media streams"
          value={
            data.mediaQuota
              ? `${data.mediaQuota.activeStreams}/${data.mediaQuota.maxConcurrentStreams}`
              : 'NOT SET'
          }
          tone={
            data.mediaQuota &&
            data.mediaQuota.activeStreams < data.mediaQuota.maxConcurrentStreams
              ? 'success'
              : 'warning'
          }
        />
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <Panel title="Runtime Validation Farm">
          {data.validations.length === 0 ? (
            <Empty label="尚无 Build-bound Validation 证据" />
          ) : (
            <div className="divide-y divide-border-subtle">
              {data.validations.slice(0, 5).map((run) => (
                <Row
                  key={run.validationId}
                  title={run.buildId}
                  subtitle={`${run.suiteVersion} · ${run.replayDatasetId} · ${run.persona}`}
                  value={run.state}
                  detail={`${run.requiredTests - run.requiredFailures}/${run.requiredTests} required`}
                  tone={tone(run.state)}
                />
              ))}
            </div>
          )}
        </Panel>

        <Panel title="SLA / Error Budget">
          {data.errorBudget ? (
            <div className="p-5">
              <div className="flex items-end justify-between gap-4">
                <div>
                  <p className="text-[10px] uppercase tracking-wider text-text-muted">
                    Remaining
                  </p>
                  <p className="mt-1 font-mono text-2xl font-semibold text-text-primary">
                    {formatDuration(
                      data.errorBudget.remainingUnavailableSeconds
                    )}
                  </p>
                </div>
                <Status
                  value={data.errorBudget.state}
                  tone={tone(data.errorBudget.state)}
                />
              </div>
              <div className="mt-5 grid grid-cols-3 gap-px bg-border-subtle">
                <Small
                  label="Target"
                  value={`${(data.errorBudget.availabilityTarget * 100).toFixed(3)}%`}
                />
                <Small
                  label="Consumed"
                  value={formatDuration(
                    data.errorBudget.consumedUnavailableSeconds
                  )}
                />
                <Small
                  label="Burn"
                  value={`${(data.errorBudget.burnRatio * 100).toFixed(2)}%`}
                />
              </div>
              {data.slaExclusions.length > 0 ? (
                <p className="mt-3 text-[10px] text-text-muted">
                  {data.slaExclusions.filter((item) => item.enabled).length}{' '}
                  explicit SLA exclusion(s) enabled
                </p>
              ) : null}
            </div>
          ) : (
            <Empty label="尚未配置租户 SLO Policy" />
          )}
        </Panel>

        <Panel title="Multi-region / DR">
          <div className="divide-y divide-border-subtle">
            {data.regions.map((region) => (
              <Row
                key={region.regionId}
                title={region.regionId}
                subtitle={`${region.role} · replication lag ${region.replicationLagSeconds}s`}
                value={region.admissionState}
                detail={relativeTime(region.lastVerifiedAt)}
                tone={tone(region.admissionState)}
              />
            ))}
          </div>
        </Panel>

        <Panel title="Recovery GameDay">
          {latestGameDay ? (
            <div className="p-5">
              <div className="flex items-start justify-between gap-4">
                <div>
                  <p className="text-[13px] font-semibold text-text-primary">
                    {latestGameDay.scenario}
                  </p>
                  <p className="mt-1 font-mono text-[10px] text-text-muted">
                    {latestGameDay.sourceRegion} → {latestGameDay.targetRegion}
                  </p>
                </div>
                <Status
                  value={latestGameDay.state}
                  tone={tone(latestGameDay.state)}
                />
              </div>
              <div className="mt-5 grid grid-cols-3 gap-px bg-border-subtle">
                <Small
                  label="Observed RTO"
                  value={
                    latestGameDay.observedRtoSeconds == null
                      ? '—'
                      : `${latestGameDay.observedRtoSeconds}s`
                  }
                />
                <Small
                  label="Observed RPO"
                  value={
                    latestGameDay.observedRpoSeconds == null
                      ? '—'
                      : `${latestGameDay.observedRpoSeconds}s`
                  }
                />
                <Small
                  label="Data loss"
                  value={String(latestGameDay.dataLossRecords ?? '—')}
                />
              </div>
            </div>
          ) : (
            <Empty label="尚无可复核的 Recovery GameDay" />
          )}
        </Panel>
      </section>

      <section className="grid gap-4 xl:grid-cols-3">
        <Panel title="Retention / Residency">
          {data.retentionPolicies.length === 0 ? (
            <Empty label="尚未配置 Retention Policy" />
          ) : (
            <div className="divide-y divide-border-subtle">
              {data.retentionPolicies.map((policy) => (
                <Row
                  key={policy.dataClass}
                  title={policy.dataClass}
                  subtitle={`${policy.residencyRegion} · ${policy.retentionDays} days`}
                  value={policy.legalHold ? 'LEGAL HOLD' : 'ACTIVE'}
                  detail={relativeTime(policy.updatedAt)}
                  tone={policy.legalHold ? 'warning' : 'success'}
                />
              ))}
            </div>
          )}
        </Panel>

        <Panel title="Cost Model">
          {data.mediaQuota ? (
            <div className="grid grid-cols-3 gap-px border-b border-border-subtle bg-border-subtle">
              <Small
                label="Media streams"
                value={`${data.mediaQuota.activeStreams}/${data.mediaQuota.maxConcurrentStreams}`}
              />
              <Small
                label="Active bitrate"
                value={`${data.mediaQuota.activeBitrateKbps} kbps`}
              />
              <Small
                label="Bitrate limit"
                value={`${data.mediaQuota.maxBitrateKbps} kbps`}
              />
            </div>
          ) : null}
          <div className="divide-y divide-border-subtle">
            {data.costRates.slice(0, 6).map((rate) => (
              <Row
                key={rate.pricingVersion}
                title={`${rate.region} / ${rate.resourceTemplate}`}
                subtitle={rate.pricingVersion}
                value={`$${rate.baseHourlyUsd.toFixed(3)}/h`}
                detail={relativeTime(rate.effectiveAt)}
                tone="neutral"
              />
            ))}
          </div>
        </Panel>

        <Panel title="License Inventory">
          <div className="divide-y divide-border-subtle">
            {data.licenseInventory.map((component) => (
              <Row
                key={component.componentId}
                title={component.componentName}
                subtitle={`${component.componentType} · ${component.componentVersion}`}
                value={component.licenseId}
                detail={component.approved ? 'APPROVED' : 'REVIEW REQUIRED'}
                tone={component.approved ? 'success' : 'warning'}
              />
            ))}
          </div>
        </Panel>
      </section>
    </div>
  );
}

function Panel({
  title,
  children,
}: {
  title: string;
  children: React.ReactNode;
}) {
  return (
    <section className="border border-border-subtle bg-surface-1">
      <h2 className="border-b border-border-subtle px-5 py-3 text-[12px] font-semibold text-text-primary">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Metric({
  icon: Icon,
  label,
  value,
  tone: metricTone = 'neutral',
}: {
  icon: React.ComponentType<{ size?: number }>;
  label: string;
  value: string;
  tone?: Tone;
}) {
  return (
    <div className="flex min-h-24 items-center gap-3 bg-surface-1 px-5 py-4">
      <div className="flex h-9 w-9 items-center justify-center bg-surface-3 text-accent">
        <Icon size={17} />
      </div>
      <div className="min-w-0">
        <p className="text-[10px] uppercase tracking-wider text-text-muted">
          {label}
        </p>
        <p
          className={cn(
            'mt-1 truncate font-mono text-[13px] font-semibold',
            color(metricTone)
          )}
        >
          {value}
        </p>
      </div>
    </div>
  );
}

function Row({
  title,
  subtitle,
  value,
  detail,
  tone: rowTone,
}: {
  title: string;
  subtitle: string;
  value: string;
  detail: string;
  tone: Tone;
}) {
  return (
    <div className="flex items-center justify-between gap-4 px-5 py-3">
      <div className="min-w-0">
        <p className="truncate text-[12px] font-medium text-text-primary">
          {title}
        </p>
        <p className="mt-0.5 truncate font-mono text-[10px] text-text-muted">
          {subtitle}
        </p>
      </div>
      <div className="shrink-0 text-right">
        <Status value={value} tone={rowTone} />
        <p className="mt-1 font-mono text-[9px] text-text-muted">{detail}</p>
      </div>
    </div>
  );
}

function Status({ value, tone: statusTone }: { value: string; tone: Tone }) {
  return (
    <span
      className={cn(
        'inline-flex border px-2 py-0.5 font-mono text-[9px] font-semibold',
        statusTone === 'success'
          ? 'border-success/25 bg-success/8 text-success'
          : statusTone === 'danger'
            ? 'border-danger/25 bg-danger/8 text-danger'
            : statusTone === 'warning'
              ? 'border-warning/25 bg-warning/8 text-warning'
              : 'border-border-default bg-surface-2 text-text-secondary'
      )}
    >
      {value}
    </span>
  );
}

function Small({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface-2 px-3 py-3">
      <p className="text-[9px] uppercase tracking-wider text-text-muted">
        {label}
      </p>
      <p className="mt-1 font-mono text-[11px] text-text-primary">{value}</p>
    </div>
  );
}

function Empty({ label }: { label: string }) {
  return (
    <p className="px-5 py-10 text-center text-[12px] text-text-muted">
      {label}
    </p>
  );
}

type Tone = 'success' | 'warning' | 'danger' | 'neutral';

function tone(value?: string): Tone {
  if (
    value === 'PASSED' ||
    value === 'HEALTHY' ||
    value === 'OPEN' ||
    value === 'FAILOVER_READY'
  )
    return 'success';
  if (value === 'FAILED' || value === 'EXHAUSTED' || value === 'CLOSED')
    return 'danger';
  if (value === 'DEGRADED' || value === 'RUNNING') return 'warning';
  return 'neutral';
}

function color(value: Tone) {
  if (value === 'success') return 'text-success';
  if (value === 'danger') return 'text-danger';
  if (value === 'warning') return 'text-warning';
  return 'text-text-primary';
}

function formatDuration(seconds: number) {
  if (seconds >= 3600) return `${(seconds / 3600).toFixed(1)}h`;
  if (seconds >= 60) return `${Math.floor(seconds / 60)}m`;
  return `${seconds}s`;
}

function relativeTime(value: string) {
  const seconds = Math.max(
    0,
    Math.round((Date.now() - new Date(value).getTime()) / 1000)
  );
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  return `${Math.floor(seconds / 3600)}h ago`;
}
