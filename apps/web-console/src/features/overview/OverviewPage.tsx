import {
  Activity,
  AlertTriangle,
  CircleDot,
  Clock3,
  Monitor,
  Play,
  Plus,
  Server,
} from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingRows } from '@/components/feedback/AsyncStates';
import { MetricCard } from '@/components/ui/MetricCard';
import { ApiSessionStateChip } from '@/features/sessions/components/ApiSessionStateChip';
import { useSessions } from '@/features/sessions/api/sessionQueries';

export function OverviewPage() {
  const navigate = useNavigate();
  const sessionsQuery = useSessions({ limit: 100 });
  const sessions = sessionsQuery.data?.items ?? [];

  const counts = {
    running: sessions.filter((session) => session.state === 'RUNNING').length,
    pending: sessions.filter((session) =>
      ['CREATED', 'STARTING', 'RECOVERING'].includes(session.state)
    ).length,
    unhealthy: sessions.filter((session) =>
      ['DEGRADED', 'FAILED'].includes(session.state)
    ).length,
    operations: sessions.filter((session) => session.currentOperation).length,
  };

  return (
    <div>
      <TopContextBar
        title="总览"
        subtitle="Control Plane Session 与 Operation 实时概况"
      />

      <div className="p-6">
        {sessionsQuery.error ? (
          <div className="rounded-[10px] border border-border-subtle bg-surface-1">
            <ErrorState
              error={sessionsQuery.error}
              title="无法读取总览"
              onRetry={() => sessionsQuery.refetch()}
            />
          </div>
        ) : (
          <>
            <div className="mb-6 grid grid-cols-4 gap-4">
              <MetricCard
                label="运行中 Session"
                value={sessionsQuery.isLoading ? '—' : counts.running}
                change="真实 Control Plane 状态"
                icon={Monitor}
                iconColor="text-success"
              />
              <MetricCard
                label="待启动 / 恢复"
                value={sessionsQuery.isLoading ? '—' : counts.pending}
                change="Created、Starting、Recovering"
                icon={Clock3}
                iconColor="text-accent-secondary"
              />
              <MetricCard
                label="活跃 Operation"
                value={sessionsQuery.isLoading ? '—' : counts.operations}
                change="写操作生命周期"
                icon={Activity}
                iconColor="text-accent"
              />
              <MetricCard
                label="异常 Session"
                value={sessionsQuery.isLoading ? '—' : counts.unhealthy}
                change="Degraded 与 Failed"
                changeType={counts.unhealthy > 0 ? 'down' : 'neutral'}
                icon={AlertTriangle}
                iconColor={
                  counts.unhealthy > 0 ? 'text-danger' : 'text-text-muted'
                }
              />
            </div>

            <div className="grid grid-cols-[minmax(0,1.5fr)_minmax(300px,0.5fr)] gap-4">
              <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
                <div className="flex h-12 items-center justify-between border-b border-border-subtle px-5">
                  <div>
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      最近 Session
                    </h2>
                    <p className="text-[10px] text-text-muted">
                      按创建时间由 Control Plane 返回
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => navigate('/environments')}
                    className="text-[11px] text-accent hover:underline"
                  >
                    查看全部
                  </button>
                </div>
                {sessionsQuery.isLoading ? (
                  <LoadingRows rows={7} />
                ) : sessions.length === 0 ? (
                  <div className="flex min-h-80 flex-col items-center justify-center text-center">
                    <Server size={20} className="text-text-muted" />
                    <p className="mt-3 text-[12px] text-text-primary">
                      Control Plane 中还没有 Session
                    </p>
                    <button
                      type="button"
                      onClick={() => navigate('/environments?create=1')}
                      className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[11px] font-medium text-canvas"
                    >
                      <Plus size={13} />
                      创建第一个环境
                    </button>
                  </div>
                ) : (
                  <div className="divide-y divide-border-subtle">
                    {sessions.slice(0, 8).map((session) => (
                      <button
                        key={session.sessionId}
                        type="button"
                        onClick={() =>
                          navigate(`/environments/${session.sessionId}`)
                        }
                        className="grid w-full grid-cols-[1.2fr_0.8fr_0.8fr_auto] items-center gap-4 px-5 py-3 text-left transition-colors hover:bg-surface-2"
                      >
                        <div className="min-w-0">
                          <p className="truncate font-mono text-[11px] text-text-primary">
                            {session.sessionId}
                          </p>
                          <p className="mt-0.5 text-[10px] text-text-muted">
                            {formatDate(session.updatedAt)}
                          </p>
                        </div>
                        <span className="truncate font-mono text-[10px] text-text-secondary">
                          {session.nodeId || 'Node 未分配'}
                        </span>
                        <span className="truncate text-[10px] text-text-muted">
                          {session.currentOperation?.mode || '无活跃操作'}
                        </span>
                        <ApiSessionStateChip state={session.state} />
                      </button>
                    ))}
                  </div>
                )}
              </section>

              <div className="space-y-4">
                <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
                  <div className="flex items-center justify-between">
                    <h2 className="text-[13px] font-semibold text-text-primary">
                      连接状态
                    </h2>
                    <span className="inline-flex items-center gap-1.5 text-[10px] text-success">
                      <CircleDot size={10} />
                      API 可达
                    </span>
                  </div>
                  <dl className="mt-4 divide-y divide-border-subtle">
                    <InfoRow
                      label="数据源"
                      value="PostgreSQL / Control Plane"
                    />
                    <InfoRow label="租户隔离" value="X-Tenant-Id（本地开发）" />
                    <InfoRow label="列表上限" value="当前总览最多 100 条" />
                    <InfoRow label="实时方式" value="Query 同步；SSE 待接入" />
                  </dl>
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
                      detail="启动、查看与终止"
                      onClick={() => navigate('/environments')}
                    />
                  </div>
                </section>

                <section className="rounded-[10px] border border-warning/20 bg-warning/6 p-4">
                  <p className="text-[11px] font-medium text-warning">
                    指标能力仍不完整
                  </p>
                  <p className="mt-1 text-[10px] leading-4 text-text-muted">
                    Browser Node、Proxy、Agent、成本与安全汇总 API
                    尚未实现，因此这里不显示固定 Mock 数字。
                  </p>
                </section>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 py-2.5">
      <dt className="text-[10px] text-text-muted">{label}</dt>
      <dd className="text-right text-[10px] text-text-secondary">{value}</dd>
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
      className="flex w-full items-center gap-3 rounded-[8px] border border-border-subtle bg-surface-2 px-3 py-2.5 text-left transition-colors hover:border-accent/30 hover:bg-accent-soft"
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
