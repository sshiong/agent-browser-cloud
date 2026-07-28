import {
  CircleAlert,
  CircleCheck,
  LoaderCircle,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import { cn } from '@/shared/lib/utils';
import type {
  BusinessRecoveryValidationView,
  SessionMigrationView,
} from '@/types/session';

export function BusinessRecoveryCard({
  validation,
  migration,
  loading,
  error,
  canValidate,
  validating,
  onValidate,
  onRetry,
}: {
  validation: BusinessRecoveryValidationView | null | undefined;
  migration: SessionMigrationView | null | undefined;
  loading: boolean;
  error: unknown;
  canValidate: boolean;
  validating: boolean;
  onValidate: () => void;
  onRetry: () => void;
}) {
  return (
    <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
      <div className="mb-4 flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <ShieldCheck size={15} className="text-accent" />
            <h2 className="text-[13px] font-semibold text-text-primary">
              Business Recovery Ready Gate
            </h2>
          </div>
          <p className="mt-1 text-[11px] leading-5 text-text-muted">
            基于 PostgreSQL 中的应用恢复契约和当前权威 Browser
            State；不执行租户脚本。
          </p>
        </div>
        <button
          type="button"
          onClick={onValidate}
          disabled={!canValidate || validating}
          className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-accent/35 px-3 text-[11px] text-accent hover:bg-accent-soft disabled:cursor-not-allowed disabled:border-border-default disabled:text-text-muted disabled:opacity-45"
        >
          {validating ? (
            <LoaderCircle size={13} className="animate-spin" />
          ) : (
            <RefreshCw size={13} />
          )}
          重新验证
        </button>
      </div>

      {loading ? (
        <LoadingPanel label="正在读取业务恢复结果" />
      ) : error ? (
        <ErrorState
          error={error}
          title="无法读取 Business Recovery"
          onRetry={onRetry}
        />
      ) : !validation ? (
        <div className="flex items-start gap-3 border border-dashed border-border-default bg-surface-2 p-4">
          <CircleAlert size={16} className="mt-0.5 shrink-0 text-warning" />
          <div>
            <p className="text-[12px] font-medium text-text-primary">
              尚无持久验证结果
            </p>
            <p className="mt-1 text-[11px] leading-5 text-text-muted">
              Session 运行并形成权威 Browser State 后可验证；跨 Node
              迁移也会自动执行同一 Ready Gate。
            </p>
          </div>
        </div>
      ) : (
        <div className="space-y-3">
          <div
            className={cn(
              'flex flex-wrap items-center justify-between gap-3 border p-4',
              validation.ready
                ? 'border-success/25 bg-success/8'
                : 'border-warning/25 bg-warning/8'
            )}
          >
            <div className="flex items-center gap-3">
              {validation.ready ? (
                <CircleCheck size={17} className="text-success" />
              ) : (
                <CircleAlert size={17} className="text-warning" />
              )}
              <div>
                <p className="font-mono text-[12px] font-medium text-text-primary">
                  {validation.verdict}
                </p>
                <p className="mt-0.5 text-[10px] text-text-muted">
                  {validation.ready
                    ? 'Ready Gate 已通过，可恢复 Agent'
                    : 'Ready Gate 未通过，Agent 保持暂停'}
                </p>
              </div>
            </div>
            <span className="font-mono text-[10px] text-text-muted">
              {validation.source} · Context {validation.contextEpoch} · State{' '}
              {validation.stateVersion}
            </span>
          </div>

          <dl className="grid grid-cols-2 gap-px overflow-hidden border border-border-subtle bg-border-subtle md:grid-cols-4">
            <Metric
              label="Application"
              value={validation.applicationId ?? 'generic'}
            />
            <Metric
              label="Contract"
              value={
                validation.contractVersion == null
                  ? 'default'
                  : `v${validation.contractVersion}`
              }
            />
            <Metric
              label="Validated"
              value={new Date(validation.evaluatedAt).toLocaleTimeString()}
            />
            <Metric label="Request" value={validation.requestId || 'system'} />
          </dl>

          {migration?.latestRecoveryAction && (
            <div className="border border-border-subtle bg-surface-2 px-3 py-2.5">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <p className="font-mono text-[10px] text-text-primary">
                  AUTO RECOVERY · {migration.latestRecoveryAction.action}
                </p>
                <span className="font-mono text-[10px] text-text-muted">
                  {migration.latestRecoveryAction.state} ·{' '}
                  {migration.autoRecoveryAttempts}/
                  {migration.autoRecoveryMaximum}
                </span>
              </div>
              <p className="mt-1 text-[10px] leading-4 text-text-muted">
                Attempt {migration.latestRecoveryAction.attemptNumber} · State{' '}
                {migration.latestRecoveryAction.baseStateVersion}
                {migration.latestRecoveryAction.resultingStateVersion
                  ? ` → ${migration.latestRecoveryAction.resultingStateVersion}`
                  : ''}
                {migration.latestRecoveryAction.targetExtensionId
                  ? ` · Extension ${migration.latestRecoveryAction.targetExtensionId}`
                  : ''}
                {migration.latestRecoveryAction.errorCode
                  ? ` · ${migration.latestRecoveryAction.errorCode}`
                  : ''}
              </p>
            </div>
          )}

          <div className="space-y-1.5">
            {validation.evidence.map((item) => (
              <div
                key={item}
                className="border-l-2 border-border-default bg-surface-2 px-3 py-2 font-mono text-[10px] text-text-secondary"
              >
                {item}
              </div>
            ))}
          </div>
        </div>
      )}
    </section>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 bg-surface-2 px-3 py-2.5">
      <dt className="text-[9px] uppercase tracking-[0.13em] text-text-muted">
        {label}
      </dt>
      <dd className="mt-1 truncate font-mono text-[10px] text-text-primary">
        {value}
      </dd>
    </div>
  );
}
