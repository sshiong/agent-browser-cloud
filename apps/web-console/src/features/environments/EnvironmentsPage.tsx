import { useDeferredValue, useEffect, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  Box,
  ChevronLeft,
  ChevronRight,
  CircleAlert,
  Columns3,
  ExternalLink,
  FileUp,
  Filter,
  Layers3,
  Network,
  Plus,
  RotateCw,
  Search,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingRows } from '@/components/feedback/AsyncStates';
import { CreateSessionDialog } from '@/features/sessions/components/CreateSessionDialog';
import { ApiSessionStateChip } from '@/features/sessions/components/ApiSessionStateChip';
import { useSessions } from '@/features/sessions/api/sessionQueries';
import { SessionLifecycleActions } from '@/features/sessions/components/SessionLifecycleActions';
import { useWorkspaceOverviewStream } from '@/features/overview/api/overviewQueries';
import { useWorkspaceGroups } from '@/features/groups/groupQueries';
import { useWorkspaceTags } from '@/features/groups/tagQueries';
import { cn } from '@/shared/lib/utils';
import type { SessionState, SessionView } from '@/types/session';
import { useAuth } from '@/auth/AuthProvider';
import { EnvironmentSavedViews } from './EnvironmentSavedViews';
import { EnvironmentImportDrawer } from './EnvironmentImportDrawer';
import { SessionActionsMenu } from './SessionActionsMenu';
import { BatchDeleteSessionsDialog } from './BatchDeleteSessionsDialog';
import type {
  EnvironmentPrimaryView,
  EnvironmentSavedView,
} from '@/types/savedView';

const PAGE_SIZE = 20;
const primaryViews = [
  { value: 'all', label: '全部', state: undefined },
  { value: 'running', label: '运行中', state: 'RUNNING' },
  { value: 'stopped', label: '已停止', state: 'TERMINATED' },
  { value: 'abnormal', label: '异常', state: 'DEGRADED' },
] as const;
const exactStates: { value: SessionState; label: string }[] = [
  { value: 'CREATED', label: '已创建' },
  { value: 'STARTING', label: '启动中' },
  { value: 'RUNNING', label: '运行中' },
  { value: 'DEGRADED', label: '降级' },
  { value: 'HIBERNATING', label: '停止中' },
  { value: 'HIBERNATED', label: '已停止' },
  { value: 'RECOVERING', label: '恢复中' },
  { value: 'TERMINATING', label: '停止中（兼容）' },
  { value: 'TERMINATED', label: '已停止（兼容）' },
  { value: 'FAILED', label: '失败' },
];

type View = (typeof primaryViews)[number]['value'];
type OptionalColumn = 'runtime' | 'context' | 'operation';

function applySavedView(
  savedView: EnvironmentSavedView,
  controls: {
    setOptionalColumns: (value: Record<OptionalColumn, boolean>) => void;
    setShowAdvanced: (value: boolean) => void;
    setShowColumns: (value: boolean) => void;
    updateParams: (updates: Record<string, string | undefined>) => void;
  }
) {
  const nextView = savedView.primaryView.toLowerCase() as View;
  controls.setOptionalColumns({
    runtime: savedView.showRuntimeColumn,
    context: savedView.showContextColumn,
    operation: savedView.showOperationColumn,
  });
  controls.setShowAdvanced(
    Boolean(
      savedView.sessionState || savedView.groupId || savedView.tagIds.length
    )
  );
  controls.setShowColumns(false);
  controls.updateParams({
    view: nextView === 'all' ? undefined : nextView,
    state: savedView.sessionState ?? undefined,
    q: savedView.searchQuery || undefined,
    groupId: savedView.groupId ?? undefined,
    tags: savedView.tagIds.length ? savedView.tagIds.join(',') : undefined,
    tagMatch:
      savedView.tagIds.length > 1 && savedView.tagMatch === 'ALL'
        ? 'ALL'
        : undefined,
    page: undefined,
  });
}

export function EnvironmentsPage() {
  const auth = useAuth();
  const [searchParams, setSearchParams] = useSearchParams();
  const rawView = searchParams.get('view');
  const view: View = primaryViews.some((item) => item.value === rawView)
    ? (rawView as View)
    : 'all';
  const rawState = searchParams.get('state');
  const exactState = exactStates.some((item) => item.value === rawState)
    ? (rawState as SessionState)
    : undefined;
  const page = Math.max(0, Number(searchParams.get('page') ?? 1) - 1 || 0);
  const search = searchParams.get('q') ?? '';
  const groupId = searchParams.get('groupId') ?? undefined;
  const tagIds = Array.from(
    new Set(
      (searchParams.get('tags') ?? '')
        .split(',')
        .map((value) => value.trim())
        .filter(Boolean)
    )
  ).slice(0, 16);
  const tagMatch = searchParams.get('tagMatch') === 'ALL' ? 'ALL' : 'ANY';
  const deferredSearch = useDeferredValue(search);
  const activeView = primaryViews.find((item) => item.value === view)!;
  const state = exactState ?? activeView.state;
  const [showAdvanced, setShowAdvanced] = useState(false);
  const [showColumns, setShowColumns] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<Set<string>>(
    () => new Set()
  );
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [optionalColumns, setOptionalColumns] = useState<
    Record<OptionalColumn, boolean>
  >({
    runtime: true,
    context: true,
    operation: true,
  });
  const groupsQuery = useWorkspaceGroups();
  const tagsQuery = useWorkspaceTags();

  const query = useSessions({
    state,
    query: deferredSearch,
    groupId,
    tagIds,
    tagMatch,
    limit: PAGE_SIZE,
    offset: page * PAGE_SIZE,
  });
  const streamState = useWorkspaceOverviewStream(query.isSuccess);
  const createOpen = auth.canOperate && searchParams.get('create') === '1';
  const importOpen = auth.canOperate && searchParams.get('import') === '1';
  const total = query.data?.total ?? 0;
  const items = query.data?.items ?? [];
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const runningOnPage = items.filter((item) => item.state === 'RUNNING').length;
  const abnormalOnPage = items.filter((item) =>
    ['DEGRADED', 'FAILED'].includes(item.state)
  ).length;
  const deletableItems = items.filter(canDeleteSession);
  const selectedSessions = items.filter((item) =>
    selectedSessionIds.has(item.sessionId)
  );
  const allDeletableSelected =
    deletableItems.length > 0 &&
    deletableItems.every((item) => selectedSessionIds.has(item.sessionId));
  const someDeletableSelected = deletableItems.some((item) =>
    selectedSessionIds.has(item.sessionId)
  );
  const visibleDeletableKey = deletableItems
    .map((item) => item.sessionId)
    .join('|');

  useEffect(() => {
    const visibleIds = new Set(visibleDeletableKey.split('|').filter(Boolean));
    setSelectedSessionIds((current) => {
      const next = new Set([...current].filter((id) => visibleIds.has(id)));
      return next.size === current.size ? current : next;
    });
  }, [visibleDeletableKey]);

  const updateParams = (updates: Record<string, string | undefined>) => {
    const next = new URLSearchParams(searchParams);
    Object.entries(updates).forEach(([key, value]) => {
      if (value) next.set(key, value);
      else next.delete(key);
    });
    setSearchParams(next, { replace: true });
  };

  const setCreateOpen = (open: boolean) => {
    if (open && !auth.canOperate) return;
    updateParams({ create: open ? '1' : undefined });
  };

  const setImportOpen = (open: boolean) => {
    if (open && !auth.canOperate) return;
    updateParams({ import: open ? '1' : undefined });
  };

  const selectView = (nextView: View) => {
    updateParams({
      view: nextView === 'all' ? undefined : nextView,
      state: undefined,
      page: undefined,
    });
  };

  const setPage = (nextPage: number) => {
    updateParams({
      page: nextPage <= 0 ? undefined : String(nextPage + 1),
    });
  };

  return (
    <div className="min-h-full">
      <TopContextBar
        title={auth.identity?.tenantId ?? '当前租户'}
        subtitle="Agent Browser Cloud / Runtime Console"
        globalOnly
      />

      <section className="border-b border-border-subtle bg-canvas/80 px-4 py-5 sm:px-6">
        <div className="mx-auto flex max-w-[1760px] flex-wrap items-start justify-between gap-5">
          <div>
            <div className="mb-2 flex items-center gap-2 font-mono text-[10px] uppercase tracking-[0.16em] text-text-muted">
              <span>Control Plane</span>
              <span>/</span>
              <span className="text-accent">Environments</span>
            </div>
            <h1 className="text-[22px] font-semibold tracking-[-0.02em] text-text-primary">
              环境管理
            </h1>
            <p className="mt-1.5 max-w-[720px] text-[13px] text-text-secondary">
              管理真实 Session、Runtime、Profile、网络绑定与当前 Operation。
            </p>
          </div>
          {auth.canOperate && (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setImportOpen(true)}
                className="inline-flex h-10 items-center gap-2 rounded-[7px] border border-border-default bg-surface-1 px-4 text-[13px] font-medium text-text-secondary transition-colors hover:border-accent/40 hover:text-text-primary"
              >
                <FileUp size={15} />
                导入环境
              </button>
              <button
                type="button"
                onClick={() => setCreateOpen(true)}
                className="inline-flex h-10 items-center gap-2 rounded-[7px] bg-accent px-4 text-[13px] font-semibold text-canvas transition-colors hover:bg-accent/90"
              >
                <Plus size={16} />
                新建环境
              </button>
            </div>
          )}
        </div>
      </section>

      <div className="border-b border-border-subtle bg-surface-1 px-4 sm:px-6">
        <div className="mx-auto max-w-[1760px]">
          <div className="flex min-h-[58px] flex-wrap items-center justify-between gap-3 py-2">
            <div
              className="flex items-center gap-1"
              role="tablist"
              aria-label="环境状态视图"
            >
              {primaryViews.map((item) => (
                <button
                  key={item.value}
                  type="button"
                  role="tab"
                  aria-selected={view === item.value && !exactState}
                  onClick={() => selectView(item.value)}
                  className={cn(
                    'relative h-9 px-3 text-[13px] font-medium transition-colors',
                    view === item.value && !exactState
                      ? 'text-text-primary after:absolute after:inset-x-2 after:bottom-0 after:h-0.5 after:bg-accent'
                      : 'text-text-muted hover:text-text-secondary'
                  )}
                >
                  {item.label}
                </button>
              ))}
            </div>

            <div className="flex min-w-0 flex-1 flex-wrap items-center justify-end gap-2">
              <label className="relative min-w-[220px] flex-1 sm:max-w-[380px]">
                <span className="sr-only">搜索环境</span>
                <Search
                  size={15}
                  className="absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
                />
                <input
                  type="search"
                  placeholder="搜索名称、Session、Profile、区域…"
                  value={search}
                  maxLength={128}
                  onChange={(event) =>
                    updateParams({
                      q: event.target.value || undefined,
                      page: undefined,
                    })
                  }
                  className="h-10 w-full rounded-[7px] border border-border-subtle bg-surface-2 pl-9 pr-3 text-[13px] text-text-primary placeholder:text-text-muted focus:border-accent focus:outline-none"
                />
              </label>
              <ToolbarButton
                icon={Filter}
                label="高级筛选"
                active={
                  showAdvanced ||
                  Boolean(exactState) ||
                  Boolean(groupId) ||
                  tagIds.length > 0
                }
                onClick={() => setShowAdvanced((current) => !current)}
              />
              <div className="relative">
                <ToolbarButton
                  icon={Columns3}
                  label="列"
                  active={showColumns}
                  onClick={() => setShowColumns((current) => !current)}
                />
                {showColumns && (
                  <div className="absolute right-0 top-11 z-20 w-52 border border-border-default bg-surface-2 p-2 shadow-2xl">
                    <p className="px-2 pb-2 font-mono text-[9px] uppercase tracking-[0.14em] text-text-muted">
                      Column visibility
                    </p>
                    {(
                      [
                        ['runtime', 'Runtime / Node'],
                        ['context', 'Context'],
                        ['operation', '当前 Operation'],
                      ] as const
                    ).map(([column, label]) => (
                      <label
                        key={column}
                        className="flex cursor-pointer items-center gap-2 px-2 py-2 text-[12px] text-text-secondary hover:bg-surface-3"
                      >
                        <input
                          type="checkbox"
                          checked={optionalColumns[column]}
                          onChange={(event) =>
                            setOptionalColumns((current) => ({
                              ...current,
                              [column]: event.target.checked,
                            }))
                          }
                          className="h-4 w-4 accent-accent"
                        />
                        {label}
                      </label>
                    ))}
                  </div>
                )}
              </div>
              <EnvironmentSavedViews
                current={{
                  primaryView: view.toUpperCase() as EnvironmentPrimaryView,
                  sessionState: exactState,
                  searchQuery: search,
                  groupId: groupId ?? null,
                  tagIds,
                  tagMatch,
                  showRuntimeColumn: optionalColumns.runtime,
                  showContextColumn: optionalColumns.context,
                  showOperationColumn: optionalColumns.operation,
                }}
                onApply={(savedView) => {
                  applySavedView(savedView, {
                    setOptionalColumns,
                    setShowAdvanced,
                    setShowColumns,
                    updateParams,
                  });
                }}
              />
            </div>
          </div>

          {showAdvanced && (
            <div className="flex flex-wrap items-end gap-4 border-t border-border-subtle py-4">
              <label className="block min-w-[220px]">
                <span className="mb-1.5 block text-[11px] text-text-muted">
                  精确状态
                </span>
                <select
                  value={exactState ?? ''}
                  onChange={(event) =>
                    updateParams({
                      state: event.target.value || undefined,
                      page: undefined,
                    })
                  }
                  className="field-input"
                >
                  <option value="">继承当前主视图</option>
                  {exactStates.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label} · {item.value}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block min-w-[220px]">
                <span className="mb-1.5 block text-[11px] text-text-muted">
                  Workspace Group
                </span>
                <select
                  value={groupId ?? ''}
                  disabled={groupsQuery.isLoading}
                  onChange={(event) =>
                    updateParams({
                      groupId: event.target.value || undefined,
                      page: undefined,
                    })
                  }
                  className="field-input"
                >
                  <option value="">全部分组</option>
                  {groupsQuery.data?.items.map((group) => (
                    <option key={group.groupId} value={group.groupId}>
                      {group.name} · {group.sessionCount}
                    </option>
                  ))}
                </select>
              </label>
              <fieldset
                className="min-w-[280px] flex-1"
                aria-label="Workspace Tags"
              >
                <div className="mb-1.5 flex items-center justify-between gap-3">
                  <span className="text-[11px] text-text-muted">
                    Workspace Tags
                  </span>
                  {tagIds.length > 1 && (
                    <span className="inline-flex border border-border-subtle">
                      {(['ANY', 'ALL'] as const).map((mode) => (
                        <button
                          key={mode}
                          type="button"
                          onClick={() =>
                            updateParams({
                              tagMatch: mode === 'ANY' ? undefined : mode,
                              page: undefined,
                            })
                          }
                          className={cn(
                            'h-6 px-2 font-mono text-[9px]',
                            tagMatch === mode
                              ? 'bg-accent text-canvas'
                              : 'bg-surface-2 text-text-muted'
                          )}
                        >
                          {mode}
                        </button>
                      ))}
                    </span>
                  )}
                </div>
                <div className="flex min-h-10 flex-wrap items-center gap-1.5 border border-border-subtle bg-surface-2 px-2 py-1.5">
                  {tagsQuery.isLoading ? (
                    <span className="text-[10px] text-text-muted">
                      加载标签…
                    </span>
                  ) : tagsQuery.data?.items.length ? (
                    tagsQuery.data.items.map((tag) => {
                      const selected = tagIds.includes(tag.tagId);
                      return (
                        <button
                          key={tag.tagId}
                          type="button"
                          aria-pressed={selected}
                          onClick={() => {
                            const next = selected
                              ? tagIds.filter((id) => id !== tag.tagId)
                              : [...tagIds, tag.tagId].slice(0, 16);
                            updateParams({
                              tags: next.length ? next.join(',') : undefined,
                              tagMatch:
                                next.length > 1 && tagMatch === 'ALL'
                                  ? 'ALL'
                                  : undefined,
                              page: undefined,
                            });
                          }}
                          className={cn(
                            'inline-flex h-6 items-center gap-1.5 border px-2 text-[10px]',
                            selected
                              ? 'border-accent/50 bg-accent-soft text-accent'
                              : 'border-border-subtle bg-surface-1 text-text-muted hover:text-text-primary'
                          )}
                        >
                          <span
                            className="h-1.5 w-1.5"
                            style={{ backgroundColor: tag.color }}
                          />
                          {tag.name}
                        </button>
                      );
                    })
                  ) : (
                    <span className="text-[10px] text-text-muted">
                      尚无可用标签
                    </span>
                  )}
                </div>
              </fieldset>
              <button
                type="button"
                onClick={() =>
                  updateParams({
                    state: undefined,
                    q: undefined,
                    groupId: undefined,
                    tags: undefined,
                    tagMatch: undefined,
                    page: undefined,
                  })
                }
                className="h-10 px-2 text-[12px] text-accent hover:underline"
              >
                清除筛选
              </button>
              <p className="ml-auto hidden text-[10px] text-text-muted lg:block">
                筛选、搜索和分页已同步到 URL，可复制链接共享当前视图。
              </p>
            </div>
          )}
        </div>
      </div>

      <main className="mx-auto max-w-[1760px] p-4 sm:p-6">
        {query.isSuccess && streamState !== 'LIVE' && (
          <p
            role="status"
            className="mb-3 border border-warning/30 bg-warning/10 px-3 py-2 text-[11px] text-warning"
          >
            会话状态连接中断或正在连接，当前状态可能已过期；连接恢复后自动同步，也可手动刷新。
          </p>
        )}
        <section className="mb-4 grid border border-border-subtle bg-border-subtle sm:grid-cols-3">
          <Readout
            label="当前结果"
            value={query.data ? String(query.data.total) : '—'}
            detail={state ?? 'ALL STATES'}
          />
          <Readout
            label="本页运行中"
            value={query.data ? String(runningOnPage) : '—'}
            detail="RUNNING"
            tone="success"
          />
          <Readout
            label="本页异常"
            value={query.data ? String(abnormalOnPage) : '—'}
            detail="DEGRADED / FAILED"
            tone={abnormalOnPage > 0 ? 'danger' : 'muted'}
          />
        </section>

        {query.isLoading ? (
          <div className="border border-border-subtle bg-surface-1">
            <LoadingRows />
          </div>
        ) : query.error ? (
          <div className="border border-border-subtle bg-surface-1">
            <ErrorState error={query.error} onRetry={() => query.refetch()} />
          </div>
        ) : items.length === 0 &&
          !search &&
          !state &&
          !groupId &&
          tagIds.length === 0 ? (
          <EnvironmentEmptyState
            canCreate={auth.canOperate}
            onCreate={() => setCreateOpen(true)}
            onImport={() => setImportOpen(true)}
          />
        ) : items.length === 0 ? (
          <FilteredEmptyState
            onClear={() =>
              updateParams({
                view: undefined,
                state: undefined,
                q: undefined,
                groupId: undefined,
                tags: undefined,
                tagMatch: undefined,
                page: undefined,
              })
            }
          />
        ) : (
          <section className="overflow-hidden border border-border-subtle bg-surface-1">
            <div className="flex min-h-11 items-center justify-between gap-3 border-b border-border-subtle bg-surface-2 px-4">
              {selectedSessions.length > 0 ? (
                <div className="flex items-center gap-3">
                  <p className="text-[11px] text-text-secondary">
                    已选择{' '}
                    <span className="font-mono text-text-primary">
                      {selectedSessions.length}
                    </span>{' '}
                    个可删除环境
                  </p>
                  <button
                    type="button"
                    onClick={() => setDeleteOpen(true)}
                    className="inline-flex h-8 items-center gap-1.5 border border-danger/35 px-2.5 text-[11px] font-medium text-danger hover:bg-danger/10"
                  >
                    <Trash2 size={12} />
                    删除所选
                  </button>
                  <button
                    type="button"
                    onClick={() => setSelectedSessionIds(new Set())}
                    className="h-8 px-2 text-[11px] text-text-muted hover:text-text-primary"
                  >
                    清除选择
                  </button>
                </div>
              ) : (
                <p className="text-[11px] text-text-muted">
                  共{' '}
                  <span className="font-mono text-text-secondary">{total}</span>{' '}
                  个环境 · 服务端筛选与分页
                </p>
              )}
              <button
                type="button"
                onClick={() => query.refetch()}
                disabled={query.isFetching}
                className="inline-flex h-8 items-center gap-1.5 px-2 text-[11px] text-text-muted hover:text-accent disabled:opacity-50"
              >
                <RotateCw
                  size={12}
                  className={query.isFetching ? 'animate-spin' : undefined}
                />
                {query.isFetching ? '同步中' : '刷新'}
              </button>
            </div>
            <div className="max-h-[calc(100vh-340px)] overflow-auto">
              <table className="w-full min-w-[1080px]">
                <thead className="sticky top-0 z-10">
                  <tr className="border-b border-border-subtle bg-surface-2">
                    {auth.canOperate && (
                      <th className="w-11 px-3 py-2.5 text-center">
                        <SelectionCheckbox
                          checked={allDeletableSelected}
                          indeterminate={
                            someDeletableSelected && !allDeletableSelected
                          }
                          disabled={deletableItems.length === 0}
                          label="选择本页全部可删除环境"
                          onChange={() =>
                            setSelectedSessionIds(
                              allDeletableSelected
                                ? new Set()
                                : new Set(
                                    deletableItems.map((item) => item.sessionId)
                                  )
                            )
                          }
                        />
                      </th>
                    )}
                    <TableHead>环境</TableHead>
                    <TableHead>Profile / 租户</TableHead>
                    <TableHead>区域 / 资源</TableHead>
                    {optionalColumns.runtime && (
                      <TableHead>Runtime / Node</TableHead>
                    )}
                    {optionalColumns.context && <TableHead>Context</TableHead>}
                    {optionalColumns.operation && (
                      <TableHead>当前 Operation</TableHead>
                    )}
                    <TableHead>状态</TableHead>
                    <TableHead>最近活动</TableHead>
                    <TableHead>
                      <span className="sr-only">操作</span>
                    </TableHead>
                  </tr>
                </thead>
                <tbody>
                  {items.map((session) => (
                    <SessionRow
                      key={session.sessionId}
                      session={session}
                      columns={optionalColumns}
                      selected={selectedSessionIds.has(session.sessionId)}
                      onSelectedChange={(selected) =>
                        setSelectedSessionIds((current) => {
                          const next = new Set(current);
                          if (selected) next.add(session.sessionId);
                          else next.delete(session.sessionId);
                          return next;
                        })
                      }
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex min-h-12 items-center justify-between border-t border-border-subtle px-4">
              <span className="text-[11px] text-text-muted">
                第 {page + 1} / {pageCount} 页 · 每页 {PAGE_SIZE} 条
              </span>
              <div className="flex items-center gap-1">
                <PageButton
                  label="上一页"
                  disabled={page === 0}
                  onClick={() => setPage(Math.max(0, page - 1))}
                >
                  <ChevronLeft size={14} />
                </PageButton>
                <PageButton
                  label="下一页"
                  disabled={page + 1 >= pageCount}
                  onClick={() => setPage(Math.min(pageCount - 1, page + 1))}
                >
                  <ChevronRight size={14} />
                </PageButton>
              </div>
            </div>
          </section>
        )}
      </main>

      {auth.canOperate && (
        <>
          <CreateSessionDialog open={createOpen} onOpenChange={setCreateOpen} />
          <EnvironmentImportDrawer
            open={importOpen}
            onOpenChange={setImportOpen}
          />
          <BatchDeleteSessionsDialog
            open={deleteOpen}
            sessions={selectedSessions}
            onOpenChange={setDeleteOpen}
            onDeleted={() => setSelectedSessionIds(new Set())}
          />
        </>
      )}
    </div>
  );
}

function EnvironmentEmptyState({
  canCreate,
  onCreate,
  onImport,
}: {
  canCreate: boolean;
  onCreate: () => void;
  onImport: () => void;
}) {
  const flow = [
    { icon: Box, label: 'Runtime', detail: '已签名的稳定 Build' },
    { icon: Layers3, label: 'Profile', detail: '空状态、现有或 Checkpoint' },
    { icon: Network, label: 'Proxy', detail: '托管出口与区域策略' },
    { icon: ShieldCheck, label: 'Workload', detail: '资源、隔离与 Agent 能力' },
  ];
  return (
    <section className="grid min-h-[430px] overflow-hidden border border-border-subtle bg-surface-1 lg:grid-cols-[1.15fr_0.85fr]">
      <div className="flex items-center px-6 py-12 sm:px-10 lg:px-14">
        <div className="max-w-[540px]">
          <span className="flex h-11 w-11 items-center justify-center rounded-[10px] border border-accent/25 bg-accent-soft text-accent">
            <Box size={20} />
          </span>
          <p className="mt-6 font-mono text-[10px] uppercase tracking-[0.18em] text-accent">
            No environments provisioned
          </p>
          <h2 className="mt-2 text-[21px] font-semibold tracking-[-0.02em] text-text-primary">
            创建第一个受治理的浏览器环境
          </h2>
          <p className="mt-3 max-w-[500px] text-[13px] leading-6 text-text-secondary">
            向导会从真实 Registry、Profile Store、Proxy 和 Region API
            读取可用项，并把工作负载声明提交给 Control Plane。
          </p>
          <div className="mt-7 flex flex-wrap gap-3">
            {canCreate && (
              <button
                type="button"
                onClick={onCreate}
                className="inline-flex h-10 items-center gap-2 rounded-[7px] bg-accent px-4 text-[13px] font-semibold text-canvas"
              >
                <Plus size={15} />
                新建环境
              </button>
            )}
            {canCreate && (
              <button
                type="button"
                onClick={onImport}
                className="inline-flex h-10 items-center gap-2 rounded-[7px] border border-border-default px-4 text-[13px] text-text-secondary hover:border-accent/40 hover:text-text-primary"
              >
                <FileUp size={15} />
                导入配置
              </button>
            )}
          </div>
          {!canCreate && (
            <p className="mt-5 text-[11px] text-warning">
              当前角色为只读，请联系租户管理员创建环境。
            </p>
          )}
        </div>
      </div>
      <div className="border-t border-border-subtle bg-surface-2/65 p-7 lg:border-l lg:border-t-0 lg:p-10">
        <p className="font-mono text-[10px] uppercase tracking-[0.16em] text-text-muted">
          Provisioning path
        </p>
        <ol className="mt-6 space-y-1">
          {flow.map((item, index) => {
            const Icon = item.icon;
            return (
              <li key={item.label} className="relative flex gap-4 pb-6">
                {index < flow.length - 1 && (
                  <span className="absolute left-[17px] top-9 h-[calc(100%-28px)] w-px bg-border-default" />
                )}
                <span className="z-[1] flex h-9 w-9 shrink-0 items-center justify-center rounded-full border border-border-default bg-surface-2 text-accent">
                  <Icon size={15} />
                </span>
                <span className="pt-0.5">
                  <span className="block text-[13px] font-medium text-text-primary">
                    {index + 1}. {item.label}
                  </span>
                  <span className="mt-1 block text-[11px] text-text-muted">
                    {item.detail}
                  </span>
                </span>
              </li>
            );
          })}
        </ol>
      </div>
    </section>
  );
}

function FilteredEmptyState({ onClear }: { onClear: () => void }) {
  return (
    <section className="flex min-h-[340px] items-center justify-center border border-border-subtle bg-surface-1 px-6 py-12 text-center">
      <div className="max-w-[440px]">
        <CircleAlert size={25} className="mx-auto text-text-muted" />
        <h2 className="mt-4 text-[16px] font-semibold text-text-primary">
          没有匹配当前视图的环境
        </h2>
        <p className="mt-2 text-[12px] leading-5 text-text-muted">
          搜索与状态筛选已在服务端执行。调整条件或清除筛选后再试。
        </p>
        <button
          type="button"
          onClick={onClear}
          className="mt-5 h-9 px-3 text-[12px] font-medium text-accent hover:underline"
        >
          清除全部筛选
        </button>
      </div>
    </section>
  );
}

function SessionRow({
  session,
  columns,
  selected,
  onSelectedChange,
}: {
  session: SessionView;
  columns: Record<OptionalColumn, boolean>;
  selected: boolean;
  onSelectedChange: (selected: boolean) => void;
}) {
  const auth = useAuth();
  const navigate = useNavigate();
  const canDelete = canDeleteSession(session);

  return (
    <tr
      className={cn(
        'group border-b border-border-subtle transition-colors hover:bg-surface-2 focus-within:bg-surface-2',
        session.state === 'RUNNING' && 'border-l-2 border-l-accent',
        ['DEGRADED', 'FAILED'].includes(session.state) &&
          'border-l-2 border-l-danger',
        selected && 'bg-accent-soft/60 hover:bg-accent-soft/70'
      )}
    >
      {auth.canOperate && (
        <td className="w-11 px-3 py-3 text-center">
          <SelectionCheckbox
            checked={selected}
            disabled={!canDelete}
            label={
              canDelete
                ? `选择 ${session.displayName}`
                : `${session.displayName} 需先停止并等待操作完成后才能删除`
            }
            onChange={() => onSelectedChange(!selected)}
          />
        </td>
      )}
      <td className="px-4 py-3">
        <button
          type="button"
          onClick={() => navigate(`/environments/${session.sessionId}`)}
          className="text-left"
        >
          <span className="block max-w-[230px] truncate text-[13px] font-medium text-text-primary hover:text-accent">
            {session.displayName}
          </span>
          <span className="block max-w-[230px] truncate font-mono text-[10px] text-text-muted">
            {session.sessionId}
          </span>
          {(session.tags?.length ?? 0) > 0 && (
            <span className="mt-1 flex max-w-[230px] flex-wrap gap-1">
              {session.tags?.slice(0, 3).map((tag) => (
                <span
                  key={tag.tagId}
                  className="inline-flex items-center gap-1 border border-border-subtle px-1.5 py-0.5 text-[9px] text-text-muted"
                >
                  <span
                    className="h-1.5 w-1.5"
                    style={{ backgroundColor: tag.color }}
                    aria-hidden="true"
                  />
                  {tag.name}
                </span>
              ))}
              {(session.tags?.length ?? 0) > 3 && (
                <span className="px-1 py-0.5 text-[9px] text-text-muted">
                  +{(session.tags?.length ?? 0) - 3}
                </span>
              )}
            </span>
          )}
        </button>
      </td>
      <td className="px-4 py-3">
        <span className="block max-w-[190px] truncate font-mono text-[11px] text-text-secondary">
          {session.profileId}
        </span>
        <span className="block max-w-[190px] truncate font-mono text-[10px] text-text-muted">
          {session.tenantId}
        </span>
      </td>
      <td className="px-4 py-3">
        <span className="block font-mono text-[11px] text-text-secondary">
          {session.region}
        </span>
        <span className="block font-mono text-[10px] text-text-muted">
          AUTO
        </span>
      </td>
      {columns.runtime && (
        <td className="px-4 py-3">
          <span className="block max-w-[190px] truncate font-mono text-[11px] text-text-secondary">
            {session.runtimeBuildId || 'Runtime 未绑定'}
          </span>
          <span className="block max-w-[190px] truncate font-mono text-[10px] text-text-muted">
            {session.nodeId || 'Node 未分配'}
          </span>
          <span className="block max-w-[190px] truncate font-mono text-[10px] text-text-muted">
            Agent {session.agentPolicy ?? 'BALANCED'}
          </span>
          <span className="block max-w-[190px] truncate font-mono text-[10px] text-text-muted">
            Ext {session.extensionIds?.length ?? 0}
          </span>
        </td>
      )}
      {columns.context && (
        <td className="px-4 py-3">
          <span className="font-mono text-[11px] text-text-primary">
            e{session.contextEpoch}
          </span>
          <span className="ml-2 font-mono text-[10px] text-text-muted">
            gen {session.browserGeneration}
          </span>
        </td>
      )}
      {columns.operation && (
        <td className="px-4 py-3">
          {session.currentOperation ? (
            <div>
              <p className="text-[11px] text-text-primary">
                {session.currentOperation.mode}
              </p>
              <p className="max-w-[150px] truncate font-mono text-[10px] text-text-muted">
                {session.currentOperation.operationId}
              </p>
            </div>
          ) : (
            <span className="text-[11px] text-text-muted">无活跃操作</span>
          )}
        </td>
      )}
      <td className="px-4 py-3">
        <ApiSessionStateChip state={session.state} />
      </td>
      <td className="whitespace-nowrap px-4 py-3 text-[11px] text-text-muted">
        {formatDate(session.updatedAt)}
      </td>
      <td className="px-4 py-3">
        <div className="flex items-center justify-end gap-1">
          <SessionLifecycleActions session={session} />
          <button
            type="button"
            onClick={() => navigate(`/environments/${session.sessionId}`)}
            className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted hover:bg-accent-soft hover:text-accent"
            aria-label={`查看 ${session.sessionId} 详情`}
            title="查看详情"
          >
            <ExternalLink size={13} />
          </button>
          <SessionActionsMenu session={session} />
        </div>
      </td>
    </tr>
  );
}

function canDeleteSession(session: SessionView) {
  return (
    ['CREATED', 'HIBERNATED', 'TERMINATED'].includes(session.state) &&
    !session.currentOperation
  );
}

function SelectionCheckbox({
  checked,
  indeterminate = false,
  disabled = false,
  label,
  onChange,
}: {
  checked: boolean;
  indeterminate?: boolean;
  disabled?: boolean;
  label: string;
  onChange: () => void;
}) {
  const ref = useRef<HTMLInputElement>(null);
  useEffect(() => {
    if (ref.current) ref.current.indeterminate = indeterminate;
  }, [indeterminate]);
  return (
    <input
      ref={ref}
      type="checkbox"
      checked={checked}
      disabled={disabled}
      onChange={onChange}
      aria-label={label}
      title={label}
      className="h-3.5 w-3.5 cursor-pointer accent-accent disabled:cursor-not-allowed disabled:opacity-30"
    />
  );
}

function ToolbarButton({
  icon: Icon,
  label,
  active,
  onClick,
}: {
  icon: typeof Filter;
  label: string;
  active?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex h-10 items-center gap-2 rounded-[7px] border px-3 text-[12px] transition-colors',
        active
          ? 'border-accent/40 bg-accent-soft text-accent'
          : 'border-border-subtle text-text-secondary hover:border-border-default hover:bg-surface-2'
      )}
    >
      <Icon size={14} />
      <span className="hidden sm:inline">{label}</span>
    </button>
  );
}

function Readout({
  label,
  value,
  detail,
  tone = 'muted',
}: {
  label: string;
  value: string;
  detail: string;
  tone?: 'muted' | 'success' | 'danger';
}) {
  return (
    <div className="flex items-center justify-between gap-4 bg-surface-1 px-4 py-3">
      <span>
        <span className="block text-[11px] text-text-muted">{label}</span>
        <span className="mt-0.5 block font-mono text-[9px] text-text-muted">
          {detail}
        </span>
      </span>
      <span
        className={cn(
          'font-mono text-[18px] font-semibold',
          tone === 'success' && 'text-success',
          tone === 'danger' && 'text-danger',
          tone === 'muted' && 'text-text-primary'
        )}
      >
        {value}
      </span>
    </div>
  );
}

function TableHead({ children }: { children: React.ReactNode }) {
  return (
    <th className="whitespace-nowrap px-4 py-3 text-left text-[10px] font-medium uppercase tracking-[0.12em] text-text-muted">
      {children}
    </th>
  );
}

function PageButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      disabled={disabled}
      onClick={onClick}
      className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-30"
    >
      {children}
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
