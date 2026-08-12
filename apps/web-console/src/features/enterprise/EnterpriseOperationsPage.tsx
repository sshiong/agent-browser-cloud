import { useState } from 'react';
import {
  BadgeCheck,
  CircleDollarSign,
  Download,
  Globe2,
  RadioTower,
  ShieldCheck,
  TimerReset,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import {
  useEnterpriseOverview,
  useGenerateRecoveryGameDayReport,
  useRecoveryGameDayEvents,
  useUpdateRecoveryGameDayRemediation,
} from './enterpriseQueries';
import { cn } from '@/shared/lib/utils';
import { currentActorId } from '@/api/session';
import { getRuntimeIdentity } from '@/auth/runtimeIdentity';
import type { RecoveryGameDayRemediationView } from '@/types/enterprise';

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
  const gameDayEvents = useRecoveryGameDayEvents(latestGameDay?.gameDayId);
  const report = useGenerateRecoveryGameDayReport();
  const canManageGameDay =
    getRuntimeIdentity()?.roles.includes('PLATFORM_ADMIN') ?? false;
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
          label="Release gate"
          value={
            data.releaseFreeze?.enabled
              ? data.releaseFreeze.phase
              : (data.errorBudget?.state ?? 'NOT SET')
          }
          tone={tone(
            data.releaseFreeze?.enabled
              ? data.releaseFreeze.phase
              : data.errorBudget?.state
          )}
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
                  subtitle={
                    run.job
                      ? `${run.job.browserEngine} ${run.job.browserVersion} · ${run.job.operatingSystem}/${run.job.architecture} · ${run.replayDatasetId}`
                      : `${run.suiteVersion} · ${run.replayDatasetId} · ${run.persona}`
                  }
                  value={run.job?.state ?? run.state}
                  detail={
                    run.job?.failureCode
                      ? `${run.job.failureCode} · attempt ${run.job.attempt}/${run.job.maximumAttempts}`
                      : run.job && run.job.state !== 'COMMITTED'
                        ? `attempt ${run.job.attempt}/${run.job.maximumAttempts}${run.job.workerId ? ` · ${run.job.workerId}` : ''}`
                        : `${run.requiredTests - run.requiredFailures}/${run.requiredTests} required`
                  }
                  tone={tone(run.job?.state ?? run.state)}
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
                  value={`${data.errorBudget.burnRatio.toFixed(3)}×`}
                />
              </div>
              {data.releaseFreeze ? (
                <div className="mt-3 border border-border-subtle bg-surface-2 px-3 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div>
                      <p className="text-[9px] uppercase tracking-wider text-text-muted">
                        Runtime promotion gate
                      </p>
                      <p className="mt-1 font-mono text-[10px] text-text-secondary">
                        {data.releaseFreeze.currentBurnRate.toFixed(3)}× burn ·
                        freeze{' '}
                        {data.releaseFreeze.freezeBurnRateThreshold.toFixed(3)}×
                        · recover below{' '}
                        {data.releaseFreeze.recoveryBurnRateThreshold.toFixed(
                          3
                        )}
                        × for {data.releaseFreeze.recoveryStableMinutes}m
                      </p>
                    </div>
                    <Status
                      value={
                        data.releaseFreeze.enabled
                          ? data.releaseFreeze.phase
                          : 'DISABLED'
                      }
                      tone={tone(
                        data.releaseFreeze.enabled
                          ? data.releaseFreeze.phase
                          : undefined
                      )}
                    />
                  </div>
                  <p className="mt-2 font-mono text-[9px] text-text-muted">
                    {data.releaseFreeze.reasonCode} · evaluated{' '}
                    {relativeTime(data.releaseFreeze.evaluatedAt)} · v
                    {data.releaseFreeze.version}
                  </p>
                </div>
              ) : null}
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
                  <p className="mt-1 font-mono text-[9px] uppercase tracking-wide text-text-muted">
                    {latestGameDay.executionMode} · {latestGameDay.environment}
                    {latestGameDay.blastRadius
                      ? ` · ${latestGameDay.blastRadius.scope} ≤ ${latestGameDay.blastRadius.maximumTargets}`
                      : ''}
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
              {latestGameDay.job ? (
                <div className="mt-3 border border-border-subtle bg-surface-2 px-3 py-3">
                  <div className="flex items-center justify-between gap-3">
                    <div className="min-w-0">
                      <p className="text-[9px] uppercase tracking-wider text-text-muted">
                        Automated execution
                      </p>
                      <p className="mt-1 truncate font-mono text-[10px] text-text-secondary">
                        {latestGameDay.job.currentStage} · attempt{' '}
                        {latestGameDay.job.attempt}/
                        {latestGameDay.job.maximumAttempts}
                        {latestGameDay.job.workerId
                          ? ` · ${latestGameDay.job.workerId}`
                          : ''}
                      </p>
                    </div>
                    <Status
                      value={latestGameDay.job.state}
                      tone={tone(latestGameDay.job.state)}
                    />
                  </div>
                  <p className="mt-2 font-mono text-[9px] text-text-muted">
                    recovery{' '}
                    {latestGameDay.job.recoveryConfirmed === true
                      ? 'confirmed'
                      : latestGameDay.job.faultInjected
                        ? 'required'
                        : 'not started'}
                    {latestGameDay.job.recoveryAttempt > 0
                      ? ` · recovery attempt ${latestGameDay.job.recoveryAttempt}/${latestGameDay.job.maximumRecoveryAttempts}`
                      : ''}
                    {latestGameDay.job.failureCode
                      ? ` · ${latestGameDay.job.failureCode}`
                      : ''}
                  </p>
                </div>
              ) : null}
              {latestGameDay.abortRequested ? (
                <p className="mt-3 border border-warning/25 bg-warning/8 px-3 py-2 text-[10px] text-warning">
                  已请求中止；Worker 必须先确认恢复，平台才会关闭本次演练。
                </p>
              ) : null}
              <div className="mt-3 flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle pt-3">
                <div>
                  <p className="text-[9px] uppercase tracking-wider text-text-muted">
                    Signed evidence report
                  </p>
                  <p className="mt-1 font-mono text-[9px] text-text-secondary">
                    {report.data
                      ? `${report.data.exportId} · ${report.data.eventCount} events · ${report.data.reportHash.slice(0, 12)}…`
                      : 'JSON · SHA-256 · HMAC-SHA256'}
                  </p>
                  {report.isError ? (
                    <p className="mt-1 text-[9px] text-danger">
                      报告生成失败；权限或请求详情可在错误响应中复核。
                    </p>
                  ) : null}
                </div>
                <button
                  type="button"
                  disabled={report.isPending}
                  onClick={() => report.mutate(latestGameDay.gameDayId)}
                  className="inline-flex min-h-9 items-center gap-2 border border-border-strong bg-surface-2 px-3 text-[10px] font-medium text-text-primary transition-colors hover:border-accent/60 hover:text-accent disabled:cursor-not-allowed disabled:opacity-50"
                >
                  <Download className="size-3.5" aria-hidden="true" />
                  {report.isPending ? '生成中' : '生成签名报告'}
                </button>
              </div>
              <div className="mt-3 border-t border-border-subtle pt-3">
                <p className="text-[9px] uppercase tracking-wider text-text-muted">
                  Immutable timeline
                </p>
                {gameDayEvents.isLoading ? (
                  <p className="mt-2 text-[10px] text-text-muted">
                    正在读取事件链…
                  </p>
                ) : gameDayEvents.isError ? (
                  <p className="mt-2 text-[10px] text-danger">事件链暂不可用</p>
                ) : gameDayEvents.data?.items.length ? (
                  <div className="mt-2 max-h-48 divide-y divide-border-subtle overflow-y-auto border border-border-subtle">
                    {gameDayEvents.data.items.map((event) => (
                      <div
                        key={event.eventId}
                        className="grid grid-cols-[72px_minmax(0,1fr)_auto] gap-3 bg-surface-2 px-3 py-2"
                      >
                        <span className="font-mono text-[9px] text-text-muted">
                          {new Date(event.occurredAt).toLocaleTimeString([], {
                            hour: '2-digit',
                            minute: '2-digit',
                            second: '2-digit',
                          })}
                        </span>
                        <span className="truncate font-mono text-[9px] text-text-secondary">
                          {event.eventType} · {event.stage}
                          {event.reasonCode ? ` · ${event.reasonCode}` : ''}
                        </span>
                        <span className="font-mono text-[9px] text-text-muted">
                          e{event.claimEpoch}/a{event.attempt}
                        </span>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="mt-2 text-[10px] text-text-muted">
                    手工演练没有 Worker 事件；最终证据仍由运行记录保留。
                  </p>
                )}
              </div>
            </div>
          ) : (
            <Empty label="尚无可复核的 Recovery GameDay" />
          )}
        </Panel>
      </section>

      <section className="grid gap-4 xl:grid-cols-2">
        <Panel title="GameDay 90-day trend">
          {data.recoveryGameDayTrends.length === 0 ? (
            <Empty label="尚无可聚合的 GameDay 趋势" />
          ) : (
            <div className="divide-y divide-border-subtle">
              {data.recoveryGameDayTrends.slice(0, 8).map((trend) => (
                <Row
                  key={`${trend.scenario}:${trend.environment}`}
                  title={trend.scenario}
                  subtitle={`${trend.environment} · P95 RTO ${trend.p95RtoSeconds ?? '—'}s / RPO ${trend.p95RpoSeconds ?? '—'}s`}
                  value={`${Number(trend.passRatePercent).toFixed(1)}%`}
                  detail={`${trend.passedRuns}/${trend.totalRuns} passed · ${trend.openTicketCount} open`}
                  tone={
                    trend.failedRuns + trend.abortedRuns > 0
                      ? 'warning'
                      : 'success'
                  }
                />
              ))}
            </div>
          )}
        </Panel>

        <Panel title="GameDay remediation">
          {data.recoveryGameDayRemediations.length === 0 ? (
            <Empty label="没有待处理的 GameDay 整改工单" />
          ) : (
            <div className="divide-y divide-border-subtle">
              {data.recoveryGameDayRemediations.slice(0, 8).map((ticket) => (
                <RemediationTicketRow
                  key={ticket.ticketId}
                  ticket={ticket}
                  canManage={canManageGameDay}
                />
              ))}
            </div>
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
                value={`$${rate.baseHourlyUsd.toFixed(3)}/h · $${(rate.remoteDesktopEgressGibUsd ?? 0).toFixed(3)}/GiB RFB`}
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

function RemediationTicketRow({
  ticket,
  canManage,
}: {
  ticket: RecoveryGameDayRemediationView;
  canManage: boolean;
}) {
  const mutation = useUpdateRecoveryGameDayRemediation();
  const [resolution, setResolution] = useState('');
  const resolutionErrorId = `${ticket.ticketId}-resolution-error`;

  const acknowledge = () => {
    mutation.mutate({
      ticketId: ticket.ticketId,
      input: { state: 'ACKNOWLEDGED', ownerId: currentActorId() },
    });
  };
  const resolve = () => {
    const normalized = resolution.trim();
    if (!normalized) return;
    mutation.mutate({
      ticketId: ticket.ticketId,
      input: {
        state: 'RESOLVED',
        ownerId: ticket.ownerId ?? currentActorId(),
        resolution: normalized,
      },
    });
  };

  return (
    <div className="bg-surface-1 px-5 py-4">
      <div className="flex items-start justify-between gap-4">
        <div className="min-w-0">
          <p className="truncate text-[12px] font-medium text-text-primary">
            {ticket.severity} · {ticket.scenario}
          </p>
          <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
            {ticket.reasonCode}
            {ticket.ownerId ? ` · ${ticket.ownerId}` : ' · unassigned'}
          </p>
        </div>
        <div className="shrink-0 text-right">
          <Status
            value={ticket.state}
            tone={ticket.state === 'RESOLVED' ? 'success' : 'warning'}
          />
          <p className="mt-1 font-mono text-[9px] text-text-muted">
            {relativeTime(ticket.updatedAt)}
          </p>
        </div>
      </div>

      {ticket.resolution ? (
        <p className="mt-3 border-l-2 border-success/50 pl-3 text-[10px] leading-4 text-text-secondary">
          {ticket.resolution}
        </p>
      ) : null}

      {canManage && ticket.state === 'OPEN' ? (
        <button
          type="button"
          disabled={mutation.isPending}
          onClick={acknowledge}
          className="mt-3 inline-flex min-h-11 items-center border border-border-strong bg-surface-2 px-3 text-[10px] font-semibold text-text-primary transition-colors hover:border-accent/60 hover:text-accent focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50"
        >
          {mutation.isPending ? '正在确认…' : `确认归属给 ${currentActorId()}`}
        </button>
      ) : null}

      {canManage && ticket.state === 'ACKNOWLEDGED' ? (
        <div className="mt-3 grid gap-2 border-t border-border-subtle pt-3 sm:grid-cols-[minmax(0,1fr)_auto] sm:items-end">
          <label className="block">
            <span className="text-[9px] font-medium uppercase tracking-wider text-text-muted">
              关闭说明
            </span>
            <input
              value={resolution}
              maxLength={2048}
              onChange={(event) => setResolution(event.target.value)}
              aria-describedby={
                mutation.isError ? resolutionErrorId : undefined
              }
              className="mt-1 min-h-11 w-full border border-border-default bg-surface-2 px-3 text-[11px] text-text-primary outline-none transition-colors placeholder:text-text-muted focus:border-accent"
              placeholder="说明根因、修复和验证证据"
            />
          </label>
          <button
            type="button"
            disabled={mutation.isPending || !resolution.trim()}
            onClick={resolve}
            className="inline-flex min-h-11 items-center justify-center border border-success/35 bg-success/10 px-4 text-[10px] font-semibold text-success transition-colors hover:border-success/70 focus-visible:outline focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-50"
          >
            {mutation.isPending ? '正在关闭…' : '关闭工单'}
          </button>
        </div>
      ) : null}

      {mutation.isError ? (
        <p id={resolutionErrorId} className="mt-2 text-[9px] text-danger">
          状态更新失败；请检查平台管理员权限、当前状态和请求详情。
        </p>
      ) : null}
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
  if (
    value === 'FAILED' ||
    value === 'ABORTED' ||
    value === 'EXHAUSTED' ||
    value === 'CLOSED' ||
    value === 'FROZEN'
  )
    return 'danger';
  if (
    value === 'DEGRADED' ||
    value === 'RUNNING' ||
    value === 'QUEUED' ||
    value === 'CLAIMED' ||
    value === 'EXECUTING' ||
    value === 'RECOVERY_REQUIRED' ||
    value === 'RECOVERING' ||
    value === 'ACKED'
  )
    return 'warning';
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
