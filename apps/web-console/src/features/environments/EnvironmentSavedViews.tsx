import { useState } from 'react';
import {
  Bookmark,
  Check,
  LoaderCircle,
  Plus,
  RefreshCw,
  Trash2,
  UserRound,
  UsersRound,
  X,
} from 'lucide-react';
import { useAuth } from '@/auth/AuthProvider';
import { cn } from '@/shared/lib/utils';
import type {
  EnvironmentSavedView,
  EnvironmentSavedViewConfiguration,
  EnvironmentSavedViewScope,
} from '@/types/savedView';
import {
  useCreateEnvironmentSavedView,
  useDeleteEnvironmentSavedView,
  useEnvironmentSavedViews,
  useUpdateEnvironmentSavedView,
} from './savedViewQueries';
import { isSessionApiError } from '@/api/session';

export function EnvironmentSavedViews({
  current,
  onApply,
}: {
  current: EnvironmentSavedViewConfiguration;
  onApply: (view: EnvironmentSavedView) => void;
}) {
  const auth = useAuth();
  const query = useEnvironmentSavedViews();
  const create = useCreateEnvironmentSavedView();
  const update = useUpdateEnvironmentSavedView();
  const remove = useDeleteEnvironmentSavedView();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [scope, setScope] = useState<EnvironmentSavedViewScope>('PERSONAL');
  const [deleteArmed, setDeleteArmed] = useState<string | null>(null);
  const admin = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const busy = create.isPending || update.isPending || remove.isPending;
  const mutationError = create.error ?? update.error ?? remove.error;
  const requestId = isSessionApiError(mutationError)
    ? mutationError.body.requestId
    : undefined;
  const items = query.data?.items ?? [];
  const exactMatch = items.find((item) => sameConfiguration(item, current));

  const createView = () => {
    const normalized = name.trim();
    if (!normalized || busy || !auth.canOperate) return;
    create.mutate(
      { ...current, name: normalized, scope },
      {
        onSuccess: () => {
          setName('');
          setScope('PERSONAL');
        },
      }
    );
  };

  const overwrite = (view: EnvironmentSavedView) => {
    if (!canManage(view, auth.identity?.actorId, admin) || busy) return;
    update.mutate({
      savedView: view,
      body: {
        ...current,
        name: view.name,
        expectedVersion: view.version,
      },
    });
  };

  const deleteView = (view: EnvironmentSavedView) => {
    if (deleteArmed !== view.savedViewId) {
      setDeleteArmed(view.savedViewId);
      return;
    }
    remove.mutate(view, {
      onSuccess: () => setDeleteArmed(null),
    });
  };

  return (
    <div className="relative">
      <button
        type="button"
        onClick={() => setOpen((value) => !value)}
        aria-expanded={open}
        className={cn(
          'inline-flex h-10 items-center gap-2 rounded-[7px] border px-3 text-[12px] transition-colors',
          open || exactMatch
            ? 'border-accent/40 bg-accent-soft text-accent'
            : 'border-border-subtle text-text-secondary hover:border-border-default hover:bg-surface-2'
        )}
      >
        <Bookmark size={14} fill={exactMatch ? 'currentColor' : 'none'} />
        <span className="hidden lg:inline">
          {exactMatch ? exactMatch.name : '保存视图'}
        </span>
        {items.length > 0 && (
          <span className="font-mono text-[9px] text-text-muted">
            {items.length}
          </span>
        )}
      </button>

      {open && (
        <section
          className="absolute right-0 top-12 z-30 w-[min(420px,calc(100vw-2rem))] border border-border-default bg-surface-1 shadow-2xl"
          aria-label="环境 Saved Views"
        >
          <header className="flex items-start justify-between gap-4 border-b border-border-subtle px-4 py-3">
            <div>
              <p className="font-mono text-[10px] uppercase tracking-[0.14em] text-accent">
                Environment presets
              </p>
              <h2 className="mt-1 text-[13px] font-semibold text-text-primary">
                已保存视图
              </h2>
              <p className="mt-1 text-[10px] leading-4 text-text-muted">
                保存筛选与列配置，不保存 Session 结果快照。
              </p>
            </div>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="flex h-8 w-8 shrink-0 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭 Saved Views"
            >
              <X size={14} />
            </button>
          </header>

          {auth.canOperate && (
            <div className="border-b border-border-subtle bg-surface-2 px-4 py-3">
              <div className="flex gap-2">
                <label className="min-w-0 flex-1">
                  <span className="sr-only">视图名称</span>
                  <input
                    value={name}
                    onChange={(event) => setName(event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Enter') createView();
                    }}
                    maxLength={64}
                    placeholder="视图名称，例如：新加坡运行环境"
                    className="h-9 w-full border border-border-subtle bg-surface-1 px-3 text-[12px] text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
                  />
                </label>
                <button
                  type="button"
                  onClick={createView}
                  disabled={!name.trim() || busy}
                  className="inline-flex h-9 shrink-0 items-center gap-1.5 bg-accent px-3 text-[11px] font-semibold text-canvas disabled:opacity-45"
                >
                  {create.isPending ? (
                    <LoaderCircle size={13} className="animate-spin" />
                  ) : (
                    <Plus size={13} />
                  )}
                  保存
                </button>
              </div>
              <div className="mt-2 flex items-center gap-1">
                <ScopeButton
                  active={scope === 'PERSONAL'}
                  icon={UserRound}
                  label="个人"
                  onClick={() => setScope('PERSONAL')}
                />
                {admin && (
                  <ScopeButton
                    active={scope === 'WORKSPACE'}
                    icon={UsersRound}
                    label="Workspace"
                    onClick={() => setScope('WORKSPACE')}
                  />
                )}
                <span className="ml-auto font-mono text-[9px] text-text-muted">
                  {current.primaryView}
                  {current.sessionState ? ` / ${current.sessionState}` : ''}
                </span>
              </div>
            </div>
          )}

          <div className="max-h-[360px] overflow-y-auto">
            {query.isLoading ? (
              <div className="flex items-center gap-2 px-4 py-6 text-[11px] text-text-muted">
                <LoaderCircle size={14} className="animate-spin" />
                正在读取 PostgreSQL Saved Views…
              </div>
            ) : query.isError ? (
              <div className="flex items-center justify-between gap-3 px-4 py-5">
                <p className="text-[11px] text-danger">Saved Views 读取失败</p>
                <button
                  type="button"
                  onClick={() => void query.refetch()}
                  className="text-[11px] text-accent"
                >
                  重试
                </button>
              </div>
            ) : items.length === 0 ? (
              <div className="px-4 py-7">
                <Bookmark size={17} className="text-text-muted" />
                <p className="mt-3 text-[12px] text-text-secondary">
                  尚未保存环境视图
                </p>
                <p className="mt-1 text-[10px] leading-4 text-text-muted">
                  调整状态、搜索和列后保存；个人视图仅自己可见。
                </p>
              </div>
            ) : (
              <div className="divide-y divide-border-subtle">
                {items.map((item) => {
                  const currentMatch = sameConfiguration(item, current);
                  const manageable = canManage(
                    item,
                    auth.identity?.actorId,
                    admin
                  );
                  return (
                    <article
                      key={item.savedViewId}
                      className={cn(
                        'group px-4 py-3',
                        currentMatch
                          ? 'bg-accent-soft/55'
                          : 'hover:bg-surface-2'
                      )}
                    >
                      <div className="flex items-start justify-between gap-3">
                        <button
                          type="button"
                          onClick={() => {
                            onApply(item);
                            setOpen(false);
                          }}
                          className="min-w-0 flex-1 text-left"
                        >
                          <span className="flex items-center gap-2">
                            {item.scope === 'PERSONAL' ? (
                              <UserRound
                                size={12}
                                className="shrink-0 text-text-muted"
                              />
                            ) : (
                              <UsersRound
                                size={12}
                                className="shrink-0 text-accent"
                              />
                            )}
                            <span className="truncate text-[12px] font-medium text-text-primary">
                              {item.name}
                            </span>
                            {currentMatch && (
                              <Check
                                size={12}
                                className="shrink-0 text-success"
                              />
                            )}
                          </span>
                          <span className="mt-1 block truncate font-mono text-[9px] text-text-muted">
                            {summary(item)}
                          </span>
                        </button>
                        {manageable && auth.canOperate && (
                          <div className="flex shrink-0 items-center gap-0.5">
                            <button
                              type="button"
                              onClick={() => overwrite(item)}
                              disabled={busy || currentMatch}
                              className="flex h-8 w-8 items-center justify-center text-text-muted hover:bg-surface-3 hover:text-accent disabled:opacity-25"
                              aria-label={`以当前配置覆盖 ${item.name}`}
                              title="以当前配置覆盖"
                            >
                              <RefreshCw size={12} />
                            </button>
                            <button
                              type="button"
                              onClick={() => deleteView(item)}
                              onBlur={() => setDeleteArmed(null)}
                              disabled={busy}
                              className={cn(
                                'flex h-8 items-center justify-center text-[9px]',
                                deleteArmed === item.savedViewId
                                  ? 'bg-danger px-2 font-semibold text-white'
                                  : 'w-8 text-text-muted hover:bg-danger/10 hover:text-danger'
                              )}
                              aria-label={`删除 ${item.name}`}
                            >
                              {deleteArmed === item.savedViewId ? (
                                '确认'
                              ) : (
                                <Trash2 size={12} />
                              )}
                            </button>
                          </div>
                        )}
                      </div>
                    </article>
                  );
                })}
              </div>
            )}
          </div>

          {mutationError && (
            <div
              role="alert"
              className="border-t border-danger/30 bg-danger/8 px-4 py-2.5 text-[10px] text-danger"
            >
              {mutationError instanceof Error
                ? mutationError.message
                : 'Saved View 写入失败'}
              {requestId ? ` · Request ${requestId}` : ''}
            </div>
          )}
          <footer className="border-t border-border-subtle px-4 py-2 font-mono text-[9px] text-text-muted">
            {query.data?.total ?? 0} VIEWS · CAS + IDEMPOTENCY + AUDIT
          </footer>
        </section>
      )}
    </div>
  );
}

function ScopeButton({
  active,
  icon: Icon,
  label,
  onClick,
}: {
  active: boolean;
  icon: typeof UserRound;
  label: string;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={cn(
        'inline-flex h-7 items-center gap-1.5 px-2 text-[10px]',
        active
          ? 'bg-surface-3 text-text-primary'
          : 'text-text-muted hover:text-text-secondary'
      )}
    >
      <Icon size={11} />
      {label}
    </button>
  );
}

function canManage(
  item: EnvironmentSavedView,
  actorId: string | undefined,
  admin: boolean
) {
  return item.scope === 'PERSONAL' ? item.ownerActorId === actorId : admin;
}

function sameConfiguration(
  item: EnvironmentSavedView,
  current: EnvironmentSavedViewConfiguration
) {
  return (
    item.primaryView === current.primaryView &&
    (item.sessionState ?? undefined) === (current.sessionState ?? undefined) &&
    item.searchQuery === current.searchQuery &&
    item.showRuntimeColumn === current.showRuntimeColumn &&
    item.showContextColumn === current.showContextColumn &&
    item.showOperationColumn === current.showOperationColumn
  );
}

function summary(item: EnvironmentSavedView) {
  const columns = [
    item.showRuntimeColumn ? 'RUNTIME' : null,
    item.showContextColumn ? 'CONTEXT' : null,
    item.showOperationColumn ? 'OPERATION' : null,
  ]
    .filter(Boolean)
    .join('+');
  return [
    item.scope,
    item.sessionState ?? item.primaryView,
    item.searchQuery ? `“${item.searchQuery}”` : 'NO QUERY',
    columns || 'BASE COLUMNS',
  ].join(' · ');
}
