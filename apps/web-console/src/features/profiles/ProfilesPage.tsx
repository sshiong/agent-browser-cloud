import { useMemo, useState } from 'react';
import {
  CheckCircle2,
  Database,
  FileArchive,
  Download,
  FileUp,
  Plus,
  Search,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { CreateProfileDialog } from '@/features/profiles/CreateProfileDialog';
import { ProfileImportDrawer } from '@/features/profiles/ProfileImportDrawer';
import { ProfileExportDialog } from '@/features/profiles/ProfileExportDialog';
import { useProfiles } from '@/features/profiles/profileQueries';
import { cn } from '@/shared/lib/utils';
import type { ProfileView } from '@/types/profile';
import { useAuth } from '@/auth/AuthProvider';

export function ProfilesPage() {
  const auth = useAuth();
  const query = useProfiles();
  const [search, setSearch] = useState('');
  const [createOpen, setCreateOpen] = useState(false);
  const [importOpen, setImportOpen] = useState(false);
  const [exportProfile, setExportProfile] = useState<ProfileView>();
  const canExport = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const profiles = useMemo(() => {
    const needle = search.trim().toLowerCase();
    if (!needle) return query.data?.items ?? [];
    return (query.data?.items ?? []).filter((profile) =>
      [
        profile.profileId,
        profile.name,
        profile.description,
        profile.latestCheckpointId,
      ]
        .filter(Boolean)
        .some((value) => value?.toLowerCase().includes(needle))
    );
  }, [query.data?.items, search]);

  const totalBytes =
    query.data?.items.reduce(
      (total, profile) => total + profile.coreSizeBytes,
      0
    ) ?? 0;
  const checkpointed =
    query.data?.items.filter((profile) => profile.latestCheckpointId).length ??
    0;

  return (
    <div>
      <TopContextBar
        title="Profile 存储"
        subtitle="Control Plane 元数据与 Browser Node 检查点的真实状态"
      />

      <main className="p-4 sm:p-6">
        <section
          className="mb-4 grid grid-cols-1 border border-border-subtle bg-border-subtle sm:grid-cols-3"
          aria-label="Profile 指标"
        >
          <Metric
            icon={<Database size={15} />}
            label="Profile"
            value={String(query.data?.total ?? 0)}
          />
          <Metric
            icon={<FileArchive size={15} />}
            label="已形成检查点"
            value={String(checkpointed)}
          />
          <Metric
            icon={<CheckCircle2 size={15} />}
            label="Core 占用"
            value={formatBytes(totalBytes)}
          />
        </section>

        <div className="mb-3 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <label className="relative block w-full sm:max-w-[340px]">
            <Search
              size={14}
              className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-text-muted"
            />
            <span className="sr-only">搜索 Profile</span>
            <input
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              className="field-input pl-9"
              placeholder="搜索 ID、名称或检查点"
            />
          </label>
          {auth.canOperate && (
            <div className="flex items-center gap-2">
              <button
                type="button"
                onClick={() => setImportOpen(true)}
                className="inline-flex h-9 items-center justify-center gap-2 border border-border-default px-4 text-[12px] font-medium text-text-secondary transition-colors hover:bg-surface-2 hover:text-text-primary"
              >
                <FileUp size={14} />
                导入 Checkpoint
              </button>
              <button
                type="button"
                onClick={() => setCreateOpen(true)}
                className="inline-flex h-9 items-center justify-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas transition-colors hover:bg-accent/90"
              >
                <Plus size={14} />
                新建 Profile
              </button>
            </div>
          )}
        </div>

        <section className="overflow-hidden border border-border-subtle bg-surface-1">
          {query.isLoading ? (
            <LoadingRows rows={5} />
          ) : query.isError ? (
            <ErrorState
              error={query.error}
              onRetry={() => query.refetch()}
              title="无法加载 Profile"
            />
          ) : profiles.length === 0 ? (
            <EmptyState
              title={search ? '没有匹配的 Profile' : '尚未创建 Profile'}
              description={
                search
                  ? '调整搜索条件，或清空关键词查看全部 Profile。'
                  : '创建第一个持久化 Profile；它会在 Session 安全停止后提交 Core 检查点。'
              }
              action={
                !search && auth.canOperate ? (
                  <div className="flex flex-wrap justify-center gap-2">
                    <button
                      type="button"
                      onClick={() => setImportOpen(true)}
                      className="h-8 border border-border-default px-3 text-[12px] text-text-secondary"
                    >
                      导入 Checkpoint
                    </button>
                    <button
                      type="button"
                      onClick={() => setCreateOpen(true)}
                      className="h-8 bg-accent px-3 text-[12px] font-semibold text-canvas"
                    >
                      创建空白 Profile
                    </button>
                  </div>
                ) : null
              }
            />
          ) : (
            <>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[900px]">
                  <thead>
                    <tr className="border-b border-border-subtle bg-surface-2">
                      {[
                        'Profile',
                        'Core / 文件',
                        '最新检查点',
                        '写入世代',
                        '恢复来源',
                        '更新时间',
                        '操作',
                      ].map((label) => (
                        <th
                          key={label}
                          className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted"
                        >
                          {label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {profiles.map((profile) => (
                      <ProfileRow
                        key={profile.profileId}
                        profile={profile}
                        canExport={canExport}
                        onExport={setExportProfile}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="divide-y divide-border-subtle md:hidden">
                {profiles.map((profile) => (
                  <ProfileCard
                    key={profile.profileId}
                    profile={profile}
                    canExport={canExport}
                    onExport={setExportProfile}
                  />
                ))}
              </div>
            </>
          )}
        </section>
      </main>

      {auth.canOperate && (
        <>
          <CreateProfileDialog open={createOpen} onOpenChange={setCreateOpen} />
          <ProfileImportDrawer open={importOpen} onOpenChange={setImportOpen} />
        </>
      )}
      <ProfileExportDialog
        profile={exportProfile}
        onOpenChange={() => setExportProfile(undefined)}
      />
    </div>
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

function ProfileRow({
  profile,
  canExport,
  onExport,
}: {
  profile: ProfileView;
  canExport: boolean;
  onExport: (profile: ProfileView) => void;
}) {
  return (
    <tr className="border-b border-border-subtle last:border-b-0 hover:bg-surface-2/60">
      <td className="px-4 py-3.5">
        <p className="text-[13px] font-medium text-text-primary">
          {profile.name}
        </p>
        <p className="mt-0.5 font-mono text-[10px] text-text-muted">
          {profile.profileId}
        </p>
      </td>
      <td className="px-4 py-3.5">
        <p className="font-mono text-[12px] text-text-secondary">
          {formatBytes(profile.coreSizeBytes)}
        </p>
        <p className="text-[10px] text-text-muted">
          {profile.checkpointFileCount.toLocaleString()} files
        </p>
      </td>
      <td className="px-4 py-3.5">
        {profile.latestCheckpointId ? (
          <>
            <p className="max-w-[190px] truncate font-mono text-[11px] text-text-secondary">
              {profile.latestCheckpointId}
            </p>
            <p className="text-[10px] text-text-muted">
              epoch {profile.latestCheckpointEpoch}
            </p>
          </>
        ) : (
          <span className="text-[11px] text-text-muted">尚未提交</span>
        )}
      </td>
      <td className="px-4 py-3.5 font-mono text-[12px] text-text-secondary">
        {profile.profileWriteEpoch}
      </td>
      <td className="px-4 py-3.5">
        <RestoreChip status={profile.restoreStatus} />
      </td>
      <td className="px-4 py-3.5 text-[11px] text-text-muted">
        {formatDate(profile.updatedAt)}
      </td>
      <td className="px-4 py-3.5">
        {canExport && profile.latestCheckpointId && (
          <button
            type="button"
            onClick={() => onExport(profile)}
            className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[11px] text-text-secondary hover:bg-surface-2 hover:text-text-primary"
          >
            <Download size={13} /> 导出
          </button>
        )}
      </td>
    </tr>
  );
}

function ProfileCard({
  profile,
  canExport,
  onExport,
}: {
  profile: ProfileView;
  canExport: boolean;
  onExport: (profile: ProfileView) => void;
}) {
  return (
    <article className="p-4">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-[13px] font-medium text-text-primary">
            {profile.name}
          </h2>
          <p className="truncate font-mono text-[10px] text-text-muted">
            {profile.profileId}
          </p>
        </div>
        <RestoreChip status={profile.restoreStatus} />
      </div>
      <dl className="mt-4 grid grid-cols-2 gap-x-4 gap-y-3">
        <Datum label="Core" value={formatBytes(profile.coreSizeBytes)} />
        <Datum label="文件" value={String(profile.checkpointFileCount)} />
        <Datum
          label="检查点"
          value={
            profile.latestCheckpointEpoch
              ? `epoch ${profile.latestCheckpointEpoch}`
              : '尚未提交'
          }
        />
        <Datum label="写入世代" value={String(profile.profileWriteEpoch)} />
      </dl>
      {canExport && profile.latestCheckpointId && (
        <button
          type="button"
          onClick={() => onExport(profile)}
          className="mt-4 inline-flex h-8 w-full items-center justify-center gap-2 border border-border-default text-[11px] text-text-secondary"
        >
          <Download size={13} /> 导出 Checkpoint
        </button>
      )}
    </article>
  );
}

function Datum({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[10px] uppercase tracking-wider text-text-muted">
        {label}
      </dt>
      <dd className="mt-0.5 font-mono text-[11px] text-text-secondary">
        {value}
      </dd>
    </div>
  );
}

function RestoreChip({ status }: { status: ProfileView['restoreStatus'] }) {
  const ready = status === 'TECHNICAL_READY';
  return (
    <span
      className={cn(
        'inline-flex whitespace-nowrap border px-2 py-0.5 text-[10px] font-medium',
        ready
          ? 'border-success/25 bg-success/10 text-success'
          : 'border-border-default bg-surface-2 text-text-muted'
      )}
    >
      {ready ? '检查点恢复' : '空白初始化'}
    </span>
  );
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KiB', 'MiB', 'GiB', 'TiB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}
