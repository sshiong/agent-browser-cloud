import {
  ArrowLeft,
  CircleDot,
  Crosshair,
  Hand,
  LoaderCircle,
  Monitor,
  ShieldCheck,
  Unplug,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { useCallback, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { DEFAULT_ACTOR_ID } from '@/api/session';
import {
  useBrowserState,
  useReleaseHumanTakeover,
  useSession,
} from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';
import { NoVncViewport, type DesktopConnectionState } from './NoVncViewport';

export function RemoteDesktopPage() {
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get('session') ?? '';
  const sessionQuery = useSession(sessionId);
  const stateQuery = useBrowserState(
    sessionId,
    sessionQuery.data?.state === 'RUNNING'
  );
  const releaseMutation = useReleaseHumanTakeover(sessionId);
  const [desktopState, setDesktopState] =
    useState<DesktopConnectionState>('DISCONNECTED');
  const session = sessionQuery.data;
  const takeover = session?.currentOperation?.mode === 'HUMAN_TAKEOVER';
  const takeoverOwned =
    takeover && session.currentOperation?.actorId === DEFAULT_ACTOR_ID;
  const takeoverHeldByOther = takeover && !takeoverOwned;
  const ready =
    takeoverOwned && session.currentOperation?.phase === 'EXECUTING';
  const releasing =
    releaseMutation.isPending ||
    (takeoverOwned && session.currentOperation?.phase === 'COMPLETING');

  const release = async () => {
    try {
      await releaseMutation.mutateAsync();
    } catch {
      // The structured mutation error is rendered below.
    }
  };
  const releaseAfterDisconnect = useCallback(() => {
    if (!releaseMutation.isPending) {
      void releaseMutation.mutateAsync().catch(() => undefined);
    }
  }, [releaseMutation]);

  return (
    <div>
      <TopContextBar
        title="远程桌面"
        subtitle={
          session
            ? `${session.displayName} · ${session.sessionId}`
            : 'HumanTakeover / Exclusive Operation'
        }
      />

      {!sessionId ? (
        <EmptySelection />
      ) : sessionQuery.isLoading ? (
        <LoadingPanel label="正在校验 HumanTakeover 权限" />
      ) : sessionQuery.error || !session ? (
        <ErrorState
          error={sessionQuery.error}
          title="无法打开远程桌面"
          onRetry={() => sessionQuery.refetch()}
        />
      ) : (
        <main className="grid min-h-[calc(100vh-56px)] grid-rows-[auto_1fr]">
          <header className="flex flex-wrap items-center justify-between gap-3 border-b border-border-subtle bg-surface-1/55 px-5 py-3">
            <div className="flex min-w-0 items-center gap-4">
              <Link
                to={`/environments/${sessionId}`}
                className="inline-flex items-center gap-1.5 text-[10px] text-text-muted hover:text-accent"
              >
                <ArrowLeft size={12} />
                Session
              </Link>
              <div className="h-4 w-px bg-border-default" />
              <div className="min-w-0">
                <p className="truncate text-[12px] font-medium text-text-primary">
                  {session.displayName}
                </p>
                <p className="mt-0.5 truncate font-mono text-[9px] text-text-muted">
                  {session.sessionId} · epoch {session.contextEpoch} ·
                  generation {session.browserGeneration}
                </p>
              </div>
            </div>

            <div className="flex items-center gap-2">
              <TakeoverStatus
                ready={ready}
                takeover={takeoverOwned}
                heldByOther={takeoverHeldByOther}
              />
              <button
                type="button"
                onClick={() => void release()}
                disabled={!takeoverOwned || releasing}
                className="inline-flex h-8 items-center gap-1.5 rounded-[6px] border border-danger/35 px-3 text-[10px] font-medium text-danger hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-40"
              >
                {releasing ? (
                  <LoaderCircle size={12} className="animate-spin" />
                ) : (
                  <Unplug size={12} />
                )}
                {releasing ? '正在释放输入' : '结束接管'}
              </button>
            </div>
          </header>

          <div className="grid min-h-0 grid-cols-1 xl:grid-cols-[minmax(0,1fr)_280px]">
            <section className="relative flex min-h-[560px] items-center justify-center overflow-hidden bg-[#080d13] p-6">
              <div className="absolute inset-0 bg-grid opacity-45" />
              <div className="absolute inset-x-0 top-0 h-px bg-accent/25" />
              <div className="relative w-full max-w-5xl border border-border-default bg-canvas shadow-[0_24px_80px_rgba(0,0,0,0.32)]">
                <div className="flex h-8 items-center justify-between border-b border-border-subtle bg-surface-2 px-3">
                  <div className="flex items-center gap-2 font-mono text-[9px] text-text-muted">
                    <span
                      className={cn(
                        'h-1.5 w-1.5 rounded-full',
                        ready ? 'bg-success' : 'bg-warning'
                      )}
                    />
                    DISPLAY / PRIMARY
                  </div>
                  <span className="font-mono text-[9px] text-text-muted">
                    1440 × 900 · {desktopState}
                  </span>
                </div>
                <div className="aspect-[16/10] min-h-[420px]">
                  {ready && session.currentOperation ? (
                    <NoVncViewport
                      sessionId={sessionId}
                      operationEpoch={session.currentOperation.operationEpoch}
                      onConnectionState={setDesktopState}
                      onUnexpectedDisconnect={releaseAfterDisconnect}
                    />
                  ) : (
                    <DisplayProvisioning
                      ready={ready}
                      heldByOther={takeoverHeldByOther}
                      title={stateQuery.data?.title}
                      url={stateQuery.data?.url}
                    />
                  )}
                </div>
              </div>
            </section>

            <aside className="border-l border-border-subtle bg-surface-1/55">
              <RailSection title="控制边界">
                <RailRow
                  icon={Hand}
                  label="Owner"
                  value={session.currentOperation?.actorId || '未持有'}
                  active={takeoverOwned}
                />
                <RailRow
                  icon={ShieldCheck}
                  label="Operation"
                  value={session.currentOperation?.phase || 'INACTIVE'}
                  active={ready}
                />
                <RailRow
                  icon={Crosshair}
                  label="State"
                  value={
                    stateQuery.data
                      ? `v${stateQuery.data.stateVersion} / ${stateQuery.data.stateQuality}`
                      : '等待采集'
                  }
                  active={Boolean(stateQuery.data)}
                />
              </RailSection>

              <RailSection title="输入安全">
                <dl className="space-y-3 text-[10px]">
                  <RailMetric label="Input Ledger" value="Node authoritative" />
                  <RailMetric label="Idle watchdog" value="5 seconds" />
                  <RailMetric label="Release barrier" value="Required" />
                  <RailMetric label="End resync" value="Required" />
                </dl>
              </RailSection>

              {releaseMutation.error && (
                <div className="m-4 border border-danger/25 bg-danger/8 p-3 text-[10px] leading-5 text-danger">
                  {releaseMutation.error instanceof Error
                    ? releaseMutation.error.message
                    : '无法结束接管，请刷新后重试。'}
                </div>
              )}
            </aside>
          </div>
        </main>
      )}
    </div>
  );
}

function DisplayProvisioning({
  ready,
  heldByOther,
  title,
  url,
}: {
  ready: boolean;
  heldByOther: boolean;
  title?: string;
  url?: string;
}) {
  return (
    <div className="relative flex h-full min-h-[420px] items-center justify-center overflow-hidden">
      <div className="absolute inset-0 bg-[radial-gradient(circle_at_center,rgba(85,214,190,0.045),transparent_52%)]" />
      <div className="relative max-w-md px-8 text-center">
        {ready ? (
          <Monitor size={24} className="mx-auto text-accent" />
        ) : heldByOther ? (
          <ShieldCheck size={24} className="mx-auto text-warning" />
        ) : (
          <LoaderCircle
            size={24}
            className="mx-auto animate-spin text-warning"
          />
        )}
        <p className="mt-4 text-[12px] font-medium text-text-primary">
          {ready
            ? '接管输入屏障已建立'
            : heldByOther
              ? '此 Session 已由其他 Actor 接管'
              : '正在建立排他接管边界'}
        </p>
        <p className="mt-2 text-[10px] leading-5 text-text-muted">
          {ready
            ? 'Control Plane 已确认 Input Ledger 清空和 State Resync，正在签发短期 noVNC 数据面票据。'
            : heldByOther
              ? '当前 Actor 无权读取或释放他人的控制边界。请返回 Session 详情等待接管结束。'
              : 'Browser Node 正在释放 Agent 遗留按键并采集接管前状态。'}
        </p>
        {(title || url) && (
          <div className="mt-5 border-y border-border-subtle py-3 text-left">
            <p className="truncate text-[10px] text-text-secondary">{title}</p>
            <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
              {url}
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

function TakeoverStatus({
  ready,
  takeover,
  heldByOther,
}: {
  ready: boolean;
  takeover: boolean;
  heldByOther: boolean;
}) {
  return (
    <span
      className={cn(
        'inline-flex h-8 items-center gap-1.5 border px-2.5 font-mono text-[9px] tracking-[0.1em]',
        ready
          ? 'border-success/25 bg-success/8 text-success'
          : heldByOther
            ? 'border-warning/25 bg-warning/8 text-warning'
            : takeover
              ? 'border-warning/25 bg-warning/8 text-warning'
              : 'border-border-default text-text-muted'
      )}
    >
      <CircleDot
        size={9}
        className={takeover || heldByOther ? 'animate-pulse' : ''}
      />
      {ready
        ? 'CONTROL ACQUIRED'
        : heldByOther
          ? 'LOCKED BY OTHER'
          : takeover
            ? 'PREPARING'
            : 'NO CONTROL'}
    </span>
  );
}

function RailSection({
  title,
  children,
}: {
  title: string;
  children: ReactNode;
}) {
  return (
    <section className="border-b border-border-subtle p-4">
      <h2 className="mb-4 text-[9px] uppercase tracking-[0.16em] text-text-muted">
        {title}
      </h2>
      {children}
    </section>
  );
}

function RailRow({
  icon: Icon,
  label,
  value,
  active,
}: {
  icon: typeof Hand;
  label: string;
  value: string;
  active: boolean;
}) {
  return (
    <div className="flex items-center gap-3 border-t border-border-subtle py-3 first:border-t-0 first:pt-0">
      <Icon size={13} className={active ? 'text-accent' : 'text-text-muted'} />
      <div className="min-w-0 flex-1">
        <p className="text-[9px] text-text-muted">{label}</p>
        <p className="mt-0.5 truncate font-mono text-[10px] text-text-secondary">
          {value}
        </p>
      </div>
    </div>
  );
}

function RailMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-3">
      <dt className="text-text-muted">{label}</dt>
      <dd className="font-mono text-[9px] text-text-secondary">{value}</dd>
    </div>
  );
}

function EmptySelection() {
  return (
    <div className="flex h-[calc(100vh-56px)] items-center justify-center">
      <div className="max-w-sm text-center">
        <Monitor size={30} className="mx-auto text-text-muted" />
        <h2 className="mt-4 text-[14px] font-medium text-text-primary">
          选择运行中的 Session
        </h2>
        <p className="mt-2 text-[11px] leading-5 text-text-muted">
          HumanTakeover 必须从 Session 详情发起，确保租户、Actor 和排他
          Operation 已绑定。
        </p>
        <Link
          to="/environments"
          className="mt-5 inline-flex h-8 items-center border border-border-default px-3 text-[10px] text-text-secondary hover:bg-surface-2"
        >
          前往环境管理
        </Link>
      </div>
    </div>
  );
}
