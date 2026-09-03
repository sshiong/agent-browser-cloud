import {
  ArrowLeft,
  CircleDot,
  ClipboardCopy,
  Crosshair,
  Eye,
  Hand,
  LoaderCircle,
  Monitor,
  ShieldCheck,
  UserRoundX,
  Users,
  Unplug,
} from 'lucide-react';
import type { ReactNode } from 'react';
import { useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router';
import { ErrorState, LoadingPanel } from '@/components/feedback/AsyncStates';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { currentActorId } from '@/api/session';
import { getRuntimeIdentity, hasAnyRole } from '@/auth/runtimeIdentity';
import {
  useBrowserState,
  useReleaseHumanTakeover,
  useSession,
  useSessionResourceStream,
  useRemoteDesktopParticipants,
  useRemoteDesktopParticipantHistory,
  useRevokeRemoteDesktopParticipant,
  useAgentClipboard,
  useCreateClipboardBridge,
  useCompleteClipboardBridge,
} from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';
import {
  NoVncViewport,
  type DesktopConnectionState,
  type NoVncViewportHandle,
  type UserClipboardObservation,
} from './NoVncViewport';
import type { DesktopQuality } from './desktopQuality';

export function RemoteDesktopPage() {
  const [searchParams] = useSearchParams();
  const sessionId = searchParams.get('session') ?? '';
  const sessionQuery = useSession(sessionId);
  useSessionResourceStream(sessionId, Boolean(sessionId));
  const stateQuery = useBrowserState(
    sessionId,
    ['RUNNING', 'DEGRADED'].includes(sessionQuery.data?.state ?? '')
  );
  const releaseMutation = useReleaseHumanTakeover(sessionId);
  const participantsQuery = useRemoteDesktopParticipants(
    sessionId,
    Boolean(sessionId)
  );
  const participantHistoryQuery = useRemoteDesktopParticipantHistory(
    sessionId,
    Boolean(sessionId)
  );
  const revokeParticipant = useRevokeRemoteDesktopParticipant(sessionId);
  const agentClipboardQuery = useAgentClipboard(sessionId, Boolean(sessionId));
  const createClipboardBridge = useCreateClipboardBridge(sessionId);
  const completeClipboardBridge = useCompleteClipboardBridge(sessionId);
  const desktopRef = useRef<NoVncViewportHandle>(null);
  const [connectionId, setConnectionId] = useState<string>();
  const [userClipboard, setUserClipboard] =
    useState<UserClipboardObservation>();
  const [clipboardBridgeMessage, setClipboardBridgeMessage] =
    useState<string>();
  const [desktopState, setDesktopState] =
    useState<DesktopConnectionState>('DISCONNECTED');
  // Opening VNC is primarily an Agent observer workflow. Default to the
  // server-enforced read-only ticket so merely watching (including incidental
  // pointer movement over the canvas) can never defer Agent commands. The
  // operator can explicitly opt into shared control when human input is needed.
  const [viewOnly, setViewOnly] = useState(true);
  const [quality, setQuality] = useState<DesktopQuality>('SMOOTH');
  const session = sessionQuery.data;
  const takeover = session?.currentOperation?.mode === 'HUMAN_TAKEOVER';
  const takeoverOwned =
    takeover && session.currentOperation?.actorId === currentActorId();
  const takeoverHeldByOther = takeover && !takeoverOwned;
  const runtimeReady = ['RUNNING', 'DEGRADED'].includes(session?.state ?? '');
  const ready = runtimeReady;
  const agentActive = session?.currentOperation?.ownerType === 'AGENT';
  const canRevokeParticipants = hasAnyRole(getRuntimeIdentity()?.roles ?? [], [
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const bindingEpoch = session?.contextEpoch ?? 0;
  const bridgeUserToAgent = async () => {
    if (!connectionId || !userClipboard) return;
    setClipboardBridgeMessage(undefined);
    createClipboardBridge.reset();
    try {
      const result = await createClipboardBridge.mutateAsync({
        direction: 'USER_TO_AGENT',
        purpose: 'HUMAN_ASSISTANCE',
        connectionId,
        expectedAgentClipboardVersion: agentClipboardQuery.data?.version ?? 0,
        value: userClipboard.value,
        userClipboardObservedAt: userClipboard.observedAt,
      });
      setUserClipboard(undefined);
      setClipboardBridgeMessage(
        `已显式写入 AgentClipboard v${result.agentClipboardVersion}；正文未进入审计。`
      );
    } catch {
      // Structured mutation error is rendered below.
    }
  };
  const bridgeAgentToUser = async () => {
    if (!connectionId || !agentClipboardQuery.data) return;
    setClipboardBridgeMessage(undefined);
    createClipboardBridge.reset();
    completeClipboardBridge.reset();
    try {
      const issued = await createClipboardBridge.mutateAsync({
        direction: 'AGENT_TO_USER',
        purpose: 'HUMAN_ASSISTANCE',
        connectionId,
        expectedAgentClipboardVersion: agentClipboardQuery.data.version,
      });
      if (
        !issued.value ||
        !desktopRef.current?.writeUserClipboard(issued.value)
      ) {
        setClipboardBridgeMessage(
          '桥接已签发但 RFB 控制连接不可写；未确认交付，密文将在 60 秒后过期。'
        );
        return;
      }
      await completeClipboardBridge.mutateAsync({
        bridgeId: issued.bridgeId,
        contentHash: issued.contentHash,
      });
      setClipboardBridgeMessage(
        `已显式写入 UserClipboard（${issued.valueLength} 字符）；Agent 会在真人输入空闲 2 秒后继续。`
      );
    } catch {
      // Structured mutation error is rendered below.
    }
  };
  const release = async () => {
    try {
      await releaseMutation.mutateAsync();
    } catch {
      // The structured mutation error is rendered in the safety rail.
    }
  };
  return (
    <div>
      <TopContextBar
        title="远程桌面"
        subtitle={
          session
            ? `${session.displayName} · ${session.sessionId}`
            : 'Agent / Human Collaborative Control'
        }
      />

      {!sessionId ? (
        <EmptySelection />
      ) : sessionQuery.isLoading ? (
        <LoadingPanel label="正在读取 Session 协作控制状态" />
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

            <div className="flex flex-wrap items-center gap-2">
              <label className="flex items-center gap-2 text-[11px] text-text-muted">
                画质
                <select
                  aria-label="远程桌面画质"
                  value={quality}
                  onChange={(event) =>
                    setQuality(event.target.value as DesktopQuality)
                  }
                  className="h-8 border border-border-default bg-surface-2 px-2 text-text-primary"
                >
                  <option value="SMOOTH">流畅优先 · 低画质</option>
                  <option value="BALANCED">均衡 · 压缩画质</option>
                  <option value="SHARP">清晰优先 · 原始像素</option>
                </select>
              </label>
              <div
                className="inline-flex h-8 border border-border-default bg-surface-2"
                aria-label="远程桌面连接模式"
              >
                <button
                  type="button"
                  aria-pressed={!viewOnly}
                  onClick={() => setViewOnly(false)}
                  className={cn(
                    'px-2.5 font-mono text-[9px]',
                    !viewOnly
                      ? 'bg-accent/12 text-accent'
                      : 'text-text-muted hover:text-text-primary'
                  )}
                >
                  协作控制
                </button>
                <button
                  type="button"
                  aria-pressed={viewOnly}
                  onClick={() => setViewOnly(true)}
                  className={cn(
                    'border-l border-border-default px-2.5 font-mono text-[9px]',
                    viewOnly
                      ? 'bg-accent/12 text-accent'
                      : 'text-text-muted hover:text-text-primary'
                  )}
                >
                  只读观察（推荐）
                </button>
              </div>
              <CollaborationStatus ready={ready} agentActive={agentActive} />
              {takeoverOwned && (
                <button
                  type="button"
                  onClick={() => void release()}
                  disabled={releaseMutation.isPending}
                  className="inline-flex h-8 items-center gap-1.5 rounded-[6px] border border-danger/35 px-3 text-[10px] font-medium text-danger hover:bg-danger/10 disabled:cursor-not-allowed disabled:opacity-40"
                >
                  {releaseMutation.isPending ? (
                    <LoaderCircle size={12} className="animate-spin" />
                  ) : (
                    <Unplug size={12} />
                  )}
                  {releaseMutation.isPending ? '正在释放输入' : '结束显式接管'}
                </button>
              )}
            </div>
          </header>

          <div className="grid min-h-0 grid-cols-1 xl:grid-cols-[minmax(0,1fr)_280px]">
            <section className="relative flex min-h-[560px] items-start justify-center overflow-hidden bg-[#080d13] p-6">
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
                    {desktopState}
                  </span>
                </div>
                <div className="aspect-[16/10] min-h-[420px]">
                  {ready ? (
                    <NoVncViewport
                      ref={desktopRef}
                      sessionId={sessionId}
                      bindingEpoch={bindingEpoch}
                      quality={quality}
                      viewOnly={viewOnly}
                      onConnectionState={(next) => {
                        setDesktopState(next);
                        if (next !== 'CONNECTED') setUserClipboard(undefined);
                      }}
                      onConnectionId={setConnectionId}
                      onUserClipboard={setUserClipboard}
                    />
                  ) : (
                    <DisplayProvisioning
                      ready={ready}
                      takeover={takeover}
                      heldByOther={takeoverHeldByOther}
                      title={stateQuery.data?.title}
                      url={stateQuery.data?.url}
                    />
                  )}
                </div>
              </div>
            </section>

            <aside className="border-l border-border-subtle bg-surface-1/55">
              <RailSection title="画质与流畅度">
                <p className="text-[11px] leading-5 text-text-muted">
                  {quality === 'SMOOTH'
                    ? '低画质 JPEG 与色彩降采样，减少传输量，优先降低操作延迟。浏览器分辨率和点击坐标不变。'
                    : quality === 'BALANCED'
                      ? '压缩传输，兼顾文字清晰度与带宽。'
                      : '无损原始像素，适合检查细节；大面积变化时可能明显降低帧率。'}
                  实际帧率受画面变化、网络和配额限制，不保证固定
                  FPS。切换画质不停止 Agent，也不切换控制权限。
                </p>
              </RailSection>
              <RailSection title="协作控制">
                <RailRow
                  icon={Hand}
                  label="Human Input"
                  value={viewOnly ? 'OBSERVE ONLY' : 'PRIORITY WHEN ACTIVE'}
                  active={ready}
                />
                <RailRow
                  icon={ShieldCheck}
                  label="Agent"
                  value={agentActive ? 'ACTIVE / VISIBLE' : 'IDLE'}
                  active={agentActive}
                />
                <RailRow
                  icon={Eye}
                  label="Connection"
                  value={
                    viewOnly
                      ? 'VIEW ONLY / AGENT CONTINUES'
                      : 'CONTROL / HUMAN PRIORITY'
                  }
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
              {releaseMutation.error && (
                <div className="m-4 border border-danger/25 bg-danger/8 p-3 text-[10px] leading-5 text-danger">
                  {releaseMutation.error instanceof Error
                    ? releaseMutation.error.message
                    : '无法结束显式接管，请刷新后重试。'}
                </div>
              )}

              <RailSection title="输入安全">
                <dl className="space-y-3 text-[10px]">
                  <RailMetric label="Input Ledger" value="Node authoritative" />
                  <RailMetric label="Human idle window" value="2 seconds" />
                  <RailMetric
                    label="Agent command"
                    value="Deferred, not stopped"
                  />
                  <RailMetric label="Disconnect cleanup" value="Required" />
                  <RailMetric label="Agent session" value="Preserved" />
                  <RailMetric label="Collaborators" value="Bounded to 8" />
                  <RailMetric label="View-only input" value="Node rejected" />
                </dl>
              </RailSection>

              <RailSection title="显式 Clipboard Bridge">
                <div className="space-y-2.5 text-[9px] leading-4 text-text-muted">
                  <p>
                    AgentClipboard 与 RFB/X11 UserClipboard
                    默认隔离。只有点击下方按钮才跨域， 且绑定当前 Actor、连接与
                    60 秒交付窗口。
                  </p>
                  <div className="grid grid-cols-2 gap-2">
                    <button
                      type="button"
                      onClick={() => void bridgeUserToAgent()}
                      disabled={
                        desktopState !== 'CONNECTED' ||
                        !connectionId ||
                        !userClipboard ||
                        createClipboardBridge.isPending
                      }
                      className="inline-flex min-h-9 items-center justify-center gap-1 border border-border-default bg-surface-2 px-2 text-text-secondary hover:border-accent/45 hover:text-accent disabled:cursor-not-allowed disabled:opacity-35"
                    >
                      <ClipboardCopy size={10} /> 真人 → Agent
                    </button>
                    <button
                      type="button"
                      onClick={() => void bridgeAgentToUser()}
                      disabled={
                        desktopState !== 'CONNECTED' ||
                        viewOnly ||
                        !connectionId ||
                        !agentClipboardQuery.data?.valueLength ||
                        createClipboardBridge.isPending ||
                        completeClipboardBridge.isPending
                      }
                      className="inline-flex min-h-9 items-center justify-center gap-1 border border-border-default bg-surface-2 px-2 text-text-secondary hover:border-accent/45 hover:text-accent disabled:cursor-not-allowed disabled:opacity-35"
                    >
                      <ClipboardCopy size={10} /> Agent → 真人
                    </button>
                  </div>
                  <dl className="grid grid-cols-2 gap-2 font-mono text-[8px]">
                    <RailMetric
                      label="User source"
                      value={
                        userClipboard
                          ? `${userClipboard.value.length} chars / fresh`
                          : 'waiting for RFB cut text'
                      }
                    />
                    <RailMetric
                      label="Agent source"
                      value={
                        agentClipboardQuery.data
                          ? `v${agentClipboardQuery.data.version} / ${agentClipboardQuery.data.valueLength} chars`
                          : 'unavailable'
                      }
                    />
                  </dl>
                  <p>
                    密码和 OTP 不走通用剪贴板；人工发送后仍由一次性敏感输入 API
                    交给 Agent 有界重试代填。人工接管始终可选。
                  </p>
                  {clipboardBridgeMessage && (
                    <p className="border border-success/25 bg-success/8 p-2 text-success">
                      {clipboardBridgeMessage}
                    </p>
                  )}
                  {(createClipboardBridge.error ||
                    completeClipboardBridge.error) && (
                    <p className="border border-danger/25 bg-danger/8 p-2 text-danger">
                      {(createClipboardBridge.error ||
                        completeClipboardBridge.error) instanceof Error
                        ? (
                            createClipboardBridge.error ||
                            completeClipboardBridge.error
                          )?.message
                        : 'Clipboard Bridge 失败，请按结构化错误修正后重试。'}
                    </p>
                  )}
                </div>
              </RailSection>

              <RailSection
                title={`在线参与者 · ${participantsQuery.data?.onlineCount ?? 0}`}
              >
                {participantsQuery.isLoading ? (
                  <p className="font-mono text-[9px] text-text-muted">
                    读取 Node 连接状态…
                  </p>
                ) : participantsQuery.error ? (
                  <button
                    type="button"
                    onClick={() => void participantsQuery.refetch()}
                    className="text-left text-[9px] text-danger hover:underline"
                  >
                    参与者状态不可用，点击重试
                  </button>
                ) : participantsQuery.data?.items.length ? (
                  <div className="space-y-2">
                    {participantsQuery.data.items.map((participant) => (
                      <div
                        key={participant.connectionId}
                        className="border border-border-subtle bg-surface-2/55 p-2.5"
                      >
                        <div className="flex items-start justify-between gap-2">
                          <div className="min-w-0">
                            <p className="truncate text-[10px] text-text-secondary">
                              {participant.actorId || 'Actor unavailable'}
                            </p>
                            <p className="mt-1 truncate font-mono text-[8px] text-text-muted">
                              {participant.connectionId}
                            </p>
                          </div>
                          <span className="inline-flex items-center gap-1 font-mono text-[8px] text-success">
                            <Users size={9} /> {participant.state}
                          </span>
                        </div>
                        <div className="mt-2 flex items-center justify-between gap-2">
                          <span className="font-mono text-[8px] text-text-muted">
                            {participant.viewOnly ? 'VIEW ONLY' : 'CONTROL'} ·{' '}
                            {participant.accessMode === 'EXCLUSIVE_TAKEOVER'
                              ? 'LEGACY / SHARED'
                              : 'SHARED'}
                          </span>
                          {canRevokeParticipants && (
                            <button
                              type="button"
                              onClick={() =>
                                void revokeParticipant.mutateAsync(
                                  participant.connectionId
                                )
                              }
                              disabled={
                                participant.state === 'REVOKE_REQUESTED' ||
                                revokeParticipant.isPending
                              }
                              aria-label={`撤销 ${participant.actorId || participant.connectionId} 的远程桌面连接`}
                              className="inline-flex items-center gap-1 text-[8px] text-danger hover:underline disabled:cursor-not-allowed disabled:opacity-40"
                            >
                              <UserRoundX size={9} />
                              {participant.state === 'REVOKE_REQUESTED'
                                ? '撤销中'
                                : '管理员撤销'}
                            </button>
                          )}
                        </div>
                        <dl className="mt-2 grid grid-cols-3 gap-1 border-t border-border-subtle pt-2 font-mono text-[8px] text-text-muted">
                          <div>
                            <dt>FORWARDED</dt>
                            <dd className="mt-0.5 text-text-secondary">
                              {(
                                (participant.forwardedBytes ?? 0) /
                                (1024 * 1024)
                              ).toFixed(2)}{' '}
                              MiB
                            </dd>
                          </div>
                          <div>
                            <dt>QUOTA WAIT</dt>
                            <dd className="mt-0.5 text-text-secondary">
                              {(
                                participant.quotaWaitMillis ?? 0
                              ).toLocaleString()}{' '}
                              ms
                            </dd>
                          </div>
                          <div>
                            <dt>EGRESS COST</dt>
                            <dd className="mt-0.5 text-text-secondary">
                              ${(participant.egressCostUsd ?? 0).toFixed(6)}
                              {(participant.unpricedForwardedBytes ?? 0) > 0
                                ? ' · RATE MISSING'
                                : ''}
                            </dd>
                          </div>
                        </dl>
                      </div>
                    ))}
                  </div>
                ) : (
                  <p className="font-mono text-[9px] text-text-muted">
                    暂无已确认在线连接
                  </p>
                )}
                {revokeParticipant.error && (
                  <p className="mt-2 text-[9px] leading-4 text-danger">
                    {revokeParticipant.error instanceof Error
                      ? revokeParticipant.error.message
                      : '撤销请求失败'}
                  </p>
                )}
              </RailSection>
              <RailSection title="最近连接历史">
                {participantHistoryQuery.isLoading ? (
                  <p className="font-mono text-[9px] text-text-muted">
                    读取终态连接记录…
                  </p>
                ) : participantHistoryQuery.error ? (
                  <button
                    type="button"
                    onClick={() => void participantHistoryQuery.refetch()}
                    className="text-left text-[9px] text-danger hover:underline"
                  >
                    历史记录不可用，点击重试
                  </button>
                ) : participantHistoryQuery.data?.pages.some(
                    (page) => page.items.length > 0
                  ) ? (
                  <div className="space-y-2">
                    {participantHistoryQuery.data.pages
                      .flatMap((page) => page.items)
                      .map((participant) => (
                        <div
                          key={participant.connectionId}
                          className="border-l border-border-default pl-2.5"
                        >
                          <div className="flex items-center justify-between gap-2">
                            <span className="truncate text-[9px] text-text-secondary">
                              {participant.actorId || participant.connectionId}
                            </span>
                            <span className="font-mono text-[8px] text-text-muted">
                              {participant.state}
                            </span>
                          </div>
                          <p className="mt-1 font-mono text-[8px] text-text-muted">
                            {new Date(participant.observedAt).toLocaleString()}{' '}
                            · {participant.reason}
                          </p>
                        </div>
                      ))}
                    {participantHistoryQuery.hasNextPage && (
                      <button
                        type="button"
                        onClick={() =>
                          void participantHistoryQuery.fetchNextPage()
                        }
                        disabled={participantHistoryQuery.isFetchingNextPage}
                        className="text-[9px] text-accent hover:underline disabled:opacity-40"
                      >
                        {participantHistoryQuery.isFetchingNextPage
                          ? '加载中…'
                          : '加载更早记录'}
                      </button>
                    )}
                  </div>
                ) : (
                  <p className="font-mono text-[9px] text-text-muted">
                    暂无已结束连接
                  </p>
                )}
              </RailSection>
            </aside>
          </div>
        </main>
      )}
    </div>
  );
}

function DisplayProvisioning({
  ready,
  takeover,
  heldByOther,
  title,
  url,
}: {
  ready: boolean;
  takeover: boolean;
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
        ) : (
          <LoaderCircle
            size={24}
            className="mx-auto animate-spin text-warning"
          />
        )}
        <p className="mt-4 text-[12px] font-medium text-text-primary">
          {ready
            ? takeover
              ? '显式人工接管已就绪'
              : '协作远程桌面已就绪'
            : heldByOther
              ? '显式接管由其他 Actor 持有'
              : takeover
                ? '正在建立显式接管屏障'
                : 'Session Runtime 尚未就绪'}
        </p>
        <p className="mt-2 text-[10px] leading-5 text-text-muted">
          {ready && !takeover
            ? '连接不会停止 Agent；仅在真人实际输入时短暂获得优先级。'
            : ready
              ? '这是明确的 HumanTakeover；Agent 已按治理流程交接执行权。'
              : heldByOther
                ? '当前 Actor 不能进入或释放他人的显式接管。'
                : takeover
                  ? 'Browser Node 正在完成输入释放与接管前状态同步。'
                  : '远程桌面仅可连接 RUNNING 或 DEGRADED Session。'}
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

function CollaborationStatus({
  ready,
  agentActive,
}: {
  ready: boolean;
  agentActive: boolean;
}) {
  return (
    <span
      className={cn(
        'inline-flex h-8 items-center gap-1.5 border px-2.5 font-mono text-[9px] tracking-[0.1em]',
        ready
          ? 'border-success/25 bg-success/8 text-success'
          : 'border-border-default text-text-muted'
      )}
    >
      <CircleDot size={9} className={ready ? 'animate-pulse' : ''} />
      {ready
        ? agentActive
          ? 'AGENT + HUMAN READY'
          : 'HUMAN READY'
        : 'SESSION NOT READY'}
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
          从运行中的 Session 详情打开协作远程桌面，无需先暂停或交接 Agent。
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
