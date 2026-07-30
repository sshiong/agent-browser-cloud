import * as Dialog from '@radix-ui/react-dialog';
import {
  Bell,
  Bot,
  CheckCheck,
  ChevronRight,
  CircleAlert,
  LoaderCircle,
  RefreshCw,
  Rocket,
  ServerCog,
  ShieldAlert,
  X,
} from 'lucide-react';
import { useState } from 'react';
import { useNavigate } from 'react-router';
import { isSessionApiError } from '@/api/session';
import { useOnlineStatus } from '@/shared/hooks/useOnlineStatus';
import { cn } from '@/shared/lib/utils';
import type {
  NotificationCategory,
  NotificationSeverity,
  WorkspaceNotification,
} from '@/types/notification';
import {
  useMarkWorkspaceNotificationsRead,
  useWorkspaceNotifications,
} from './notificationQueries';

const CATEGORY_LABELS: Record<NotificationCategory, string> = {
  SECURITY: '安全',
  RESOURCE: '资源',
  AGENT: 'Agent',
  RELEASE: '发布',
  SYSTEM: '系统',
};

const SEVERITY_STYLES: Record<
  NotificationSeverity,
  { dot: string; icon: string; rail: string }
> = {
  INFO: {
    dot: 'bg-accent-secondary',
    icon: 'text-accent-secondary',
    rail: 'border-l-accent-secondary/45',
  },
  WARNING: {
    dot: 'bg-warning',
    icon: 'text-warning',
    rail: 'border-l-warning/55',
  },
  CRITICAL: {
    dot: 'bg-danger',
    icon: 'text-danger',
    rail: 'border-l-danger/70',
  },
};

export function WorkspaceNotificationCenter() {
  const [open, setOpen] = useState(false);
  const isOnline = useOnlineStatus();
  const navigate = useNavigate();
  const feed = useWorkspaceNotifications(true);
  const markRead = useMarkWorkspaceNotificationsRead();
  const pages = feed.data?.pages;
  const latest = pages?.[0];
  const items = pages?.flatMap((page) => page.items) ?? [];
  const unreadCount = latest?.unreadCount ?? 0;
  const headSequence = latest?.headSequence ?? 0;

  const openNotification = (item: WorkspaceNotification) => {
    setOpen(false);
    navigate(item.route);
  };

  const markAllRead = () => {
    if (headSequence > 0 && unreadCount > 0 && !markRead.isPending) {
      markRead.mutate(headSequence);
    }
  };

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>
        <button
          type="button"
          aria-label={
            unreadCount > 0 ? `通知中心，${unreadCount} 条未读` : '通知中心'
          }
          title="通知中心"
          className="relative flex h-10 w-10 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-2 hover:text-text-primary md:h-8 md:w-8"
        >
          <Bell size={16} />
          {unreadCount > 0 && (
            <span className="absolute right-0.5 top-0.5 flex min-h-4 min-w-4 items-center justify-center rounded-full bg-danger px-1 font-mono text-[8px] font-semibold leading-none text-canvas md:-right-1 md:-top-1">
              {unreadCount > 99 ? '99+' : unreadCount}
            </span>
          )}
        </button>
      </Dialog.Trigger>

      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-[#05080d]/72 data-[state=closed]:opacity-0 data-[state=open]:animate-[notification-fade_140ms_ease-out]" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[440px] flex-col border-l border-border-default bg-surface-1 shadow-[-24px_0_70px_rgba(0,0,0,0.4)] outline-none data-[state=closed]:translate-x-full data-[state=open]:animate-[notification-enter_190ms_cubic-bezier(0.16,1,0.3,1)]">
          <Dialog.Title className="sr-only">工作区通知中心</Dialog.Title>
          <Dialog.Description className="sr-only">
            查看来自 PostgreSQL 审计事件的安全、资源、Agent 和发布通知。
          </Dialog.Description>

          <div className="flex min-h-16 items-center gap-3 border-b border-border-subtle px-4 sm:px-5">
            <div className="min-w-0 flex-1">
              <div className="flex items-baseline gap-2">
                <h2 className="text-[14px] font-semibold text-text-primary">
                  工作区通知
                </h2>
                <span className="font-mono text-[9px] uppercase tracking-[0.12em] text-text-muted">
                  Audit Signal
                </span>
              </div>
              <p className="mt-0.5 text-[10px] text-text-muted">
                {unreadCount > 0
                  ? `${unreadCount} 条需要查看`
                  : '已处理当前全部高信号事件'}
              </p>
            </div>

            <button
              type="button"
              onClick={markAllRead}
              disabled={
                unreadCount === 0 || headSequence === 0 || markRead.isPending
              }
              className="flex min-h-10 items-center gap-1.5 px-2 text-[10px] text-text-secondary hover:text-accent disabled:cursor-not-allowed disabled:opacity-35"
            >
              {markRead.isPending ? (
                <LoaderCircle size={13} className="animate-spin" />
              ) : (
                <CheckCheck size={13} />
              )}
              全部已读
            </button>
            <Dialog.Close asChild>
              <button
                type="button"
                aria-label="关闭通知中心"
                className="flex h-10 w-10 items-center justify-center text-text-muted hover:text-text-primary"
              >
                <X size={16} />
              </button>
            </Dialog.Close>
          </div>

          {!isOnline && (
            <div className="flex items-center gap-2 border-b border-warning/25 bg-warning/8 px-5 py-2 text-[10px] text-warning">
              <CircleAlert size={13} />
              当前离线，通知数据可能已经过期
            </div>
          )}

          <div className="min-h-0 flex-1 overflow-y-auto bg-canvas/30">
            {feed.isLoading ? (
              <NotificationLoading />
            ) : feed.isError ? (
              <NotificationError
                error={feed.error}
                onRetry={() => void feed.refetch()}
              />
            ) : items.length === 0 ? (
              <NotificationEmpty />
            ) : (
              <div>
                {items.map((item) => (
                  <NotificationRow
                    key={item.notificationId}
                    item={item}
                    onOpen={() => openNotification(item)}
                  />
                ))}
                {feed.hasNextPage && (
                  <button
                    type="button"
                    onClick={() => void feed.fetchNextPage()}
                    disabled={feed.isFetchingNextPage}
                    className="flex min-h-12 w-full items-center justify-center gap-2 border-t border-border-subtle text-[10px] text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:opacity-50"
                  >
                    {feed.isFetchingNextPage && (
                      <LoaderCircle size={12} className="animate-spin" />
                    )}
                    加载更早通知
                  </button>
                )}
              </div>
            )}
          </div>

          <div className="flex min-h-10 items-center justify-between gap-3 border-t border-border-subtle px-4 text-[9px] text-text-muted sm:px-5">
            <span>PostgreSQL 审计投影 · 90 天</span>
            <span className="font-mono uppercase tracking-[0.1em]">
              15s refresh
            </span>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function NotificationRow({
  item,
  onOpen,
}: {
  item: WorkspaceNotification;
  onOpen: () => void;
}) {
  const style = SEVERITY_STYLES[item.severity];
  return (
    <button
      type="button"
      onClick={onOpen}
      className={cn(
        'group grid min-h-[104px] w-full grid-cols-[28px_minmax(0,1fr)_16px] gap-3 border-b border-l-2 border-b-border-subtle px-4 py-3.5 text-left transition-colors hover:bg-surface-2 focus:bg-surface-2 focus:outline-none sm:px-5',
        style.rail,
        !item.read && 'bg-surface-2/35'
      )}
    >
      <span
        className={cn(
          'mt-0.5 flex h-7 w-7 items-center justify-center',
          style.icon
        )}
      >
        <NotificationIcon category={item.category} />
      </span>
      <span className="min-w-0">
        <span className="flex items-start gap-2">
          <span className="min-w-0 flex-1 truncate text-[11px] font-medium text-text-primary">
            {item.title}
          </span>
          {!item.read && (
            <span className="mt-1 flex shrink-0 items-center gap-1 font-mono text-[8px] uppercase tracking-[0.1em] text-text-secondary">
              <span className={cn('h-1.5 w-1.5 rounded-full', style.dot)} />
              未读
            </span>
          )}
        </span>
        <span className="mt-1 block truncate text-[10px] text-text-secondary">
          {item.body}
        </span>
        <span className="mt-2 flex min-w-0 items-center gap-2 font-mono text-[8px] uppercase tracking-[0.08em] text-text-muted">
          <span>{CATEGORY_LABELS[item.category]}</span>
          <span aria-hidden="true">/</span>
          <span className="truncate">{item.eventType}</span>
        </span>
        <span className="mt-1.5 flex items-center gap-2 text-[9px] text-text-muted">
          <time dateTime={item.occurredAt}>{formatTime(item.occurredAt)}</time>
          {item.requestId && item.severity === 'CRITICAL' && (
            <>
              <span aria-hidden="true">·</span>
              <span className="truncate font-mono">
                Request {item.requestId}
              </span>
            </>
          )}
        </span>
      </span>
      <ChevronRight
        size={14}
        className="mt-1 text-text-muted transition-transform group-hover:translate-x-0.5 group-hover:text-accent"
      />
    </button>
  );
}

function NotificationIcon({ category }: { category: NotificationCategory }) {
  switch (category) {
    case 'SECURITY':
      return <ShieldAlert size={15} />;
    case 'RESOURCE':
      return <ServerCog size={15} />;
    case 'AGENT':
      return <Bot size={15} />;
    case 'RELEASE':
      return <Rocket size={15} />;
    default:
      return <CircleAlert size={15} />;
  }
}

function NotificationLoading() {
  return (
    <div className="flex min-h-[360px] items-center justify-center gap-2 text-[10px] text-text-muted">
      <LoaderCircle size={14} className="animate-spin text-accent" />
      正在读取权威通知投影
    </div>
  );
}

function NotificationEmpty() {
  return (
    <div className="flex min-h-[420px] flex-col items-center justify-center px-8 text-center">
      <Bell size={22} className="text-text-muted" />
      <p className="mt-3 text-[12px] font-medium text-text-secondary">
        当前没有高信号通知
      </p>
      <p className="mt-1 max-w-[280px] text-[10px] leading-5 text-text-muted">
        审批、安全事件、资源上限、迁移与执行失败会从不可篡改审计账本进入这里。
      </p>
    </div>
  );
}

function NotificationError({
  error,
  onRetry,
}: {
  error: unknown;
  onRetry: () => void;
}) {
  const requestId = isSessionApiError(error) ? error.body.requestId : null;
  return (
    <div className="flex min-h-[420px] flex-col items-center justify-center px-8 text-center">
      <CircleAlert size={22} className="text-danger" />
      <p className="mt-3 text-[12px] font-medium text-text-primary">
        通知读取失败
      </p>
      <p className="mt-1 text-[10px] text-text-muted">
        权威数据不可用时不会显示缓存结果。
      </p>
      {requestId && (
        <p className="mt-2 font-mono text-[9px] text-text-muted">
          Request {requestId}
        </p>
      )}
      <button
        type="button"
        onClick={onRetry}
        className="mt-4 flex min-h-10 items-center gap-2 px-3 text-[10px] text-accent hover:bg-accent/10"
      >
        <RefreshCw size={12} />
        重新读取
      </button>
    </div>
  );
}

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).format(new Date(value));
}
