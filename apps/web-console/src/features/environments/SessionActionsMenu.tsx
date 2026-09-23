import * as Dialog from '@radix-ui/react-dialog';
import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Copy,
  Download,
  ExternalLink,
  LoaderCircle,
  MoreHorizontal,
  Pencil,
} from 'lucide-react';
import { FormEvent, useEffect, useState } from 'react';
import { useNavigate } from 'react-router';
import { useAuth } from '@/auth/AuthProvider';
import {
  useCloneSessionConfiguration,
  useExportSessionConfiguration,
  useUpdateSession,
} from '@/features/sessions/api/sessionQueries';
import type { CloneProfileMode } from '@/types/session';
import type { SessionView } from '@/types/session';

export function SessionActionsMenu({ session }: { session: SessionView }) {
  const auth = useAuth();
  const navigate = useNavigate();
  const [renameOpen, setRenameOpen] = useState(false);
  const [cloneOpen, setCloneOpen] = useState(false);
  const [displayName, setDisplayName] = useState(session.displayName);
  const [cloneName, setCloneName] = useState(`${session.displayName} 副本`);
  const [profileMode, setProfileMode] =
    useState<CloneProfileMode>('NEW_EMPTY_PROFILE');
  const mutation = useUpdateSession(session.sessionId);
  const cloneMutation = useCloneSessionConfiguration(session.sessionId);
  const exportMutation = useExportSessionConfiguration(session.sessionId);

  useEffect(() => {
    if (!renameOpen) setDisplayName(session.displayName);
  }, [renameOpen, session.displayName]);

  useEffect(() => {
    if (!cloneOpen) {
      setCloneName(`${session.displayName} 副本`);
      setProfileMode('NEW_EMPTY_PROFILE');
    }
  }, [cloneOpen, session.displayName]);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const normalized = displayName.trim();
    if (!normalized || normalized === session.displayName) return;
    mutation.mutate(
      { displayName: normalized },
      { onSuccess: () => setRenameOpen(false) }
    );
  };

  const submitClone = (event: FormEvent) => {
    event.preventDefault();
    const normalized = cloneName.trim();
    if (!normalized) return;
    cloneMutation.mutate(
      { displayName: normalized, profileMode },
      { onSuccess: () => setCloneOpen(false) }
    );
  };

  const exportConfiguration = () => {
    exportMutation.mutate(undefined, {
      onSuccess: (result) => {
        const blob = new Blob([JSON.stringify(result.manifest, null, 2)], {
          type: 'application/json',
        });
        const url = URL.createObjectURL(blob);
        const anchor = document.createElement('a');
        anchor.href = url;
        anchor.download = `${session.sessionId}.environment.json`;
        anchor.click();
        URL.revokeObjectURL(url);
      },
    });
  };

  return (
    <>
      <DropdownMenu.Root>
        <DropdownMenu.Trigger asChild>
          <button
            type="button"
            title="更多操作"
            className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted outline-none hover:bg-surface-2 hover:text-text-primary focus-visible:ring-2 focus-visible:ring-accent/50 data-[state=open]:bg-surface-2 data-[state=open]:text-text-primary"
            aria-label={`打开 ${session.displayName} 的更多操作`}
          >
            <MoreHorizontal size={14} />
          </button>
        </DropdownMenu.Trigger>
        <DropdownMenu.Portal>
          <DropdownMenu.Content
            align="end"
            sideOffset={5}
            collisionPadding={12}
            className="z-[70] min-w-[176px] border border-border-default bg-surface-1 p-1.5 shadow-[0_16px_42px_rgba(3,10,18,0.28)] outline-none"
          >
            <MenuItem
              icon={<ExternalLink size={13} />}
              label="查看详情"
              onSelect={() => navigate(`/environments/${session.sessionId}`)}
            />
            {auth.canOperate && (
              <>
                <MenuItem
                  icon={<Pencil size={13} />}
                  label="重命名环境"
                  onSelect={() => setRenameOpen(true)}
                />
                <MenuItem
                  icon={<Copy size={13} />}
                  label="复制环境配置"
                  onSelect={() => setCloneOpen(true)}
                />
                <MenuItem
                  icon={
                    exportMutation.isPending ? (
                      <LoaderCircle size={13} className="animate-spin" />
                    ) : (
                      <Download size={13} />
                    )
                  }
                  label="导出环境配置"
                  onSelect={exportConfiguration}
                />
              </>
            )}
          </DropdownMenu.Content>
        </DropdownMenu.Portal>
      </DropdownMenu.Root>

      <Dialog.Root open={renameOpen} onOpenChange={setRenameOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-[80] bg-canvas/65 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-[81] w-[min(440px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 p-5 shadow-[0_24px_80px_rgba(3,10,18,0.34)] outline-none">
            <Dialog.Title className="text-[16px] font-semibold text-text-primary">
              重命名环境
            </Dialog.Title>
            <Dialog.Description className="mt-1.5 text-[11px] leading-5 text-text-muted">
              只修改展示名称，不会重启浏览器，也不会改变 Profile、Runtime 或当前
              Operation。
            </Dialog.Description>
            <form onSubmit={submit} className="mt-5">
              <label className="block text-[11px] font-medium text-text-secondary">
                环境名称
                <input
                  autoFocus
                  value={displayName}
                  onChange={(event) => setDisplayName(event.target.value)}
                  maxLength={128}
                  className="mt-2 h-10 w-full border border-border-default bg-surface-2 px-3 text-[13px] text-text-primary outline-none focus:border-accent"
                />
              </label>
              {mutation.error && (
                <p role="alert" className="mt-3 text-[11px] text-danger">
                  {mutation.error.message}
                </p>
              )}
              <footer className="mt-5 flex justify-end gap-2 border-t border-border-subtle pt-4">
                <Dialog.Close asChild>
                  <button
                    type="button"
                    className="h-9 border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2"
                  >
                    取消
                  </button>
                </Dialog.Close>
                <button
                  type="submit"
                  disabled={
                    mutation.isPending ||
                    !displayName.trim() ||
                    displayName.trim() === session.displayName
                  }
                  className="inline-flex h-9 items-center gap-2 bg-accent px-3 text-[12px] font-semibold text-canvas disabled:opacity-50"
                >
                  {mutation.isPending && (
                    <LoaderCircle size={13} className="animate-spin" />
                  )}
                  保存名称
                </button>
              </footer>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <Dialog.Root open={cloneOpen} onOpenChange={setCloneOpen}>
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-[80] bg-canvas/65 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-[81] w-[min(480px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 p-5 shadow-[0_24px_80px_rgba(3,10,18,0.34)] outline-none">
            <Dialog.Title className="text-[16px] font-semibold text-text-primary">
              复制环境配置
            </Dialog.Title>
            <Dialog.Description className="mt-1.5 text-[11px] leading-5 text-text-muted">
              创建新的 Session 身份并复制
              Runtime、资源策略、代理、标签和浏览器身份配置。
              运行状态、任务历史、截图和录像不会复制。
            </Dialog.Description>
            <form onSubmit={submitClone} className="mt-5 space-y-4">
              <label className="block text-[11px] font-medium text-text-secondary">
                新环境名称
                <input
                  autoFocus
                  value={cloneName}
                  onChange={(event) => setCloneName(event.target.value)}
                  maxLength={128}
                  className="mt-2 h-10 w-full border border-border-default bg-surface-2 px-3 text-[13px] text-text-primary outline-none focus:border-accent"
                />
              </label>
              <label className="block text-[11px] font-medium text-text-secondary">
                Profile 策略
                <select
                  value={profileMode}
                  onChange={(event) =>
                    setProfileMode(event.target.value as CloneProfileMode)
                  }
                  className="mt-2 h-10 w-full border border-border-default bg-surface-2 px-3 text-[12px] text-text-primary outline-none focus:border-accent"
                >
                  <option value="NEW_EMPTY_PROFILE">
                    新建空 Profile（推荐）
                  </option>
                  <option value="REUSE_SOURCE_PROFILE">复用原 Profile</option>
                </select>
              </label>
              <p className="border border-border-subtle bg-surface-2 p-3 text-[11px] leading-5 text-text-muted">
                {profileMode === 'NEW_EMPTY_PROFILE'
                  ? '不会复制 Cookie、LocalStorage 或登录状态，适合独立运行。'
                  : '保留现有登录状态引用；不要同时启动两个写入同一 Profile 的环境。'}
              </p>
              {cloneMutation.error && (
                <p role="alert" className="text-[11px] text-danger">
                  {cloneMutation.error.message}
                </p>
              )}
              <footer className="flex justify-end gap-2 border-t border-border-subtle pt-4">
                <Dialog.Close asChild>
                  <button
                    type="button"
                    className="h-9 border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2"
                  >
                    取消
                  </button>
                </Dialog.Close>
                <button
                  type="submit"
                  disabled={cloneMutation.isPending || !cloneName.trim()}
                  className="inline-flex h-9 items-center gap-2 bg-accent px-3 text-[12px] font-semibold text-canvas disabled:opacity-50"
                >
                  {cloneMutation.isPending && (
                    <LoaderCircle size={13} className="animate-spin" />
                  )}
                  创建副本
                </button>
              </footer>
            </form>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>

      <Dialog.Root
        open={Boolean(exportMutation.error)}
        onOpenChange={(open) => {
          if (!open) exportMutation.reset();
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-[80] bg-canvas/65 backdrop-blur-[2px]" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-[81] w-[min(420px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 p-5 shadow-[0_24px_80px_rgba(3,10,18,0.34)] outline-none">
            <Dialog.Title className="text-[16px] font-semibold text-text-primary">
              导出失败
            </Dialog.Title>
            <Dialog.Description
              role="alert"
              className="mt-2 text-[12px] leading-5 text-danger"
            >
              {exportMutation.error?.message}
            </Dialog.Description>
            <footer className="mt-5 flex justify-end border-t border-border-subtle pt-4">
              <Dialog.Close asChild>
                <button
                  type="button"
                  className="h-9 border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2"
                >
                  关闭
                </button>
              </Dialog.Close>
            </footer>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </>
  );
}

function MenuItem({
  icon,
  label,
  onSelect,
}: {
  icon: React.ReactNode;
  label: string;
  onSelect: () => void;
}) {
  return (
    <DropdownMenu.Item
      onSelect={onSelect}
      className="flex h-9 cursor-default items-center gap-2 px-2.5 text-[11px] text-text-secondary outline-none data-[highlighted]:bg-surface-2 data-[highlighted]:text-text-primary"
    >
      <span className="text-text-muted">{icon}</span>
      {label}
    </DropdownMenu.Item>
  );
}
