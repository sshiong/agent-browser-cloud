import * as Dialog from '@radix-ui/react-dialog';
import {
  CircleAlert,
  LoaderCircle,
  Network,
  RotateCcw,
  ShieldCheck,
  X,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type {
  ProxyBindingView,
  ProxyRebindRequest,
  ProxyRebindView,
} from '@/types/proxy';
import type { SessionSafePointView, SessionState } from '@/types/session';

const WORKFLOW_STEPS = [
  'CHECKPOINTING',
  'RESTORING',
  'STATE_RESYNC',
  'BUSINESS_VALIDATION',
] as const;

const ACTIVE_PHASES = new Set([
  'CHECKPOINTING',
  'PLACING_TARGET',
  'RESTORING',
  'TARGET_CLEANUP',
  'STATE_RESYNC',
  'BUSINESS_VALIDATION',
  'BUSINESS_RECOVERY_ACTION',
]);

export function ProxyRebindPanel({
  sessionId,
  sessionState,
  sessionRegion,
  currentBindingProfileId,
  hasActiveOperation,
  safePoint,
  bindings,
  bindingsLoading,
  latest,
  latestLoading,
  canAdminister,
  pending,
  error,
  onRebind,
}: {
  sessionId: string;
  sessionState: SessionState;
  sessionRegion: string;
  currentBindingProfileId?: string;
  hasActiveOperation: boolean;
  safePoint?: SessionSafePointView;
  bindings: ProxyBindingView[];
  bindingsLoading: boolean;
  latest: ProxyRebindView | null | undefined;
  latestLoading: boolean;
  canAdminister: boolean;
  pending: boolean;
  error: unknown;
  onRebind: (request: ProxyRebindRequest) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [targetId, setTargetId] = useState('');
  const [reason, setReason] = useState('');
  const [acknowledged, setAcknowledged] = useState(false);
  const activeWorkflow = Boolean(latest && ACTIVE_PHASES.has(latest.phase));
  const targets = useMemo(
    () =>
      bindings.filter(
        (binding) =>
          binding.enabled &&
          binding.bindingProfileId !== currentBindingProfileId &&
          (!binding.region || binding.region === sessionRegion)
      ),
    [bindings, currentBindingProfileId, sessionRegion]
  );
  const canOpen =
    canAdminister &&
    ['RUNNING', 'DEGRADED'].includes(sessionState) &&
    !hasActiveOperation &&
    !activeWorkflow &&
    safePoint?.safe === true &&
    targets.length > 0;

  const unavailableReason = !canAdminister
    ? '需要 Tenant Admin、Security Admin 或 Platform Admin'
    : !['RUNNING', 'DEGRADED'].includes(sessionState)
      ? '仅运行中或降级状态的 Session 可以重绑'
      : hasActiveOperation || activeWorkflow
        ? '已有排他 Operation 或重绑工作流正在执行'
        : safePoint?.safe !== true
          ? 'Safe Point 未满足，Browser 将保持当前出口'
          : targets.length === 0
            ? '没有同 Region 的可用目标 Binding'
            : null;

  const submit = async () => {
    if (!targetId || !reason.trim() || !acknowledged) return;
    await onRebind({
      targetBindingProfileId: targetId,
      reason: reason.trim(),
    });
    setOpen(false);
    setTargetId('');
    setReason('');
    setAcknowledged(false);
  };

  return (
    <>
      <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
        <div className="flex items-start justify-between gap-4 border-b border-border-subtle px-5 py-4">
          <div>
            <div className="flex items-center gap-2">
              <Network size={14} className="text-accent" />
              <h2 className="text-[13px] font-semibold text-text-primary">
                Proxy Identity
              </h2>
            </div>
            <p className="mt-1 text-[10px] leading-4 text-text-muted">
              绑定变化通过 Checkpoint 与受控重启生效，不承诺连接无感切换。
            </p>
          </div>
          <span
            className={cn(
              'border px-2 py-1 font-mono text-[9px]',
              safePoint?.safe
                ? 'border-accent/30 bg-accent-soft text-accent'
                : 'border-warning/30 bg-warning/10 text-warning'
            )}
          >
            {safePoint?.safe ? 'SAFE POINT' : 'REBIND BLOCKED'}
          </span>
        </div>

        <div className="grid gap-px bg-border-subtle sm:grid-cols-2">
          <IdentityCell
            label="当前 Binding"
            value={currentBindingProfileId ?? '系统默认 Provider'}
          />
          <IdentityCell label="Region" value={sessionRegion} />
        </div>

        {latestLoading ? (
          <div className="flex items-center gap-2 px-5 py-4 text-[10px] text-text-muted">
            <LoaderCircle size={12} className="animate-spin" />
            正在读取最近重绑工作流
          </div>
        ) : latest ? (
          <div className="border-t border-border-subtle px-5 py-4">
            <div className="flex items-center justify-between gap-3">
              <div>
                <p className="text-[9px] uppercase tracking-[0.14em] text-text-muted">
                  Latest Rebind
                </p>
                <p className="mt-1 font-mono text-[10px] text-text-secondary">
                  {latest.workflowId}
                </p>
              </div>
              <span
                className={cn(
                  'font-mono text-[10px]',
                  latest.phase === 'FAILED'
                    ? 'text-danger'
                    : latest.phase === 'COMPLETED'
                      ? 'text-accent'
                      : latest.phase === 'DEGRADED'
                        ? 'text-warning'
                        : 'text-text-secondary'
                )}
              >
                {latest.phase}
              </span>
            </div>
            <WorkflowRail workflow={latest} />
            <div className="mt-3 grid gap-px bg-border-subtle text-[9px] sm:grid-cols-2">
              <IdentityCell
                label="目标 Binding"
                value={latest.targetBindingProfileId}
              />
              <IdentityCell
                label="恢复判定"
                value={
                  latest.recoveryResult ??
                  latest.failureReason ??
                  '等待真实状态'
                }
              />
            </div>
          </div>
        ) : null}

        <div className="border-t border-border-subtle px-5 py-4">
          {unavailableReason && (
            <p className="mb-3 flex items-start gap-1.5 text-[9px] leading-4 text-text-muted">
              <CircleAlert size={11} className="mt-0.5 shrink-0 text-warning" />
              {unavailableReason}
            </p>
          )}
          {Boolean(error) && (
            <p className="mb-3 text-[9px] leading-4 text-danger">
              {isSessionApiError(error)
                ? `${error.body.code} · ${String(error.body.details?.reason ?? error.body.message)} · ${error.body.requestId ?? 'no request id'}`
                : 'Proxy Rebind 请求失败'}
            </p>
          )}
          <button
            type="button"
            disabled={!canOpen || bindingsLoading || pending}
            onClick={() => setOpen(true)}
            className="inline-flex h-8 items-center gap-1.5 rounded-[6px] border border-accent/35 px-3 text-[10px] font-medium text-accent hover:bg-accent-soft disabled:cursor-not-allowed disabled:border-border-default disabled:text-text-muted disabled:opacity-45"
          >
            <RotateCcw size={12} />
            安全重绑
          </button>
        </div>
      </section>

      <Dialog.Root open={open} onOpenChange={setOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[88vh] w-[min(520px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 overflow-y-auto rounded-[12px] border border-border-default bg-surface-1 shadow-2xl">
            <div className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
              <div>
                <Dialog.Title className="text-[15px] font-semibold text-text-primary">
                  安全重绑 Proxy
                </Dialog.Title>
                <Dialog.Description className="mt-1 text-[10px] leading-4 text-text-muted">
                  Control Plane 将等待 Safe Point，重启
                  Browser，并验证业务恢复。
                </Dialog.Description>
              </div>
              <Dialog.Close
                className="flex h-7 w-7 items-center justify-center rounded-[5px] text-text-muted hover:bg-surface-2"
                aria-label="关闭 Proxy 重绑"
              >
                <X size={14} />
              </Dialog.Close>
            </div>

            <div className="space-y-4 px-5 py-5">
              <div className="border border-warning/25 bg-warning/5 p-3">
                <p className="flex items-center gap-2 text-[10px] font-medium text-warning">
                  <CircleAlert size={12} />
                  Browser 会重启，TCP / WebSocket 会重新连接
                </p>
                <p className="mt-1.5 text-[9px] leading-4 text-text-muted">
                  Profile 会先 Flush 并创建 Checkpoint；恢复后执行 State Resync
                  与 Business Recovery Validation。失败时 Session
                  保持受控状态，不回退直连。
                </p>
              </div>

              <label className="block">
                <span className="text-[10px] text-text-secondary">
                  目标 Binding
                </span>
                <select
                  value={targetId}
                  onChange={(event) => setTargetId(event.target.value)}
                  className="mt-1.5 h-9 w-full rounded-[6px] border border-border-default bg-surface-2 px-3 text-[10px] text-text-primary outline-none focus:border-accent"
                >
                  <option value="">选择同 Region 的已启用 Binding</option>
                  {targets.map((binding) => (
                    <option
                      key={binding.bindingProfileId}
                      value={binding.bindingProfileId}
                    >
                      {binding.name} · {binding.providerId} ·{' '}
                      {binding.expectedExitIp}
                    </option>
                  ))}
                </select>
              </label>

              <label className="block">
                <span className="text-[10px] text-text-secondary">
                  变更原因
                </span>
                <textarea
                  value={reason}
                  onChange={(event) => setReason(event.target.value)}
                  maxLength={240}
                  rows={3}
                  placeholder="例如：旧出口退役，切换至已审批的新加坡静态出口"
                  className="mt-1.5 w-full resize-none rounded-[6px] border border-border-default bg-surface-2 px-3 py-2 text-[10px] leading-4 text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
                />
                <span className="mt-1 block text-right font-mono text-[8px] text-text-muted">
                  {reason.length}/240
                </span>
              </label>

              <label className="flex cursor-pointer items-start gap-2 border-t border-border-subtle pt-4">
                <input
                  type="checkbox"
                  checked={acknowledged}
                  onChange={(event) => setAcknowledged(event.target.checked)}
                  className="mt-0.5 h-3.5 w-3.5 accent-[var(--color-accent)]"
                />
                <span className="text-[9px] leading-4 text-text-secondary">
                  我确认该操作会重启 Browser，且不会被描述为无感迁移。
                </span>
              </label>
            </div>

            <div className="flex items-center justify-between border-t border-border-subtle bg-canvas/25 px-5 py-4">
              <span className="font-mono text-[9px] text-text-muted">
                {sessionId}
              </span>
              <div className="flex gap-2">
                <Dialog.Close className="h-8 rounded-[6px] border border-border-default px-3 text-[10px] text-text-secondary hover:bg-surface-2">
                  取消
                </Dialog.Close>
                <button
                  type="button"
                  disabled={
                    pending || !targetId || !reason.trim() || !acknowledged
                  }
                  onClick={() => void submit()}
                  className="inline-flex h-8 items-center gap-1.5 rounded-[6px] bg-accent px-3 text-[10px] font-semibold text-canvas hover:bg-accent/90 disabled:opacity-40"
                >
                  {pending ? (
                    <LoaderCircle size={12} className="animate-spin" />
                  ) : (
                    <ShieldCheck size={12} />
                  )}
                  {pending ? '正在创建工作流' : '确认并开始'}
                </button>
              </div>
            </div>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}

function IdentityCell({ label, value }: { label: string; value: string }) {
  return (
    <div className="min-w-0 bg-surface-2 px-4 py-3">
      <p className="text-[8px] uppercase tracking-[0.12em] text-text-muted">
        {label}
      </p>
      <p className="mt-1 truncate font-mono text-[9px] text-text-secondary">
        {value}
      </p>
    </div>
  );
}

function WorkflowRail({ workflow }: { workflow: ProxyRebindView }) {
  const currentIndex = WORKFLOW_STEPS.indexOf(
    workflow.phase as (typeof WORKFLOW_STEPS)[number]
  );
  const terminal = ['COMPLETED', 'DEGRADED'].includes(workflow.phase);
  return (
    <ol
      className="mt-4 grid grid-cols-4 gap-1"
      aria-label={`Proxy 重绑阶段：${workflow.phase}`}
    >
      {WORKFLOW_STEPS.map((step, index) => {
        const reached = terminal || index <= currentIndex;
        return (
          <li key={step} className="min-w-0">
            <span
              className={cn(
                'block h-0.5',
                reached ? 'bg-accent' : 'bg-surface-3'
              )}
            />
            <span
              className={cn(
                'mt-1 block truncate font-mono text-[7px]',
                reached ? 'text-text-secondary' : 'text-text-muted'
              )}
            >
              {step.replace('BUSINESS_', 'BIZ_')}
            </span>
          </li>
        );
      })}
    </ol>
  );
}
