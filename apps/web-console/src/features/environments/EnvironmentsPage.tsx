import { useMemo, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import {
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  LoaderCircle,
  Play,
  Plus,
  Search,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { CreateSessionDialog } from '@/features/sessions/components/CreateSessionDialog';
import { ApiSessionStateChip } from '@/features/sessions/components/ApiSessionStateChip';
import {
  useSessions,
  useStartSession,
} from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';
import type { SessionState, SessionView } from '@/types/session';

const PAGE_SIZE = 20;

const filters: { label: string; value?: SessionState }[] = [
  { label: '全部' },
  { label: '已创建', value: 'CREATED' },
  { label: '启动中', value: 'STARTING' },
  { label: '运行中', value: 'RUNNING' },
  { label: '降级', value: 'DEGRADED' },
  { label: '已终止', value: 'TERMINATED' },
  { label: '失败', value: 'FAILED' },
];

export function EnvironmentsPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [state, setState] = useState<SessionState | undefined>();
  const [search, setSearch] = useState('');
  const [page, setPage] = useState(0);
  const query = useSessions({
    state,
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  });
  const createOpen = searchParams.get('create') === '1';

  const visibleItems = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return query.data?.items ?? [];
    return (query.data?.items ?? []).filter((session) =>
      [
        session.displayName,
        session.sessionId,
        session.tenantId,
        session.profileId,
        session.region,
        session.resourceClass,
        session.nodeId,
        session.runtimeBuildId,
        session.currentOperation?.operationId,
      ]
        .filter(Boolean)
        .some((value) => value?.toLowerCase().includes(needle))
    );
  }, [query.data?.items, search]);

  const setCreateOpen = (open: boolean) => {
    const next = new URLSearchParams(searchParams);
    if (open) next.set('create', '1');
    else next.delete('create');
    setSearchParams(next, { replace: true });
  };

  const selectFilter = (nextState?: SessionState) => {
    setState(nextState);
    setPage(0);
  };

  const total = query.data?.total ?? 0;
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div>
      <TopContextBar
        title="环境管理"
        subtitle="来自 Control Plane 的真实 Session、Operation 与 Runtime 状态"
      />

      <div className="p-6">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div
            className="flex flex-wrap items-center gap-1"
            role="group"
            aria-label="Session 状态筛选"
          >
            {filters.map((filter) => (
              <button
                key={filter.label}
                type="button"
                onClick={() => selectFilter(filter.value)}
                className={cn(
                  'rounded-md px-3 py-1.5 text-[12px] font-medium transition-colors',
                  state === filter.value
                    ? 'bg-accent-soft text-accent'
                    : 'text-text-muted hover:bg-surface-2 hover:text-text-secondary'
                )}
              >
                {filter.label}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-2">
            <label className="relative">
              <span className="sr-only">搜索当前页 Session</span>
              <Search
                size={14}
                className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
              />
              <input
                type="search"
                placeholder="搜索名称、Session、Profile 或 Node"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                className="h-8 w-[300px] rounded-md border border-border-subtle bg-surface-2 pl-9 pr-3 text-[12px] text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
              />
            </label>

            <button
              type="button"
              onClick={() => setCreateOpen(true)}
              className="flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[12px] font-medium text-canvas transition-colors hover:bg-accent/90"
            >
              <Plus size={14} />
              新建环境
            </button>
          </div>
        </div>

        <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
          <div className="flex min-h-11 items-center justify-between border-b border-border-subtle bg-surface-2 px-4">
            <p className="text-[11px] text-text-muted">
              {query.data ? (
                <>
                  共{' '}
                  <span className="font-mono text-text-secondary">
                    {query.data.total}
                  </span>{' '}
                  个 Session
                  {search && ' · 搜索仅作用于当前页'}
                </>
              ) : (
                '正在连接 Control Plane'
              )}
            </p>
            {query.isFetching && !query.isLoading && (
              <span className="inline-flex items-center gap-1.5 text-[10px] text-text-muted">
                <LoaderCircle size={11} className="animate-spin text-accent" />
                同步中
              </span>
            )}
          </div>

          {query.isLoading ? (
            <LoadingRows />
          ) : query.error ? (
            <ErrorState error={query.error} onRetry={() => query.refetch()} />
          ) : query.data?.items.length === 0 ? (
            <EmptyState
              title={state ? '当前状态下没有 Session' : '还没有浏览器环境'}
              description={
                state
                  ? '清除状态筛选查看其他 Session，或创建一个新的隔离环境。'
                  : '创建第一个真实 Session，配置 Profile、区域与资源等级。'
              }
              action={
                <button
                  type="button"
                  onClick={() => setCreateOpen(true)}
                  className="inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[12px] font-medium text-canvas"
                >
                  <Plus size={13} />
                  新建环境
                </button>
              }
            />
          ) : visibleItems.length === 0 ? (
            <EmptyState
              title="当前页没有匹配结果"
              description="调整搜索条件，或切换到其他分页继续查找。"
              action={
                <button
                  type="button"
                  onClick={() => setSearch('')}
                  className="text-[12px] text-accent hover:underline"
                >
                  清除搜索
                </button>
              }
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[1160px]">
                <thead>
                  <tr className="border-b border-border-subtle bg-surface-2">
                    {[
                      '环境',
                      'Profile / 租户',
                      '部署',
                      'Runtime / Node',
                      'Context',
                      '当前 Operation',
                      '状态',
                      '最近更新',
                      '操作',
                    ].map((label) => (
                      <th
                        key={label}
                        className="px-4 py-3 text-left text-[10px] font-medium uppercase tracking-wider text-text-muted"
                      >
                        {label}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {visibleItems.map((session) => (
                    <SessionRow key={session.sessionId} session={session} />
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {query.data && query.data.total > 0 && (
            <div className="flex h-12 items-center justify-between border-t border-border-subtle px-4">
              <span className="text-[11px] text-text-muted">
                第 {page + 1} / {pageCount} 页 · 每页 {PAGE_SIZE} 条
              </span>
              <div className="flex items-center gap-1">
                <button
                  type="button"
                  onClick={() => setPage((current) => Math.max(0, current - 1))}
                  disabled={page === 0}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-30"
                  aria-label="上一页"
                >
                  <ChevronLeft size={14} />
                </button>
                <button
                  type="button"
                  onClick={() =>
                    setPage((current) => Math.min(pageCount - 1, current + 1))
                  }
                  disabled={page + 1 >= pageCount}
                  className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-30"
                  aria-label="下一页"
                >
                  <ChevronRight size={14} />
                </button>
              </div>
            </div>
          )}
        </div>
      </div>

      <CreateSessionDialog open={createOpen} onOpenChange={setCreateOpen} />
    </div>
  );
}

function SessionRow({ session }: { session: SessionView }) {
  const navigate = useNavigate();
  const startMutation = useStartSession(session.sessionId);
  const canStart =
    ['CREATED', 'HIBERNATED'].includes(session.state) &&
    !session.currentOperation;

  return (
    <tr
      className={cn(
        'group border-b border-border-subtle transition-colors hover:bg-surface-2 focus-within:bg-surface-2',
        session.state === 'RUNNING' && 'border-l-2 border-l-accent',
        ['DEGRADED', 'FAILED'].includes(session.state) &&
          'border-l-2 border-l-danger'
      )}
    >
      <td className="px-4 py-3">
        <button
          type="button"
          onClick={() => navigate(`/environments/${session.sessionId}`)}
          className="text-left"
        >
          <span className="block max-w-[220px] truncate text-[12px] font-medium text-text-primary hover:text-accent">
            {session.displayName}
          </span>
          <span className="block font-mono text-[10px] text-text-muted">
            {session.sessionId}
          </span>
        </button>
      </td>
      <td className="px-4 py-3">
        <span className="block font-mono text-[11px] text-text-secondary">
          {session.profileId}
        </span>
        <span className="block font-mono text-[10px] text-text-muted">
          {session.tenantId}
        </span>
      </td>
      <td className="px-4 py-3">
        <span className="block font-mono text-[11px] text-text-secondary">
          {session.region}
        </span>
        <span className="block font-mono text-[10px] text-text-muted">
          {session.resourceClass}
        </span>
      </td>
      <td className="px-4 py-3">
        <span className="block max-w-[180px] truncate font-mono text-[11px] text-text-secondary">
          {session.runtimeBuildId || 'Runtime 未绑定'}
        </span>
        <span className="block max-w-[180px] truncate font-mono text-[10px] text-text-muted">
          {session.nodeId || 'Node 未分配'}
        </span>
      </td>
      <td className="px-4 py-3">
        <span className="font-mono text-[11px] text-text-primary">
          e{session.contextEpoch}
        </span>
        <span className="ml-2 font-mono text-[10px] text-text-muted">
          gen {session.browserGeneration}
        </span>
      </td>
      <td className="px-4 py-3">
        {session.currentOperation ? (
          <div>
            <p className="text-[11px] text-text-primary">
              {session.currentOperation.mode}
            </p>
            <p className="font-mono text-[10px] text-text-muted">
              {session.currentOperation.operationId}
            </p>
          </div>
        ) : (
          <span className="text-[11px] text-text-muted">无活跃操作</span>
        )}
      </td>
      <td className="px-4 py-3">
        <ApiSessionStateChip state={session.state} />
      </td>
      <td className="px-4 py-3 text-[11px] text-text-muted">
        {formatDate(session.updatedAt)}
      </td>
      <td className="px-4 py-3">
        <div className="flex items-center gap-1">
          {canStart && (
            <button
              type="button"
              onClick={() => startMutation.mutate()}
              disabled={startMutation.isPending}
              className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-success/12 hover:text-success disabled:opacity-50"
              aria-label={`启动 ${session.sessionId}`}
              title="启动"
            >
              {startMutation.isPending ? (
                <LoaderCircle size={13} className="animate-spin" />
              ) : (
                <Play size={13} />
              )}
            </button>
          )}
          <button
            type="button"
            onClick={() => navigate(`/environments/${session.sessionId}`)}
            className="flex h-7 w-7 items-center justify-center rounded-md text-text-muted hover:bg-accent-soft hover:text-accent"
            aria-label={`查看 ${session.sessionId} 详情`}
            title="查看详情"
          >
            <ExternalLink size={13} />
          </button>
        </div>
      </td>
    </tr>
  );
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).format(new Date(value));
}
