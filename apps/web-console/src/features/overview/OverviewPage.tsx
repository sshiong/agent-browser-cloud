import {
  Activity,
  AlertTriangle,
  Bot,
  CircleDot,
  Clock3,
  DollarSign,
  Monitor,
  Play,
  Plus,
  Server,
  ShieldAlert,
} from 'lucide-react';
import { useNavigate } from 'react-router';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingRows } from '@/components/feedback/AsyncStates';
import { MetricCard } from '@/components/ui/MetricCard';
import {
  useWorkspaceOverview,
  useWorkspaceOverviewStream,
} from '@/features/overview/api/overviewQueries';
import { useSessions } from '@/features/sessions/api/sessionQueries';
import { ApiSessionStateChip } from '@/features/sessions/components/ApiSessionStateChip';
import { SessionLifecycleActions } from '@/features/sessions/components/SessionLifecycleActions';
import type { WorkspaceOverviewConnectionState } from '@/types/workspaceOverview';

export function OverviewPage() {
  const navigate = useNavigate();
  const overviewQuery = useWorkspaceOverview();
  const sessionsQuery = useSessions({ limit: 8 });
  const streamState = useWorkspaceOverviewStream(overviewQuery.isSuccess);
  const overview = overviewQuery.data;

  return (
    <div>
      <TopContextBar
        title="总览"
        subtitle="Control Plane、资源与安全态势的权威 Workspace 投影"
      />

      <div className="p-4 sm:p-6">
        {overviewQuery.error ? (
          <div className="rounded-[10px] border border-border-subtle bg-surface-1">
            <ErrorState
              error={overviewQuery.error}
              title="无法读取 Workspace 总览"
              onRetry={() => overviewQuery.refetch()}
            />
          </div>
        ) : (
          <>
            <div className="mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-4 xl:gap-4">
              <MetricCard
                label="运行中 Session"
                value={overview ? overview.sessions.running : '—'}
                change={
                  overview
                    ? `共 ${overview.sessions.total} 个环境`
                    : '读取 PostgreSQL 聚合'
                }
                icon={Monitor}
                iconColor="text-success"
              />
              <MetricCard
                label="待启动 / 恢复"
                value={overview ? overview.sessions.pending : '—'}
                change="Created、Starting、Recovering"
                icon={Clock3}
                iconColor="text-accent-secondary"
              />
              <MetricCard
                label="活跃 Operation"
                value={overview ? overview.operations.active : '—'}
                change="真实写操作生命周期"
                icon={Activity}
                iconColor="text-accent"
              />
              <MetricCard
                label="异常 Session"
                value={overview ? overview.sessions.unhealthy : '—'}
                change="Degraded 与 Failed"
                changeType={
                  overview && overview.sessions.unhealthy > 0
                    ? 'down'
                    : 'neutral'
                }
                icon={AlertTriangle}
                iconColor={
                  overview && overview.sessions.unhealthy > 0
                    ? 'text-danger'
                    : 'text-text-muted'
                }
              />
            </div>

            <div className="grid grid-cols-1 gap-4 xl:grid-cols-[minmax(0,1.45fr)_minmax(320px,0.55fr)]">
              <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
                <div className="flex min-h-14 items-center justify-between border-b border-border-subtle px-4 py-2 sm:px-5">
                  <div>
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      最近 Session
                    </h2>
                    <p className="text-[10px] text-text-muted">
                      最近更新的 8 个环境，点击进入运行详情
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => navigate('/environments')}
                    className="min-h-11 shrink-0 whitespace-nowrap px-2 text-[11px] text-accent hover:underline"
                  >
                    查看全部
                  </button>
                </div>
                {sessionsQuery.isLoading ? (
                  <LoadingRows rows={7} />
                ) : sessionsQuery.error ? (
                  <ErrorState
                    error={sessionsQuery.error}
                    title="无法读取最近 Session"
                    onRetry={() => sessionsQuery.refetch()}
                  />
                ) : (sessionsQuery.data?.items.length ?? 0) === 0 ? (
                  <div className="flex min-h-80 flex-col items-center justify-center px-6 text-center">
                    <Server size={20} className="text-text-muted" />
                    <p className="mt-3 text-[12px] text-text-primary">
                      当前 Workspace 还没有 Session
                    </p>
                    <p className="mt-1 text-[10px] text-text-muted">
                      创建环境后，资源策略、Operation
                      与运行状态会在这里实时呈现。
                    </p>
                    <button
                      type="button"
                      onClick={() => navigate('/environments?create=1')}
                      className="mt-4 inline-flex min-h-11 items-center gap-1.5 rounded-[7px] bg-accent px-4 text-[11px] font-medium text-canvas"
                    >
                      <Plus size={13} />
                      创建第一个环境
                    </button>
                  </div>
                ) : (
                  <div className="divide-y divide-border-subtle">
                    {sessionsQuery.data?.items.map((session) => (
                      <div
                        key={session.sessionId}
                        className="flex min-h-16 items-center gap-3 px-4 py-3 transition-colors hover:bg-surface-2 sm:px-5"
                      >
                        <button
                          type="button"
                          onClick={() =>
                            navigate(`/environments/${session.sessionId}`)
                          }
                          className="grid min-w-0 flex-1 grid-cols-[minmax(0,1fr)_auto] items-center gap-3 text-left sm:grid-cols-[1.2fr_0.8fr_auto]"
                        >
                          <div className="min-w-0">
                            <p className="truncate text-[12px] text-text-primary">
                              {session.displayName}
                            </p>
                            <p className="truncate font-mono text-[11px] text-text-primary">
                              {session.sessionId}
                            </p>
                            <p className="mt-0.5 text-[10px] text-text-muted">
                              {formatDate(session.updatedAt)}
                            </p>
                          </div>
                          <span className="hidden truncate font-mono text-[10px] text-text-secondary sm:block">
                            {session.nodeId || 'Node 未分配'}
                          </span>
                          <ApiSessionStateChip state={session.state} />
                        </button>
                        <SessionLifecycleActions session={session} />
                      </div>
                    ))}
                  </div>
                )}
              </section>

              <div className="space-y-4">
                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <div className="flex items-center justify-between gap-4">
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      数据连接
                    </h2>
                    <StreamState state={streamState} />
                  </div>
                  <dl className="mt-3 divide-y divide-border-subtle">
                    <InfoRow
                      label="数据源"
                      value="PostgreSQL / Control Plane"
                    />
                    <InfoRow label="隔离范围" value="当前认证 Workspace" />
                    <InfoRow
                      label="实时通道"
                      value="可续传 SSE / Last-Event-ID"
                    />
                    <InfoRow
                      label="生成时间"
                      value={
                        overview ? formatDate(overview.generatedAt) : '读取中'
                      }
                    />
                  </dl>
                </section>

                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <div className="flex items-center justify-between gap-4">
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      基础设施与负载
                    </h2>
                    <Server size={14} className="text-text-muted" />
                  </div>
                  <dl className="mt-3 divide-y divide-border-subtle">
                    <InfoRow
                      label="Browser Node"
                      value={
                        overview?.browserNodes.visible
                          ? `${overview.browserNodes.ready} / ${overview.browserNodes.total} 就绪`
                          : overview
                            ? '仅平台管理员可见'
                            : '—'
                      }
                      alert={Boolean(
                        overview?.browserNodes.visible &&
                        overview.browserNodes.constrained
                      )}
                    />
                    <InfoRow
                      label="Node Session 容量"
                      value={
                        overview?.browserNodes.visible
                          ? `${overview.browserNodes.activeSessions} / ${overview.browserNodes.maximumSessions}`
                          : overview
                            ? '仅平台管理员可见'
                            : '—'
                      }
                    />
                    <InfoRow
                      label="Proxy 绑定"
                      value={
                        overview
                          ? `${overview.proxies.boundSessions} Session`
                          : '—'
                      }
                    />
                    <InfoRow
                      label="活跃 Agent"
                      value={overview ? String(overview.agents.active) : '—'}
                    />
                    <InfoRow
                      label="当前每小时成本"
                      value={
                        overview
                          ? formatUsd(overview.cost.currentHourlyUsd)
                          : '—'
                      }
                    />
                  </dl>
                  <div className="mt-3 grid grid-cols-3 gap-2">
                    <SignalTile
                      icon={Bot}
                      label="等待人工"
                      value={overview?.agents.awaitingHuman}
                      alert={Boolean(overview?.agents.awaitingHuman)}
                    />
                    <SignalTile
                      icon={DollarSign}
                      label="缺少定价"
                      value={overview?.cost.activeSessionsWithoutCurrentPrice}
                      alert={Boolean(
                        overview?.cost.activeSessionsWithoutCurrentPrice
                      )}
                    />
                    <SignalTile
                      icon={ShieldAlert}
                      label="严重安全"
                      value={overview?.security.criticalLast24Hours}
                      alert={Boolean(overview?.security.criticalLast24Hours)}
                    />
                  </div>
                </section>

                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <h2 className="text-[13px] font-semibold text-text-primary">
                    快捷操作
                  </h2>
                  <div className="mt-3 space-y-2">
                    <QuickAction
                      icon={Plus}
                      label="新建浏览器环境"
                      detail="创建真实 Session"
                      onClick={() => navigate('/environments?create=1')}
                    />
                    <QuickAction
                      icon={Play}
                      label="管理 Session"
                      detail="启动、查看与停止"
                      onClick={() => navigate('/environments')}
                    />
                  </div>
                </section>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function StreamState({ state }: { state: WorkspaceOverviewConnectionState }) {
  const live = state === 'LIVE';
  const offline = state === 'OFFLINE';
  const label = live
    ? '实时同步'
    : offline
      ? '网络离线'
      : state === 'RECONNECTING'
        ? '正在重连'
        : '正在连接';
  return (
    <span
      className={`inline-flex items-center gap-1.5 text-[10px] ${
        live ? 'text-success' : offline ? 'text-danger' : 'text-warning'
      }`}
    >
      <CircleDot size={10} />
      {label}
    </span>
  );
}

function InfoRow({
  label,
  value,
  alert = false,
}: {
  label: string;
  value: string;
  alert?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5">
      <dt className="text-[10px] text-text-muted">{label}</dt>
      <dd
        className={`text-right text-[10px] ${
          alert ? 'text-warning' : 'text-text-secondary'
        }`}
      >
        {value}
      </dd>
    </div>
  );
}

function SignalTile({
  icon: Icon,
  label,
  value,
  alert,
}: {
  icon: typeof Bot;
  label: string;
  value?: number;
  alert: boolean;
}) {
  return (
    <div className="rounded-[7px] border border-border-subtle bg-surface-2 p-2.5">
      <div className="flex items-center justify-between gap-1">
        <Icon
          size={12}
          className={alert ? 'text-warning' : 'text-text-muted'}
        />
        <span
          className={
            alert ? 'text-[12px] text-warning' : 'text-[12px] text-text-primary'
          }
        >
          {value ?? '—'}
        </span>
      </div>
      <p className="mt-1 truncate text-[9px] text-text-muted">{label}</p>
    </div>
  );
}

function QuickAction({
  icon: Icon,
  label,
  detail,
  onClick,
}: {
  icon: typeof Plus;
  label: string;
  detail: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="flex min-h-12 w-full items-center gap-3 rounded-[8px] border border-border-subtle bg-surface-2 px-3 py-2.5 text-left transition-colors hover:border-accent/30 hover:bg-accent-soft"
    >
      <Icon size={14} className="text-accent" />
      <span>
        <span className="block text-[11px] text-text-primary">{label}</span>
        <span className="block text-[9px] text-text-muted">{detail}</span>
      </span>
    </button>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}

function formatUsd(value: number) {
  return new Intl.NumberFormat('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: 2,
    maximumFractionDigits: 4,
  }).format(value);
}
