import * as Dialog from '@radix-ui/react-dialog';
import {
  ArrowLeft,
  Bot,
  CircleDot,
  Clock3,
  Crosshair,
  Eye,
  Hand,
  LoaderCircle,
  Monitor,
  Play,
  RefreshCw,
  ShieldAlert,
  Square,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import {
  useSession,
  useBrowserState,
  useRequestHumanTakeover,
  useStartSession,
  useTerminateSession,
} from '@/features/sessions/api/sessionQueries';
import { ApiSessionStateChip } from '@/features/sessions/components/ApiSessionStateChip';
import { DEFAULT_ACTOR_ID, isSessionApiError } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type {
  BrowserStateView,
  OperationView,
  SessionState,
} from '@/types/session';

export function SessionDetailPage() {
  const { id = '' } = useParams();
  const navigate = useNavigate();
  const sessionQuery = useSession(id);
  const browserStateQuery = useBrowserState(
    id,
    sessionQuery.data?.state === 'RUNNING'
  );
  const startMutation = useStartSession(id);
  const terminateMutation = useTerminateSession(id);
  const takeoverMutation = useRequestHumanTakeover(id);
  const [terminateOpen, setTerminateOpen] = useState(false);

  const session = sessionQuery.data;
  const canStart =
    session &&
    ['CREATED', 'HIBERNATED'].includes(session.state) &&
    !session.currentOperation;
  const canTerminate =
    session &&
    !['TERMINATED', 'TERMINATING'].includes(session.state) &&
    !session.currentOperation;
  const takeoverActive = session?.currentOperation?.mode === 'HUMAN_TAKEOVER';
  const takeoverOwned =
    takeoverActive && session.currentOperation?.actorId === DEFAULT_ACTOR_ID;
  const takeoverHeldByOther = takeoverActive && !takeoverOwned;
  const canTakeover =
    session &&
    ['RUNNING', 'DEGRADED'].includes(session.state) &&
    (!session.currentOperation || takeoverOwned);

  const terminate = async () => {
    await terminateMutation.mutateAsync();
    setTerminateOpen(false);
  };

  const openTakeover = async () => {
    try {
      if (!takeoverOwned) await takeoverMutation.mutateAsync();
      navigate(`/remote-desktop?session=${encodeURIComponent(id)}`);
    } catch {
      // React Query exposes the structured error in MutationFeedback.
    }
  };

  return (
    <div>
      <TopContextBar
        title="Session 详情"
        subtitle={
          session
            ? `${session.displayName} · ${session.sessionId}`
            : '读取 Control Plane 权威状态'
        }
      />

      <div className="p-6">
        <Link
          to="/environments"
          className="mb-4 inline-flex items-center gap-1.5 text-[11px] text-text-muted hover:text-accent"
        >
          <ArrowLeft size={13} />
          返回环境管理
        </Link>

        {sessionQuery.isLoading ? (
          <div className="rounded-[10px] border border-border-subtle bg-surface-1">
            <LoadingPanel label="正在读取 Session 详情" />
          </div>
        ) : sessionQuery.error || !session ? (
          <div className="rounded-[10px] border border-border-subtle bg-surface-1">
            <ErrorState
              error={sessionQuery.error}
              title="无法读取 Session"
              onRetry={() => sessionQuery.refetch()}
            />
          </div>
        ) : (
          <>
            <section className="mb-4 border-y border-border-subtle py-5">
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div>
                  <div className="flex items-center gap-3">
                    <h1 className="text-[20px] font-semibold tracking-tight text-text-primary">
                      {session.displayName}
                    </h1>
                    <ApiSessionStateChip state={session.state} />
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-x-5 gap-y-1 text-[11px] text-text-muted">
                    <span>
                      Session{' '}
                      <strong className="font-mono font-normal text-text-secondary">
                        {session.sessionId}
                      </strong>
                    </span>
                    <span>
                      Tenant{' '}
                      <strong className="font-mono font-normal text-text-secondary">
                        {session.tenantId}
                      </strong>
                    </span>
                    <span>
                      Profile{' '}
                      <strong className="font-mono font-normal text-text-secondary">
                        {session.profileId}
                      </strong>
                    </span>
                    <span>
                      Region{' '}
                      <strong className="font-mono font-normal text-text-secondary">
                        {session.region}
                      </strong>
                    </span>
                    <span>
                      Resource{' '}
                      <strong className="font-mono font-normal text-text-secondary">
                        {session.resourceClass}
                      </strong>
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    type="button"
                    onClick={() =>
                      void Promise.all([
                        sessionQuery.refetch(),
                        browserStateQuery.refetch(),
                      ])
                    }
                    disabled={
                      sessionQuery.isFetching || browserStateQuery.isFetching
                    }
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-border-default px-3 text-[11px] text-text-secondary hover:bg-surface-2 disabled:opacity-50"
                  >
                    <RefreshCw
                      size={13}
                      className={cn(
                        (sessionQuery.isFetching ||
                          browserStateQuery.isFetching) &&
                          'animate-spin'
                      )}
                    />
                    刷新
                  </button>
                  <button
                    type="button"
                    onClick={() => void openTakeover()}
                    disabled={!canTakeover || takeoverMutation.isPending}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-accent/35 px-3 text-[11px] text-accent hover:bg-accent-soft disabled:cursor-not-allowed disabled:border-border-default disabled:text-text-muted disabled:opacity-45"
                  >
                    {takeoverMutation.isPending ? (
                      <LoaderCircle size={13} className="animate-spin" />
                    ) : (
                      <Hand size={13} />
                    )}
                    {takeoverHeldByOther
                      ? '他人接管中'
                      : takeoverOwned
                        ? '打开接管'
                        : '人工接管'}
                  </button>
                  {canStart && (
                    <button
                      type="button"
                      onClick={() => startMutation.mutate()}
                      disabled={startMutation.isPending}
                      className="inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[11px] font-medium text-canvas hover:bg-accent/90 disabled:opacity-60"
                    >
                      {startMutation.isPending ? (
                        <LoaderCircle size={13} className="animate-spin" />
                      ) : (
                        <Play size={13} />
                      )}
                      启动 Session
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={() => setTerminateOpen(true)}
                    disabled={!canTerminate}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-danger/35 px-3 text-[11px] text-danger hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-35"
                  >
                    <Square size={12} />
                    终止
                  </button>
                </div>
              </div>
            </section>

            <MutationFeedback
              startError={startMutation.error}
              terminateError={terminateMutation.error}
              takeoverError={takeoverMutation.error}
              hasActiveOperation={Boolean(session.currentOperation)}
              sessionState={session.state}
            />

            <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.4fr)_minmax(320px,0.6fr)]">
              <div className="space-y-4">
                <BrowserStatePanel
                  state={browserStateQuery.data}
                  running={session.state === 'RUNNING'}
                  loading={browserStateQuery.isLoading}
                  error={browserStateQuery.error}
                  onRetry={() => browserStateQuery.refetch()}
                />

                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <div className="mb-4 flex items-center justify-between">
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      运行上下文
                    </h2>
                    <span className="text-[10px] text-text-muted">
                      Control Plane / SessionView
                    </span>
                  </div>
                  <div className="grid grid-cols-2 gap-px overflow-hidden rounded-[8px] border border-border-subtle bg-border-subtle md:grid-cols-4">
                    <ContextMetric
                      label="Context Epoch"
                      value={String(session.contextEpoch)}
                    />
                    <ContextMetric
                      label="Browser Generation"
                      value={String(session.browserGeneration)}
                    />
                    <ContextMetric
                      label="Node"
                      value={session.nodeId || '未分配'}
                    />
                    <ContextMetric
                      label="Runtime Build"
                      value={session.runtimeBuildId || '未绑定'}
                    />
                    <ContextMetric label="状态来源" value="PostgreSQL" />
                    <ContextMetric
                      label="创建时间"
                      value={formatDate(session.createdAt)}
                    />
                    <ContextMetric
                      label="更新时间"
                      value={formatDate(session.updatedAt)}
                    />
                    <ContextMetric
                      label="状态同步"
                      value={session.state === 'RUNNING' ? '每 2 秒' : '已暂停'}
                    />
                  </div>
                </section>

                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <h2 className="text-[13px] font-semibold text-text-primary">
                    能力接入状态
                  </h2>
                  <p className="mt-1 text-[11px] text-text-muted">
                    未完成接口不会展示伪造数据，也不会允许危险写操作。
                  </p>
                  <div className="mt-4 divide-y divide-border-subtle">
                    <CapabilityRow
                      icon={Monitor}
                      title="远程桌面"
                      detail="等待 WebRTC / noVNC 会话契约"
                    />
                    <CapabilityRow
                      icon={Crosshair}
                      title="Browser State"
                      detail={
                        browserStateQuery.data
                          ? `已接入 · v${browserStateQuery.data.stateVersion} · ${browserStateQuery.data.targets.length} targets`
                          : '真实 CDP 状态采集与持久化已接入'
                      }
                      ready
                    />
                    <CapabilityRow
                      icon={Bot}
                      title="Agent 执行"
                      detail="等待 Agent Task 与事件流 API"
                    />
                    <CapabilityRow
                      icon={Hand}
                      title="HumanTakeover"
                      detail="排他 Operation、输入屏障与结束 State Resync 已接入"
                      ready
                    />
                    <CapabilityRow
                      icon={ShieldAlert}
                      title="安全事件"
                      detail="等待脱敏后的 Session Security Timeline"
                    />
                  </div>
                </section>
              </div>

              <aside className="space-y-4">
                <OperationPanel operation={session.currentOperation} />
                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <h2 className="text-[13px] font-semibold text-text-primary">
                    状态时间线
                  </h2>
                  <div className="mt-4 border-l border-border-default pl-4">
                    <TimelineItem
                      title={`Session ${session.state}`}
                      time={formatDate(session.updatedAt)}
                      active
                    />
                    <TimelineItem
                      title="Session 已创建"
                      time={formatDate(session.createdAt)}
                    />
                  </div>
                </section>
              </aside>
            </div>
          </>
        )}
      </div>

      <TerminateDialog
        open={terminateOpen}
        onOpenChange={setTerminateOpen}
        sessionId={id}
        pending={terminateMutation.isPending}
        onConfirm={terminate}
      />
    </div>
  );
}

function OperationPanel({ operation }: { operation?: OperationView }) {
  return (
    <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
      <div className="flex items-center justify-between">
        <h2 className="text-[13px] font-semibold text-text-primary">
          当前 Operation
        </h2>
        {operation && (
          <span className="inline-flex items-center gap-1 text-[10px] text-accent">
            <CircleDot size={10} className="animate-pulse" />
            {operation.state}
          </span>
        )}
      </div>
      {operation ? (
        <dl className="mt-4 divide-y divide-border-subtle">
          <DetailItem label="Operation ID" value={operation.operationId} mono />
          <DetailItem label="Mode" value={operation.mode} />
          <DetailItem label="Owner" value={operation.ownerType} />
          <DetailItem label="Phase" value={operation.phase} />
          <DetailItem
            label="Epoch"
            value={String(operation.operationEpoch)}
            mono
          />
          <DetailItem label="Deadline" value={formatDate(operation.deadline)} />
        </dl>
      ) : (
        <div className="mt-4 rounded-[8px] border border-dashed border-border-default p-5 text-center">
          <Clock3 size={17} className="mx-auto text-text-muted" />
          <p className="mt-2 text-[11px] text-text-secondary">
            没有活跃 Operation
          </p>
          <p className="mt-0.5 text-[10px] text-text-muted">
            写操作将由后端创建并返回真实状态。
          </p>
        </div>
      )}
    </section>
  );
}

function BrowserStatePanel({
  state,
  running,
  loading,
  error,
  onRetry,
}: {
  state: BrowserStateView | null | undefined;
  running: boolean;
  loading: boolean;
  error: unknown;
  onRetry: () => unknown;
}) {
  if (error) {
    return (
      <section className="rounded-[10px] border border-border-subtle bg-surface-1">
        <ErrorState
          error={error}
          title="无法读取 Browser State"
          onRetry={onRetry}
        />
      </section>
    );
  }

  return (
    <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
      <div className="flex flex-wrap items-start justify-between gap-3 border-b border-border-subtle px-5 py-4">
        <div>
          <div className="flex items-center gap-2">
            <Crosshair size={14} className="text-accent" />
            <h2 className="text-[13px] font-semibold text-text-primary">
              Browser State
            </h2>
            {state && (
              <span className="rounded-[4px] border border-accent/25 bg-accent-soft px-1.5 py-0.5 font-mono text-[9px] text-accent">
                {state.stateQuality}
              </span>
            )}
          </div>
          <p className="mt-1 text-[10px] text-text-muted">
            Browser Node 通过内部 CDP 采集，Control Plane
            提供租户隔离的权威快照。
          </p>
        </div>
        {state && (
          <div className="flex gap-4 text-right font-mono">
            <StateCounter label="STATE" value={`v${state.stateVersion}`} />
            <StateCounter
              label="TARGETS"
              value={String(state.targets.length)}
            />
            <StateCounter label="EPOCH" value={String(state.contextEpoch)} />
          </div>
        )}
      </div>

      {loading ? (
        <LoadingPanel label="正在读取 Browser State" />
      ) : !state ? (
        <div className="px-5 py-8 text-center">
          <Eye size={18} className="mx-auto text-text-muted" />
          <p className="mt-2 text-[11px] text-text-secondary">
            {running ? '等待首次 CDP 状态采集' : 'Session 运行后开始采集'}
          </p>
          <p className="mt-1 text-[10px] text-text-muted">
            页面未产生状态时不会展示模拟内容。
          </p>
        </div>
      ) : (
        <>
          <div className="grid gap-px bg-border-subtle md:grid-cols-[1fr_1.5fr]">
            <div className="min-w-0 bg-surface-2 px-5 py-3">
              <p className="text-[9px] uppercase tracking-[0.16em] text-text-muted">
                Document
              </p>
              <p className="mt-1 truncate text-[12px] font-medium text-text-primary">
                {state.title || 'Untitled document'}
              </p>
            </div>
            <div className="min-w-0 bg-surface-2 px-5 py-3">
              <p className="text-[9px] uppercase tracking-[0.16em] text-text-muted">
                URL
              </p>
              <p
                className="mt-1 truncate font-mono text-[10px] text-accent-secondary"
                title={state.url}
              >
                {state.url}
              </p>
            </div>
          </div>

          <div className="overflow-x-auto">
            <table className="w-full min-w-[640px] border-collapse text-left">
              <thead>
                <tr className="border-b border-border-subtle bg-canvas/35 text-[9px] uppercase tracking-[0.12em] text-text-muted">
                  <th className="px-5 py-2.5 font-medium">Target</th>
                  <th className="px-3 py-2.5 font-medium">Role</th>
                  <th className="px-3 py-2.5 font-medium">Bounds</th>
                  <th className="px-3 py-2.5 font-medium">Flags</th>
                  <th className="px-5 py-2.5 text-right font-medium">
                    Revision
                  </th>
                </tr>
              </thead>
              <tbody>
                {state.targets.slice(0, 12).map((target) => (
                  <tr
                    key={target.targetRef}
                    className="border-b border-border-subtle last:border-b-0"
                  >
                    <td className="max-w-[280px] px-5 py-3">
                      <p className="truncate text-[11px] text-text-primary">
                        {target.name || 'Unnamed target'}
                      </p>
                      <p className="mt-0.5 truncate font-mono text-[9px] text-text-muted">
                        {target.targetRef}
                      </p>
                    </td>
                    <td className="px-3 py-3 font-mono text-[10px] text-text-secondary">
                      {target.role}
                    </td>
                    <td className="px-3 py-3 font-mono text-[9px] text-text-muted">
                      {formatBounds(target.bounds)}
                    </td>
                    <td className="px-3 py-3">
                      <div className="flex gap-1">
                        <TargetFlag active={target.visible} label="VIS" />
                        <TargetFlag active={target.enabled} label="ENA" />
                      </div>
                    </td>
                    <td className="px-5 py-3 text-right font-mono text-[10px] text-text-muted">
                      {state.targetRevision}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="flex flex-wrap items-center justify-between gap-2 border-t border-border-subtle bg-canvas/25 px-5 py-2.5">
            <span className="text-[9px] text-text-muted">
              最多展示前 12 个目标 · 总计 {state.targets.length}
            </span>
            <span
              className="max-w-[360px] truncate font-mono text-[9px] text-text-muted"
              title={state.stateHash}
            >
              hash:{state.stateHash}
            </span>
          </div>
        </>
      )}
    </section>
  );
}

function StateCounter({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-[8px] tracking-[0.14em] text-text-muted">{label}</p>
      <p className="mt-0.5 text-[11px] text-text-primary">{value}</p>
    </div>
  );
}

function TargetFlag({ active, label }: { active: boolean; label: string }) {
  return (
    <span
      className={cn(
        'rounded-[3px] border px-1.5 py-0.5 font-mono text-[8px]',
        active
          ? 'border-success/25 bg-success/10 text-success'
          : 'border-border-subtle text-text-muted'
      )}
    >
      {label}
    </span>
  );
}

function MutationFeedback({
  startError,
  terminateError,
  takeoverError,
  hasActiveOperation,
  sessionState,
}: {
  startError: unknown;
  terminateError: unknown;
  takeoverError: unknown;
  hasActiveOperation: boolean;
  sessionState: SessionState;
}) {
  const error = startError || terminateError || takeoverError;
  if (error) {
    const requestId = isSessionApiError(error)
      ? error.body.requestId
      : undefined;
    return (
      <div
        className="mb-4 rounded-[8px] border border-danger/25 bg-danger/8 px-4 py-3 text-[11px] text-danger"
        role="alert"
      >
        <p>
          {error instanceof Error ? error.message : '操作失败，请刷新后重试。'}
        </p>
        {requestId && <p className="mt-1 font-mono">Request ID: {requestId}</p>}
      </div>
    );
  }
  if (sessionState === 'RECOVERING') {
    return (
      <div
        className="mb-4 grid grid-cols-[auto_1fr_auto] items-center gap-3 border-y border-accent-secondary/25 bg-accent-secondary/8 px-4 py-3 text-[11px]"
        role="status"
      >
        <LoaderCircle
          size={14}
          className="animate-spin text-accent-secondary"
        />
        <div>
          <p className="font-medium text-text-primary">
            Browser Supervisor 正在自动恢复 Runtime
          </p>
          <p className="mt-0.5 text-[10px] text-text-muted">
            当前写入已冻结；替代 Runtime 就绪后将递增 Context Epoch
            并重新采集状态。
          </p>
        </div>
        <span className="font-mono text-[9px] tracking-[0.12em] text-accent-secondary">
          RECOVERY
        </span>
      </div>
    );
  }
  if (sessionState === 'FAILED') {
    return (
      <div
        className="mb-4 grid grid-cols-[auto_1fr_auto] items-center gap-3 border-y border-danger/25 bg-danger/8 px-4 py-3 text-[11px]"
        role="alert"
      >
        <ShieldAlert size={14} className="text-danger" />
        <div>
          <p className="font-medium text-text-primary">自动恢复预算已耗尽</p>
          <p className="mt-0.5 text-[10px] text-text-muted">
            Session 已进入安全熔断，请检查 Runtime、Profile 与 Node
            后再人工处理。
          </p>
        </div>
        <span className="font-mono text-[9px] tracking-[0.12em] text-danger">
          CIRCUIT OPEN
        </span>
      </div>
    );
  }
  if (hasActiveOperation) {
    return (
      <div className="mb-4 flex items-center gap-2 rounded-[8px] border border-accent/25 bg-accent-soft px-4 py-3 text-[11px] text-accent">
        <LoaderCircle size={13} className="animate-spin" />
        后端 Operation 正在执行，详情会每 2 秒同步一次。
      </div>
    );
  }
  return null;
}

function ContextMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface-2 p-4">
      <dt className="text-[10px] uppercase tracking-wider text-text-muted">
        {label}
      </dt>
      <dd className="mt-1 font-mono text-[12px] text-text-primary">{value}</dd>
    </div>
  );
}

function DetailItem({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5">
      <dt className="text-[10px] text-text-muted">{label}</dt>
      <dd
        className={cn(
          'max-w-[210px] truncate text-[11px] text-text-secondary',
          mono && 'font-mono'
        )}
      >
        {value}
      </dd>
    </div>
  );
}

function CapabilityRow({
  icon: Icon,
  title,
  detail,
  ready,
}: {
  icon: typeof Monitor;
  title: string;
  detail: string;
  ready?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 py-3">
      <Icon size={14} className="text-text-muted" />
      <span className="w-28 text-[11px] text-text-primary">{title}</span>
      <span className="flex-1 text-[10px] text-text-muted">{detail}</span>
      <span
        className={cn(
          'rounded-full px-2 py-0.5 text-[9px]',
          ready ? 'bg-success/10 text-success' : 'bg-surface-3 text-text-muted'
        )}
      >
        {ready ? '已接入' : '待接入'}
      </span>
    </div>
  );
}

function TimelineItem({
  title,
  time,
  active,
}: {
  title: string;
  time: string;
  active?: boolean;
}) {
  return (
    <div className="relative pb-5 last:pb-0">
      <span
        className={cn(
          'absolute -left-[20.5px] top-1 h-2 w-2 rounded-full ring-4 ring-surface-1',
          active ? 'bg-accent' : 'bg-text-muted'
        )}
      />
      <p className="text-[11px] text-text-primary">{title}</p>
      <p className="mt-0.5 font-mono text-[9px] text-text-muted">{time}</p>
    </div>
  );
}

function TerminateDialog({
  open,
  onOpenChange,
  sessionId,
  pending,
  onConfirm,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  sessionId: string;
  pending: boolean;
  onConfirm: () => Promise<void>;
}) {
  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[440px] -translate-x-1/2 -translate-y-1/2 rounded-[14px] border border-danger/30 bg-surface-1 p-5 shadow-2xl">
          <div className="flex items-start justify-between">
            <div>
              <Dialog.Title className="text-[15px] font-semibold text-text-primary">
                终止 Session？
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-[11px] leading-5 text-text-muted">
                该操作会创建真实 Termination
                Operation，不会由前端立即伪造成功状态。
              </Dialog.Description>
            </div>
            <Dialog.Close
              className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-surface-2"
              aria-label="关闭终止确认"
            >
              <X size={14} />
            </Dialog.Close>
          </div>
          <div className="mt-4 rounded-[8px] bg-surface-2 px-3 py-2 font-mono text-[11px] text-text-secondary">
            {sessionId}
          </div>
          <div className="mt-5 flex justify-end gap-2">
            <Dialog.Close className="h-8 rounded-[7px] border border-border-default px-3 text-[11px] text-text-secondary hover:bg-surface-2">
              取消
            </Dialog.Close>
            <button
              type="button"
              onClick={() => void onConfirm()}
              disabled={pending}
              className="inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-danger px-3 text-[11px] font-medium text-canvas disabled:opacity-60"
            >
              {pending && <LoaderCircle size={12} className="animate-spin" />}
              {pending ? '正在提交' : '确认终止'}
            </button>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatBounds(
  bounds: { x: number; y: number; width: number; height: number } | undefined
) {
  if (!bounds) return '—';
  return `${Math.round(bounds.x)},${Math.round(bounds.y)} · ${Math.round(
    bounds.width
  )}×${Math.round(bounds.height)}`;
}
