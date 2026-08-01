import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { Pencil, Plus, Tag, Tags, Trash2, X } from 'lucide-react';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { TagEditorDialog } from './TagEditorDialog';
import { WorkspaceBatchActions } from './WorkspaceBatchActions';
import {
  useAssignSessionToTag,
  useDeleteWorkspaceTag,
  useUnassignSessionFromTag,
  useWorkspaceTags,
} from './tagQueries';
import type { TagSessionView, WorkspaceTagView } from '@/types/tag';

export function TagSection({
  canOperate,
  canAdminister,
}: {
  canOperate: boolean;
  canAdminister: boolean;
}) {
  const query = useWorkspaceTags();
  const [createOpen, setCreateOpen] = useState(false);

  return (
    <section className="mt-8" aria-labelledby="workspace-tags-heading">
      <div className="mb-4 flex flex-col gap-3 border border-border-subtle bg-surface-1 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <Tags size={15} className="text-accent" />
            <h2
              id="workspace-tags-heading"
              className="text-[13px] font-semibold text-text-primary"
            >
              Workspace Tags
            </h2>
            <span className="bg-surface-3 px-2 py-0.5 font-mono text-[10px] text-text-muted">
              {query.data?.total ?? 0}
            </span>
          </div>
          <p className="mt-1 text-[10px] text-text-muted">
            标签支持多对多环境归属；创建、修改和分配全部进入 PostgreSQL
            与租户审计链。
          </p>
        </div>
        {canAdminister && (
          <button
            type="button"
            onClick={() => setCreateOpen(true)}
            className="inline-flex h-9 shrink-0 items-center justify-center gap-2 border border-accent/40 px-4 text-[12px] font-semibold text-accent hover:bg-accent-soft"
          >
            <Plus size={14} />
            新建标签
          </button>
        )}
      </div>

      {query.isLoading ? (
        <div className="border border-border-subtle bg-surface-1">
          <LoadingRows rows={4} />
        </div>
      ) : query.isError ? (
        <ErrorState
          error={query.error}
          onRetry={() => query.refetch()}
          title="无法加载 Workspace Tags"
        />
      ) : query.data?.items.length === 0 ? (
        <div className="border border-border-subtle bg-surface-1">
          <EmptyState
            title="尚未创建 Workspace Tag"
            description="创建正式标签后，可在环境创建时选择，也可给现有环境追加或移除标签。"
            action={
              canAdminister ? (
                <button
                  type="button"
                  onClick={() => setCreateOpen(true)}
                  className="h-8 bg-accent px-3 text-[12px] font-semibold text-canvas"
                >
                  创建第一个标签
                </button>
              ) : null
            }
          />
        </div>
      ) : (
        <div className="grid gap-4 xl:grid-cols-2 2xl:grid-cols-3">
          {query.data?.items.map((tag) => (
            <TagCard
              key={tag.tagId}
              tag={tag}
              sessions={query.data?.sessions ?? []}
              canOperate={canOperate}
              canAdminister={canAdminister}
            />
          ))}
        </div>
      )}

      {canAdminister && (
        <TagEditorDialog open={createOpen} onOpenChange={setCreateOpen} />
      )}
    </section>
  );
}

function TagCard({
  tag,
  sessions,
  canOperate,
  canAdminister,
}: {
  tag: WorkspaceTagView;
  sessions: TagSessionView[];
  canOperate: boolean;
  canAdminister: boolean;
}) {
  const assign = useAssignSessionToTag();
  const unassign = useUnassignSessionFromTag();
  const remove = useDeleteWorkspaceTag();
  const [selectedSession, setSelectedSession] = useState('');
  const [editOpen, setEditOpen] = useState(false);
  const [deleteArmed, setDeleteArmed] = useState(false);
  const busy = assign.isPending || unassign.isPending || remove.isPending;
  const error = assign.error || unassign.error || remove.error;
  const available = useMemo(() => {
    const assigned = new Set(tag.sessions.map((session) => session.sessionId));
    return sessions.filter((session) => !assigned.has(session.sessionId));
  }, [sessions, tag.sessions]);

  const deleteTag = async () => {
    if (!deleteArmed) {
      setDeleteArmed(true);
      return;
    }
    await remove.mutateAsync(tag.tagId);
  };

  return (
    <article className="border border-border-subtle bg-surface-1">
      <header className="flex items-start justify-between gap-3 border-b border-border-subtle px-4 py-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span
              className="flex h-6 w-6 shrink-0 items-center justify-center"
              style={{ backgroundColor: `${tag.color}22`, color: tag.color }}
              aria-hidden="true"
            >
              <Tag size={12} />
            </span>
            <h3 className="truncate text-[12px] font-semibold text-text-primary">
              {tag.name}
            </h3>
            <span className="font-mono text-[9px] text-text-muted">
              {tag.sessionCount}
            </span>
          </div>
          <p className="mt-1 line-clamp-2 text-[10px] text-text-muted">
            {tag.description || '未填写标签说明'}
          </p>
        </div>
        {canAdminister && (
          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={() => setEditOpen(true)}
              className="flex h-8 w-8 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label={`编辑 ${tag.name}`}
            >
              <Pencil size={13} />
            </button>
            <button
              type="button"
              onClick={deleteTag}
              onBlur={() => setDeleteArmed(false)}
              disabled={busy}
              className={
                deleteArmed
                  ? 'h-8 bg-danger px-2 text-[10px] font-semibold text-white'
                  : 'flex h-8 w-8 items-center justify-center text-text-muted hover:bg-danger/10 hover:text-danger'
              }
              aria-label={`删除 ${tag.name}`}
            >
              {deleteArmed ? '再次确认' : <Trash2 size={13} />}
            </button>
          </div>
        )}
      </header>

      <div className="max-h-52 min-h-28 divide-y divide-border-subtle overflow-y-auto">
        {tag.sessions.length ? (
          tag.sessions.map((session) => (
            <div
              key={session.sessionId}
              className="flex items-center justify-between gap-3 px-4 py-2.5"
            >
              <div className="min-w-0">
                <Link
                  to={`/environments/${session.sessionId}`}
                  className="block truncate text-[11px] font-medium text-text-primary hover:text-accent"
                >
                  {session.displayName}
                </Link>
                <p className="mt-0.5 truncate font-mono text-[9px] text-text-muted">
                  {session.region} · {session.state}
                </p>
              </div>
              {canOperate && (
                <button
                  type="button"
                  onClick={() =>
                    unassign.mutate({
                      tagId: tag.tagId,
                      sessionId: session.sessionId,
                    })
                  }
                  disabled={busy}
                  className="flex h-8 w-8 shrink-0 items-center justify-center text-text-muted hover:bg-surface-3 hover:text-text-primary disabled:opacity-50"
                  aria-label={`从 ${session.displayName} 移除 ${tag.name}`}
                >
                  <X size={13} />
                </button>
              )}
            </div>
          ))
        ) : (
          <div className="flex min-h-28 items-center justify-center px-4 text-[10px] text-text-muted">
            暂无关联环境
          </div>
        )}
      </div>

      {canOperate && (
        <footer className="border-t border-border-subtle bg-surface-2 p-3">
          <div className="flex gap-2">
            <select
              value={selectedSession}
              onChange={(event) => setSelectedSession(event.target.value)}
              className="field-input min-w-0 flex-1"
              aria-label={`选择添加 ${tag.name} 的环境`}
            >
              <option value="">选择环境</option>
              {available.map((session) => (
                <option key={session.sessionId} value={session.sessionId}>
                  {session.displayName} · {session.sessionId}
                </option>
              ))}
            </select>
            <button
              type="button"
              disabled={!selectedSession || busy}
              onClick={async () => {
                await assign.mutateAsync({
                  tagId: tag.tagId,
                  sessionId: selectedSession,
                });
                setSelectedSession('');
              }}
              className="h-9 bg-accent px-3 text-[11px] font-semibold text-canvas disabled:opacity-40"
            >
              添加
            </button>
          </div>
          {error && (
            <p role="alert" className="mt-2 text-[10px] text-danger">
              {error.message}
            </p>
          )}
        </footer>
      )}

      {canOperate && tag.sessionCount > 0 && (
        <WorkspaceBatchActions
          label={tag.name}
          targetCount={tag.sessionCount}
          selector={{
            tagIds: [tag.tagId],
            tagMatch: 'ANY',
            sessionIds: [],
          }}
        />
      )}

      {canAdminister && (
        <TagEditorDialog open={editOpen} onOpenChange={setEditOpen} tag={tag} />
      )}
    </article>
  );
}
