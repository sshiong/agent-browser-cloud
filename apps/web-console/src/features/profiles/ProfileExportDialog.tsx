import * as Dialog from '@radix-ui/react-dialog';
import { AlertTriangle, Download, LoaderCircle, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { useProfileExport } from '@/features/profiles/profileQueries';
import { usePlatform } from '@/platform/PlatformProvider';
import type { ProfileExportPurpose, ProfileView } from '@/types/profile';

const purposes: Array<{ value: ProfileExportPurpose; label: string }> = [
  { value: 'TENANT_BACKUP', label: '租户备份' },
  { value: 'INCIDENT_RESPONSE', label: '事件响应' },
  { value: 'SUPPORT_DIAGNOSTICS', label: '支持诊断' },
  { value: 'COMPLIANCE_EXPORT', label: '合规导出' },
];

export function ProfileExportDialog({
  profile,
  onOpenChange,
}: {
  profile?: ProfileView;
  onOpenChange: (open: boolean) => void;
}) {
  const mutation = useProfileExport();
  const resetMutation = mutation.reset;
  const platform = usePlatform();
  const [purpose, setPurpose] = useState<ProfileExportPurpose>('TENANT_BACKUP');
  const [confirmed, setConfirmed] = useState(false);
  const [openingError, setOpeningError] = useState<string>();

  useEffect(() => {
    if (!profile) {
      resetMutation();
      setConfirmed(false);
      setOpeningError(undefined);
    }
  }, [profile, resetMutation]);

  const submit = async () => {
    if (!profile || !confirmed) return;
    setOpeningError(undefined);
    try {
      const access = await mutation.mutateAsync({
        profileId: profile.profileId,
        purpose,
      });
      await platform.openExternal(access.downloadUrl);
    } catch (error) {
      if (!isSessionApiError(error)) {
        setOpeningError(
          error instanceof Error ? error.message : '一次性导出链接打开失败'
        );
      }
    }
  };

  return (
    <Dialog.Root
      open={Boolean(profile)}
      onOpenChange={(open) => !open && onOpenChange(false)}
    >
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-[520px] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
            <div>
              <Dialog.Title className="text-[15px] font-semibold text-text-primary">
                导出 Profile Checkpoint
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-[11px] text-text-muted">
                {profile?.name} · {profile?.latestCheckpointId}
              </Dialog.Description>
            </div>
            <Dialog.Close
              className="text-text-muted hover:text-text-primary"
              aria-label="关闭"
            >
              <X size={17} />
            </Dialog.Close>
          </header>

          <div className="space-y-4 p-5">
            <div className="border border-warning/30 bg-warning/10 p-3 text-[11px] leading-5 text-text-secondary">
              <div className="flex items-center gap-2 font-semibold text-warning">
                <AlertTriangle size={14} /> 高敏感数据导出
              </div>
              <p className="mt-1">
                归档可能包含 Cookie、登录状态与站点数据。授权仅属于当前操作者，5
                分钟内可兑换一次；下载链接 60 秒失效且不会保存到平台。
              </p>
            </div>

            <label className="block">
              <span className="field-label">导出用途</span>
              <select
                className="field-input mt-1"
                value={purpose}
                onChange={(event) =>
                  setPurpose(event.target.value as ProfileExportPurpose)
                }
                disabled={mutation.isPending}
              >
                {purposes.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>

            <label className="flex items-start gap-2 text-[11px] leading-5 text-text-secondary">
              <input
                type="checkbox"
                className="mt-1 accent-accent"
                checked={confirmed}
                onChange={(event) => setConfirmed(event.target.checked)}
                disabled={mutation.isPending}
              />
              我确认导出目的合法，并会按照租户安全策略保存和传输该归档。
            </label>

            {(mutation.error || openingError) && (
              <div
                role="alert"
                className="border border-danger/30 bg-danger/10 p-3 text-[11px] text-danger"
              >
                {mutation.error?.message || openingError}
                {isSessionApiError(mutation.error) &&
                mutation.error.body.requestId
                  ? ` · Request ID ${mutation.error.body.requestId}`
                  : ''}
              </div>
            )}

            {mutation.data && !openingError && (
              <div className="border border-success/25 bg-success/10 p-3 text-[11px] text-text-secondary">
                已验证归档 {formatBytes(mutation.data.archiveSizeBytes)} ·
                SHA-256{' '}
                <span className="font-mono">
                  {mutation.data.archiveSha256.slice(0, 16)}…
                </span>
              </div>
            )}
          </div>

          <footer className="flex justify-end gap-2 border-t border-border-subtle px-5 py-4">
            <Dialog.Close className="h-9 border border-border-default px-4 text-[12px] text-text-secondary">
              取消
            </Dialog.Close>
            <button
              type="button"
              onClick={submit}
              disabled={!confirmed || mutation.isPending || !profile}
              className="inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas disabled:cursor-not-allowed disabled:opacity-50"
            >
              {mutation.isPending ? (
                <LoaderCircle size={14} className="animate-spin" />
              ) : (
                <Download size={14} />
              )}
              创建并兑换一次性授权
            </button>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function formatBytes(bytes: number) {
  if (bytes < 1024) return `${bytes} B`;
  const units = ['KiB', 'MiB', 'GiB'];
  let value = bytes / 1024;
  let unit = 0;
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024;
    unit += 1;
  }
  return `${value.toFixed(value >= 10 ? 1 : 2)} ${units[unit]}`;
}
