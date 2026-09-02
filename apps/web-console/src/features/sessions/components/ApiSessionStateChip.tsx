import type { SessionState } from '@/types/session';
import { cn } from '@/shared/lib/utils';

const stateConfig: Record<
  SessionState,
  { label: string; className: string; pulse?: boolean }
> = {
  CREATED: { label: '已创建', className: 'bg-surface-3 text-text-secondary' },
  STARTING: {
    label: '启动中',
    className: 'bg-accent-secondary/15 text-accent-secondary',
    pulse: true,
  },
  RUNNING: { label: '运行中', className: 'bg-success/15 text-success' },
  DEGRADED: { label: '降级', className: 'bg-warning/15 text-warning' },
  HIBERNATING: {
    label: '停止中',
    className: 'bg-accent-secondary/15 text-accent-secondary',
    pulse: true,
  },
  HIBERNATED: { label: '已停止', className: 'bg-surface-3 text-text-muted' },
  RECOVERING: {
    label: '恢复中',
    className: 'bg-accent-secondary/15 text-accent-secondary',
    pulse: true,
  },
  TERMINATING: {
    label: '停止中',
    className: 'bg-warning/15 text-warning',
    pulse: true,
  },
  TERMINATED: { label: '已停止', className: 'bg-surface-3 text-text-muted' },
  FAILED: { label: '失败', className: 'bg-danger/15 text-danger' },
};

export function ApiSessionStateChip({ state }: { state: SessionState }) {
  const config = stateConfig[state];
  return (
    <span
      className={cn(
        'inline-flex shrink-0 items-center gap-1.5 whitespace-nowrap rounded-full px-2.5 py-0.5 text-[11px] font-medium',
        config.className,
        config.pulse && 'animate-pulse-status'
      )}
    >
      <span
        className="h-1.5 w-1.5 shrink-0 rounded-full bg-current"
        aria-hidden="true"
      />
      {config.label}
    </span>
  );
}
