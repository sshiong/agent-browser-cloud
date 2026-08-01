import { useState } from 'react';
import {
  FolderKanban,
  Layers3,
  Pencil,
  Plus,
  Server,
  Trash2,
  Ungroup,
  X,
} from 'lucide-react';
import { Link } from 'react-router';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { useAuth } from '@/auth/AuthProvider';
import { GroupEditorDialog } from './GroupEditorDialog';
import { TagSection } from './TagSection';
import { WorkspaceBatchActions } from './WorkspaceBatchActions';
import {
  useAssignSessionToGroup,
  useDeleteWorkspaceGroup,
  useUnassignSessionFromGroup,
  useWorkspaceGroups,
} from './groupQueries';
import type { GroupSessionView, WorkspaceGroupView } from '@/types/group';
import type { MaximumReachedPolicy } from '@/types/session';

export function GroupsPage() {
  const auth = useAuth();
  const query = useWorkspaceGroups();
  const [createOpen, setCreateOpen] = useState(false);
  const canAdminister = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const platformAdmin = auth.hasAnyRole(['PLATFORM_ADMIN']);
  const assigned =
    query.data?.items.reduce((total, group) => total + group.sessionCount, 0) ??
    0;

  return (
    <div>
      <TopContextBar
        title="分组与标签"
        subtitle="PostgreSQL 权威分组、可复用标签、默认 AUTO 策略与环境归属"
      />

      <main className="p-4 sm:p-6">
        <section
          className="mb-4 grid grid-cols-1 border border-border-subtle bg-border-subtle sm:grid-cols-3"
          aria-label="分组指标"
        >
          <Metric
            icon={<FolderKanban size={15} />}
            label="Workspace Groups"
            value={String(query.data?.total ?? 0)}
          />
          <Metric
            icon={<Server size={15} />}
            label="已分组环境"
            value={String(assigned)}
          />
          <Metric
            icon={<Ungroup size={15} />}
            label="未分组环境"
            value={String(query.data?.unassignedSessions.length ?? 0)}
          />
        </section>

        <div className="mb-4 flex flex-col gap-3 border border-border-subtle bg-surface-1 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-[12px] font-medium text-text-primary">
              分组默认策略不会覆盖环境显式策略
            </p>
            <p className="mt-0.5 text-[10px] text-text-muted">
              所有创建、修改、归属和删除操作均写入租户审计链；删除分组不会终止环境。
            </p>
          </div>
          {canAdminister && (
            <button
              type="button"
              onClick={() => setCreateOpen(true)}
              className="inline-flex h-9 shrink-0 items-center justify-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas hover:bg-accent/90"
            >
              <Plus size={14} />
              新建分组
            </button>
          )}
        </div>

        {query.isLoading ? (
          <section className="border border-border-subtle bg-surface-1">
            <LoadingRows rows={6} />
          </section>
        ) : query.isError ? (
          <ErrorState
            error={query.error}
            onRetry={() => query.refetch()}
            title="无法加载 Workspace Groups"
          />
        ) : query.data?.items.length === 0 ? (
          <section className="border border-border-subtle bg-surface-1">
            <EmptyState
              title="尚未创建 Workspace Group"
              description="创建分组后可配置默认 AUTO 策略，并把已有环境纳入明确的运维边界。"
              action={
                canAdminister ? (
                  <button
                    type="button"
                    onClick={() => setCreateOpen(true)}
                    className="h-8 bg-accent px-3 text-[12px] font-semibold text-canvas"
                  >
                    创建第一个分组
                  </button>
                ) : null
              }
            />
          </section>
        ) : (
          <section className="grid gap-4 xl:grid-cols-2">
            {query.data?.items.map((group) => (
              <GroupCard
                key={group.groupId}
                group={group}
                unassigned={query.data?.unassignedSessions ?? []}
                canOperate={auth.canOperate}
                canAdminister={canAdminister}
                platformAdmin={platformAdmin}
              />
            ))}
          </section>
        )}

        <TagSection
          canOperate={auth.canOperate}
          canAdminister={canAdminister}
        />
      </main>

      {canAdminister && (
        <GroupEditorDialog
          open={createOpen}
          onOpenChange={setCreateOpen}
          platformAdmin={platformAdmin}
        />
      )}
    </div>
  );
}

function GroupCard({
  group,
  unassigned,
  canOperate,
  canAdminister,
  platformAdmin,
}: {
  group: WorkspaceGroupView;
  unassigned: GroupSessionView[];
  canOperate: boolean;
  canAdminister: boolean;
  platformAdmin: boolean;
}) {
  const assign = useAssignSessionToGroup();
  const unassign = useUnassignSessionFromGroup();
  const remove = useDeleteWorkspaceGroup();
  const [selectedSession, setSelectedSession] = useState('');
  const [editOpen, setEditOpen] = useState(false);
  const [deleteArmed, setDeleteArmed] = useState(false);
  const busy = assign.isPending || unassign.isPending || remove.isPending;
  const error = assign.error || unassign.error || remove.error;

  const deleteGroup = async () => {
    if (!deleteArmed) {
      setDeleteArmed(true);
      return;
    }
    await remove.mutateAsync(group.groupId);
  };

  return (
    <article className="border border-border-subtle bg-surface-1">
      <header className="flex items-start justify-between gap-3 border-b border-border-subtle px-4 py-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span
              className="h-2.5 w-2.5 shrink-0"
              style={{ backgroundColor: group.color }}
              aria-hidden="true"
            />
            <h2 className="truncate text-[13px] font-semibold text-text-primary">
              {group.name}
            </h2>
            <span className="bg-surface-3 px-2 py-0.5 font-mono text-[10px] text-text-muted">
              {group.sessionCount}
            </span>
          </div>
          <p className="mt-1 line-clamp-2 text-[10px] text-text-muted">
            {group.description || '未填写分组说明'}
          </p>
        </div>
        {canAdminister && (
          <div className="flex shrink-0 items-center gap-1">
            <button
              type="button"
              onClick={() => setEditOpen(true)}
              className="flex h-8 w-8 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label={`编辑 ${group.name}`}
            >
              <Pencil size={13} />
            </button>
            <button
              type="button"
              onClick={deleteGroup}
              onBlur={() => setDeleteArmed(false)}
              disabled={busy}
              className={
                deleteArmed
                  ? 'h-8 bg-danger px-2 text-[10px] font-semibold text-white'
                  : 'flex h-8 w-8 items-center justify-center text-text-muted hover:bg-danger/10 hover:text-danger'
              }
              aria-label={`删除 ${group.name}`}
            >
              {deleteArmed ? '再次确认' : <Trash2 size={13} />}
            </button>
          </div>
        )}
      </header>

      <div className="grid grid-cols-2 border-b border-border-subtle">
        <PolicyCell
          label="达到上限"
          value={maximumPolicyLabel(group.defaultOnMaximumReached)}
        />
        <PolicyCell
          label="恢复能力"
          value={
            [
              group.defaultAllowMigration ? '迁移' : null,
              group.defaultAllowHibernate ? '休眠' : null,
            ]
              .filter(Boolean)
              .join(' + ') || '均禁用'
          }
        />
      </div>

      <div className="min-h-32 divide-y divide-border-subtle">
        {group.sessions.length ? (
          group.sessions.map((session) => (
            <div
              key={session.sessionId}
              className="flex items-center justify-between gap-3 px-4 py-2.5 hover:bg-surface-2/50"
            >
              <div className="min-w-0">
                <Link
                  to={`/environments/${session.sessionId}`}
                  className="block truncate text-[11px] font-medium text-text-primary hover:text-accent"
                >
                  {session.displayName}
                </Link>
                <p className="mt-0.5 truncate font-mono text-[9px] text-text-muted">
                  {session.sessionId} · {session.region} · {session.state}
                </p>
              </div>
              {canOperate && (
                <button
                  type="button"
                  onClick={() =>
                    unassign.mutate({
                      groupId: group.groupId,
                      sessionId: session.sessionId,
                    })
                  }
                  disabled={busy}
                  className="flex h-8 w-8 shrink-0 items-center justify-center text-text-muted hover:bg-surface-3 hover:text-text-primary disabled:opacity-50"
                  aria-label={`从 ${group.name} 移除 ${session.displayName}`}
                >
                  <X size={13} />
                </button>
              )}
            </div>
          ))
        ) : (
          <div className="flex min-h-32 flex-col items-center justify-center px-4 text-center">
            <Layers3 size={18} className="text-text-muted" />
            <p className="mt-2 text-[11px] text-text-secondary">
              该分组暂无环境
            </p>
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
              aria-label={`选择加入 ${group.name} 的环境`}
            >
              <option value="">选择未分组环境</option>
              {unassigned.map((session) => (
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
                  groupId: group.groupId,
                  sessionId: selectedSession,
                });
                setSelectedSession('');
              }}
              className="h-9 bg-accent px-3 text-[11px] font-semibold text-canvas disabled:opacity-40"
            >
              加入
            </button>
          </div>
          {error && (
            <p role="alert" className="mt-2 text-[10px] text-danger">
              {error.message}
            </p>
          )}
        </footer>
      )}

      {canOperate && group.sessionCount > 0 && (
        <WorkspaceBatchActions
          label={group.name}
          targetCount={group.sessionCount}
          selector={{
            groupId: group.groupId,
            tagIds: [],
            tagMatch: 'ANY',
            sessionIds: [],
          }}
        />
      )}

      {canAdminister && (
        <GroupEditorDialog
          open={editOpen}
          onOpenChange={setEditOpen}
          group={group}
          platformAdmin={platformAdmin}
        />
      )}
    </article>
  );
}

function Metric({
  icon,
  label,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
}) {
  return (
    <div className="flex min-h-20 items-center gap-3 bg-surface-1 px-4 py-3">
      <span className="flex h-8 w-8 items-center justify-center bg-accent-soft text-accent">
        {icon}
      </span>
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          {label}
        </p>
        <p className="mt-0.5 font-mono text-[16px] font-semibold text-text-primary">
          {value}
        </p>
      </div>
    </div>
  );
}

function PolicyCell({ label, value }: { label: string; value: string }) {
  return (
    <div className="px-4 py-2.5 first:border-r first:border-border-subtle">
      <p className="text-[9px] uppercase tracking-[0.1em] text-text-muted">
        {label}
      </p>
      <p className="mt-0.5 text-[10px] font-medium text-text-secondary">
        {value}
      </p>
    </div>
  );
}

function maximumPolicyLabel(policy: MaximumReachedPolicy) {
  return (
    {
      PAUSE_AGENT: '暂停 Agent',
      WAIT_SAFE_POINT_MIGRATE: '安全点迁移',
      HIBERNATE: '自动休眠',
      TERMINATE_STRICT: '严格终止',
    } satisfies Record<MaximumReachedPolicy, string>
  )[policy];
}
