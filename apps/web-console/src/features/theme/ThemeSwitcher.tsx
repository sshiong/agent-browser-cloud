import * as DropdownMenu from '@radix-ui/react-dropdown-menu';
import {
  Check,
  Laptop,
  LoaderCircle,
  Moon,
  Sun,
  TriangleAlert,
} from 'lucide-react';
import { isSessionApiError } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type { ThemeMode } from '@/types/userPreferences';
import { useTheme } from './ThemeProvider';

const OPTIONS: {
  mode: ThemeMode;
  label: string;
  description: string;
  icon: typeof Sun;
}[] = [
  {
    mode: 'SYSTEM',
    label: '跟随系统',
    description: '随操作系统外观自动切换',
    icon: Laptop,
  },
  {
    mode: 'DARK',
    label: '深色观测站',
    description: '适合长时间监控与低照度环境',
    icon: Moon,
  },
  {
    mode: 'LIGHT',
    label: '浅色控制台',
    description: '适合明亮环境与投屏协作',
    icon: Sun,
  },
];

export function ThemeSwitcher() {
  const theme = useTheme();
  const ActiveIcon = theme.saving
    ? LoaderCircle
    : theme.resolvedTheme === 'light'
      ? Sun
      : Moon;
  const activeLabel =
    OPTIONS.find((option) => option.mode === theme.mode)?.label ?? '跟随系统';
  const requestId =
    isSessionApiError(theme.error) && theme.error.body.requestId
      ? theme.error.body.requestId
      : null;
  const hasError = Boolean(theme.error);

  return (
    <DropdownMenu.Root>
      <DropdownMenu.Trigger asChild>
        <button
          type="button"
          aria-label={`主题：${activeLabel}`}
          title={`主题：${activeLabel}`}
          className={cn(
            'relative flex h-10 w-10 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-2 hover:text-text-primary md:h-8 md:w-8',
            hasError && 'text-danger'
          )}
        >
          <ActiveIcon
            size={16}
            className={theme.saving ? 'animate-spin' : undefined}
          />
          {hasError && (
            <span className="absolute right-0.5 top-0.5 h-1.5 w-1.5 rounded-full bg-danger md:right-0 md:top-0" />
          )}
        </button>
      </DropdownMenu.Trigger>

      <DropdownMenu.Portal>
        <DropdownMenu.Content
          align="end"
          sideOffset={8}
          className="z-[60] w-[276px] border border-border-default bg-surface-1 p-1.5 shadow-[0_18px_52px_rgba(3,10,18,0.28)] outline-none data-[state=open]:animate-[theme-menu-enter_150ms_cubic-bezier(0.16,1,0.3,1)]"
        >
          <div className="px-2.5 pb-2 pt-1.5">
            <p className="text-[11px] font-semibold text-text-primary">
              界面主题
            </p>
            <p className="mt-0.5 text-[9px] text-text-muted">
              偏好保存在工作区账户并同步至桌面端
            </p>
          </div>

          {OPTIONS.map((option) => {
            const Icon = option.icon;
            const selected = theme.mode === option.mode;
            return (
              <DropdownMenu.Item
                key={option.mode}
                disabled={theme.saving}
                onSelect={() => theme.setMode(option.mode)}
                className={cn(
                  'grid min-h-[52px] grid-cols-[28px_minmax(0,1fr)_16px] items-center gap-2.5 px-2.5 outline-none transition-colors data-[disabled]:opacity-45 data-[highlighted]:bg-surface-2',
                  selected && 'bg-accent-soft'
                )}
              >
                <span
                  className={cn(
                    'flex h-7 w-7 items-center justify-center border border-border-subtle text-text-muted',
                    selected && 'border-accent/35 text-accent'
                  )}
                >
                  <Icon size={14} />
                </span>
                <span className="min-w-0">
                  <span className="block text-[10px] font-medium text-text-primary">
                    {option.label}
                  </span>
                  <span className="mt-0.5 block truncate text-[9px] text-text-muted">
                    {option.description}
                  </span>
                </span>
                {selected && <Check size={13} className="text-accent" />}
              </DropdownMenu.Item>
            );
          })}

          {hasError && (
            <div
              role="alert"
              className="mt-1.5 flex gap-2 border-t border-danger/25 bg-danger/8 px-2.5 py-2 text-[9px] text-danger"
            >
              <TriangleAlert size={12} className="mt-0.5 shrink-0" />
              <span>
                主题偏好未保存，已恢复服务端状态。
                {requestId && (
                  <span className="mt-0.5 block font-mono">
                    Request {requestId}
                  </span>
                )}
              </span>
            </div>
          )}
        </DropdownMenu.Content>
      </DropdownMenu.Portal>
    </DropdownMenu.Root>
  );
}
