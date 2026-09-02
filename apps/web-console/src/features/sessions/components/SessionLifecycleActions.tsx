import * as Dialog from '@radix-ui/react-dialog';
import { LoaderCircle, Play, Square } from 'lucide-react';
import { useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { useAuth } from '@/auth/AuthProvider';
import { useStartSession, useStopSession } from '../api/sessionQueries';
import type { SessionView } from '@/types/session';

/** Shared by the Workspace overview and Environment table (including Tauri). */
export function SessionLifecycleActions({ session }: { session: SessionView }) {
  const auth = useAuth();
  const start = useStartSession(session.sessionId);
  const terminate = useStopSession(session.sessionId);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const pending = start.isPending || terminate.isPending;
  const busy = pending || Boolean(session.currentOperation);
  const canStart = ['CREATED', 'HIBERNATED', 'TERMINATED'].includes(
    session.state
  );
  const stopping = ['TERMINATING', 'HIBERNATING'].includes(session.state);
  const error = start.error || terminate.error;

  if (!auth.canOperate) return null;

  return (
    <div className="flex max-w-64 flex-col items-end gap-1">
      {canStart ? (
        <button
          type="button"
          aria-label={`启动 ${session.sessionId}`}
          title="启动会话（复用原有登录资料）"
          disabled={busy}
          onClick={() => {
            terminate.reset();
            start.mutate();
          }}
          className="flex h-8 w-8 items-center justify-center border border-success/30 text-success hover:bg-success/12 focus-visible:outline focus-visible:outline-2 focus-visible:outline-accent disabled:cursor-not-allowed disabled:opacity-40"
        >
          {start.isPending ? (
            <LoaderCircle size={14} className="animate-spin" />
          ) : (
            <Play size={14} fill="currentColor" />
          )}
        </button>
      ) : (
        <button
          type="button"
          aria-label={`停止 ${session.sessionId}`}
          title={stopping ? '正在停止运行' : '停止运行'}
          disabled={pending || stopping}
          onClick={() => {
            start.reset();
            terminate.reset();
            setConfirmOpen(true);
          }}
          className="flex h-8 w-8 items-center justify-center border border-danger/40 text-danger hover:bg-danger/10 focus-visible:outline focus-visible:outline-2 focus-visible:outline-danger disabled:cursor-not-allowed disabled:opacity-40"
        >
          {terminate.isPending || stopping ? (
            <LoaderCircle size={14} className="animate-spin" />
          ) : (
            <Square size={13} fill="currentColor" />
          )}
        </button>
      )}
      {error && !confirmOpen && <ActionError error={error} />}
      {(start.isSuccess || terminate.isSuccess) && (
        <span
          role="status"
          className="max-w-48 text-right text-[10px] text-text-muted"
        >
          {session.currentOperation
            ? '操作执行中，以服务端状态为准'
            : '请求已受理，以服务端状态为准'}
        </span>
      )}
      <Dialog.Root
        open={confirmOpen}
        onOpenChange={(open) => {
          if (!pending) setConfirmOpen(open);
        }}
      >
        <Dialog.Portal>
          <Dialog.Overlay className="fixed inset-0 z-[80] bg-canvas/65" />
          <Dialog.Content className="fixed left-1/2 top-1/2 z-[81] w-[min(440px,calc(100vw-32px))] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 p-5 shadow-2xl">
            <Dialog.Title className="text-[16px] font-semibold text-text-primary">
              是否停止运行？
            </Dialog.Title>
            <Dialog.Description className="mt-2 text-[12px] leading-5 text-text-muted">
              将关闭浏览器并保存登录资料、Cookie 和
              Profile，正在进行的任务会中断。之后可再次启动同一个环境；不会删除环境记录。
            </Dialog.Description>
            <p className="mt-4 break-all text-[13px] text-text-primary">
              {session.displayName}
            </p>
            <p className="mt-1 break-all font-mono text-[10px] text-text-muted">
              {session.sessionId}
            </p>
            {terminate.error && <ActionError error={terminate.error} />}
            <footer className="mt-5 flex justify-end gap-2 border-t border-border-subtle pt-4">
              <Dialog.Close asChild>
                <button
                  type="button"
                  disabled={pending}
                  className="h-9 border border-border-default px-3 text-[12px] text-text-secondary disabled:opacity-50"
                >
                  取消
                </button>
              </Dialog.Close>
              <button
                type="button"
                disabled={pending || stopping || canStart}
                onClick={() =>
                  terminate.mutate(undefined, {
                    onSuccess: () => setConfirmOpen(false),
                  })
                }
                className="inline-flex h-9 items-center gap-2 bg-danger px-3 text-[12px] font-semibold text-canvas disabled:opacity-50"
              >
                {terminate.isPending ? (
                  <LoaderCircle size={13} className="animate-spin" />
                ) : (
                  <Square size={12} fill="currentColor" />
                )}
                {terminate.isPending ? '正在提交' : '确认停止'}
              </button>
            </footer>
          </Dialog.Content>
        </Dialog.Portal>
      </Dialog.Root>
    </div>
  );
}

function ActionError({ error }: { error: Error }) {
  return (
    <p
      role="alert"
      className="mt-2 break-all text-[11px] leading-5 text-danger"
    >
      操作失败：{error.message}
      {isSessionApiError(error) && error.body.requestId && (
        <span className="block font-mono text-[10px]">
          Request ID: {error.body.requestId}
        </span>
      )}
    </p>
  );
}
