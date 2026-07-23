import { cn } from '@/shared/lib/utils';
import type { SessionState, HealthStatus } from '@/types';

const stateConfig: Record<
  SessionState,
  { label: string; color: string; bg: string }
> = {
  created: { label: '已创建', color: 'text-text-muted', bg: 'bg-surface-3' },
  starting: {
    label: '启动中',
    color: 'text-accent-secondary',
    bg: 'bg-accent-secondary/15',
  },
  running: { label: '运行中', color: 'text-success', bg: 'bg-success/15' },
  idle: { label: '空闲', color: 'text-text-secondary', bg: 'bg-surface-3' },
  human_controlled: {
    label: '人工控制',
    color: 'text-purple',
    bg: 'bg-purple/15',
  },
  degraded: { label: '降级', color: 'text-warning', bg: 'bg-warning/15' },
  recovering: {
    label: '恢复中',
    color: 'text-accent-secondary',
    bg: 'bg-accent-secondary/15',
  },
  hibernated: { label: '已休眠', color: 'text-text-muted', bg: 'bg-surface-3' },
  stopping: { label: '停止中', color: 'text-warning', bg: 'bg-warning/15' },
  stopped: { label: '已停止', color: 'text-text-muted', bg: 'bg-surface-3' },
  failed: { label: '失败', color: 'text-danger', bg: 'bg-danger/15' },
};

const healthConfig: Record<
  HealthStatus,
  { label: string; color: string; bg: string }
> = {
  healthy: { label: '健康', color: 'text-success', bg: 'bg-success/15' },
  warning: { label: '警告', color: 'text-warning', bg: 'bg-warning/15' },
  critical: { label: '严重', color: 'text-danger', bg: 'bg-danger/15' },
  unknown: { label: '未知', color: 'text-text-muted', bg: 'bg-surface-3' },
};

interface StatusChipProps {
  state: SessionState;
  className?: string;
}

export function SessionStateChip({ state, className }: StatusChipProps) {
  const config = stateConfig[state];
  const isPulsing = state === 'starting' || state === 'recovering';
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-medium',
        config.bg,
        config.color,
        isPulsing && 'animate-pulse-status',
        className
      )}
    >
      <span
        className={cn(
          'h-1.5 w-1.5 rounded-full',
          config.color.replace('text-', 'bg-')
        )}
      />
      {config.label}
    </span>
  );
}

interface HealthChipProps {
  status: HealthStatus;
  className?: string;
}

export function HealthChip({ status, className }: HealthChipProps) {
  const config = healthConfig[status];
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1.5 rounded-full px-2.5 py-0.5 text-[11px] font-medium',
        config.bg,
        config.color,
        className
      )}
    >
      <span
        className={cn(
          'h-1.5 w-1.5 rounded-full',
          config.color.replace('text-', 'bg-')
        )}
      />
      {config.label}
    </span>
  );
}
