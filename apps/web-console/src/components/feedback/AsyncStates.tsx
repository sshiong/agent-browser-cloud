import { AlertTriangle, Inbox, LoaderCircle, RotateCw } from 'lucide-react';
import { isSessionApiError } from '@/api/session';

export function LoadingRows({ rows = 6 }: { rows?: number }) {
  return (
    <div
      aria-busy="true"
      aria-label="正在加载"
      className="space-y-px bg-border-subtle"
    >
      {Array.from({ length: rows }, (_, index) => (
        <div
          key={index}
          className="grid h-16 animate-pulse grid-cols-[1.4fr_1fr_1fr_0.8fr] items-center gap-6 bg-surface-1 px-5"
        >
          <span className="h-3 w-3/4 rounded bg-surface-3" />
          <span className="h-3 w-1/2 rounded bg-surface-3" />
          <span className="h-3 w-2/3 rounded bg-surface-3" />
          <span className="h-5 w-16 rounded-full bg-surface-3" />
        </div>
      ))}
    </div>
  );
}

export function LoadingPanel({ label = '正在加载数据' }: { label?: string }) {
  return (
    <div
      className="flex min-h-64 items-center justify-center text-text-secondary"
      aria-live="polite"
    >
      <LoaderCircle className="mr-2 animate-spin text-accent" size={18} />
      <span className="text-[13px]">{label}</span>
    </div>
  );
}

export function ErrorState({
  error,
  onRetry,
  title = '无法加载数据',
}: {
  error: unknown;
  onRetry?: () => void;
  title?: string;
}) {
  const requestId = isSessionApiError(error) ? error.body.requestId : undefined;
  const message =
    error instanceof Error
      ? error.message
      : 'Control Plane 返回了无法识别的错误。';

  return (
    <div
      className="flex min-h-64 flex-col items-center justify-center px-6 text-center"
      role="alert"
    >
      <div className="mb-4 flex h-10 w-10 items-center justify-center rounded-full bg-danger/12 text-danger">
        <AlertTriangle size={19} />
      </div>
      <h2 className="text-[15px] font-semibold text-text-primary">{title}</h2>
      <p className="mt-1 max-w-lg text-[12px] text-text-muted">{message}</p>
      {requestId && (
        <p className="mt-2 font-mono text-[11px] text-text-muted">
          Request ID: {requestId}
        </p>
      )}
      {onRetry && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-border-default bg-surface-2 px-3 text-[12px] text-text-secondary transition-colors hover:border-accent/40 hover:text-text-primary"
        >
          <RotateCw size={13} />
          重试
        </button>
      )}
    </div>
  );
}

export function EmptyState({
  title,
  description,
  action,
}: {
  title: string;
  description: string;
  action?: React.ReactNode;
}) {
  return (
    <div className="flex min-h-72 flex-col items-center justify-center px-6 text-center">
      <div className="mb-4 flex h-11 w-11 items-center justify-center rounded-full bg-accent-soft text-accent">
        <Inbox size={20} />
      </div>
      <h2 className="text-[15px] font-semibold text-text-primary">{title}</h2>
      <p className="mt-1 max-w-md text-[12px] text-text-muted">{description}</p>
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
