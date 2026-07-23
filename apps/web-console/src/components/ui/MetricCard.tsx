import { cn } from '@/shared/lib/utils';
import type { LucideIcon } from 'lucide-react';

interface MetricCardProps {
  label: string;
  value: string | number;
  change?: string;
  changeType?: 'up' | 'down' | 'neutral';
  icon: LucideIcon;
  iconColor?: string;
  className?: string;
}

export function MetricCard({
  label,
  value,
  change,
  changeType = 'neutral',
  icon: Icon,
  iconColor = 'text-accent',
  className,
}: MetricCardProps) {
  return (
    <div
      className={cn(
        'rounded-[10px] border border-border-subtle bg-surface-1 p-4 transition-colors hover:border-border-default',
        className
      )}
    >
      <div className="flex items-start justify-between">
        <div>
          <p className="text-[12px] text-text-muted">{label}</p>
          <p className="mt-1 text-[24px] font-semibold text-text-primary">
            {value}
          </p>
          {change && (
            <p
              className={cn(
                'mt-1 text-[11px]',
                changeType === 'up' && 'text-success',
                changeType === 'down' && 'text-danger',
                changeType === 'neutral' && 'text-text-muted'
              )}
            >
              {change}
            </p>
          )}
        </div>
        <div className={cn('rounded-lg bg-surface-2 p-2', iconColor)}>
          <Icon size={18} />
        </div>
      </div>
    </div>
  );
}
