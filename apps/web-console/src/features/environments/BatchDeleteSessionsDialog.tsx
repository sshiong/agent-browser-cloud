import * as Dialog from '@radix-ui/react-dialog';
import { LoaderCircle, ShieldAlert, Trash2 } from 'lucide-react';
import { useEffect } from 'react';
import { useBatchDeleteSessions } from '@/features/sessions/api/sessionQueries';
import type { SessionView } from '@/types/session';

export function BatchDeleteSessionsDialog({
  open,
  sessions,
  onOpenChange,
  onDeleted,
}: {
  open: boolean;
  sessions: SessionView[];
  onOpenChange: (open: boolean) => void;
  onDeleted: () => void;
}) {
  const mutation = useBatchDeleteSessions();
  const resetMutation = mutation.reset;

  useEffect(() => {
    if (!open) resetMutation();
  }, [open, resetMutation]);

  const remove = () => {
    mutation.mutate(
      { sessionIds: sessions.map((session) => session.sessionId) },
      {
        onSuccess: () => {
          onDeleted();
          onOpenChange(false);
        },
      }
    );
  };

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-[80] bg-canvas/65 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-[81] w-[min(500px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 p-5 shadow-[0_24px_80px_rgba(3,10,18,0.34)] outline-none">
          <div className="flex items-start gap-3">
            <span className="flex h-9 w-9 shrink-0 items-center justify-center border border-danger/30 bg-danger/10 text-danger">
              <ShieldAlert size={17} />
            </span>
            <div>
              <Dialog.Title className="text-[16px] font-semibold text-text-primary">
                删除 {sessions.length} 个环境？
              </Dialog.Title>
              <Dialog.Description className="mt-1.5 text-[11px] leading-5 text-text-muted">
                环境将从管理列表移除；审计、录制与恢复证据继续按保留策略保存。如需保留环境供下次使用，请取消并选择停止。
              </Dialog.Description>
            </div>
          </div>

          <div className="mt-5 max-h-40 overflow-auto border border-border-subtle bg-surface-2/70">
            {sessions.slice(0, 8).map((session) => (
              <div
                key={session.sessionId}
                className="flex items-center justify-between gap-4 border-b border-border-subtle px-3 py-2 last:border-b-0"
              >
                <span className="truncate text-[11px] text-text-secondary">
                  {session.displayName}
                </span>
                <span className="shrink-0 font-mono text-[9px] text-text-muted">
                  {session.sessionId}
                </span>
              </div>
            ))}
            {sessions.length > 8 && (
              <p className="px-3 py-2 text-[10px] text-text-muted">
                以及另外 {sessions.length - 8} 个环境
              </p>
            )}
          </div>

          {mutation.error && (
            <p role="alert" className="mt-3 text-[11px] leading-5 text-danger">
              删除失败：{mutation.error.message}
            </p>
          )}

          <footer className="mt-5 flex justify-end gap-2 border-t border-border-subtle pt-4">
            <Dialog.Close asChild>
              <button
                type="button"
                disabled={mutation.isPending}
                className="h-9 border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2 disabled:opacity-50"
              >
                取消
              </button>
            </Dialog.Close>
            <button
              type="button"
              onClick={remove}
              disabled={mutation.isPending || sessions.length === 0}
              className="inline-flex h-9 items-center gap-2 bg-danger px-3 text-[12px] font-semibold text-canvas disabled:opacity-50"
            >
              {mutation.isPending ? (
                <LoaderCircle size={13} className="animate-spin" />
              ) : (
                <Trash2 size={13} />
              )}
              确认删除
            </button>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
