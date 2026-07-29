import { Camera, CircleAlert, CircleCheck } from 'lucide-react';
import type { SessionEvidenceView } from '@/types/session';

const kindLabels: Record<SessionEvidenceView['evidenceKind'], string> = {
  AGENT_ACTION_SUCCESS: 'Agent 动作成功',
  AGENT_ACTION_FAILURE: 'Agent 动作失败',
  AGENT_NAVIGATION_SUCCESS: 'Agent 导航成功',
  AGENT_NAVIGATION_FAILURE: 'Agent 导航失败',
};

function formatTimestamp(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}

function formatBytes(value: number) {
  return value > 1024 * 1024
    ? `${(value / 1024 / 1024).toFixed(1)} MiB`
    : `${Math.max(1, Math.round(value / 1024))} KiB`;
}

export function SessionEvidenceCard({
  items,
  loading,
  error,
  onRetry,
}: {
  items: SessionEvidenceView[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  return (
    <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
      <header className="flex items-center justify-between border-b border-border-subtle px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <Camera size={14} className="text-accent" />
            <h2 className="text-xs font-semibold text-text-primary">
              Session 截图证据
            </h2>
          </div>
          <p className="mt-1 text-[10px] text-text-muted">
            真实 CDP 截图元数据 · 原始像素保存在受控对象存储
          </p>
        </div>
        <span className="font-mono text-[10px] text-text-muted">
          {items.length} EVENTS
        </span>
      </header>

      {loading ? (
        <p className="px-4 py-5 text-xs text-text-muted">正在读取证据索引…</p>
      ) : error ? (
        <div className="flex items-center justify-between gap-3 px-4 py-4">
          <p className="text-xs text-danger">证据索引读取失败。</p>
          <button
            type="button"
            onClick={onRetry}
            className="h-7 rounded-[6px] border border-border-subtle px-2.5 text-[10px] text-text-secondary hover:text-text-primary"
          >
            重试
          </button>
        </div>
      ) : items.length === 0 ? (
        <div className="px-4 py-5">
          <p className="text-xs font-medium text-text-primary">暂无截图证据</p>
          <p className="mt-1 text-[10px] text-text-muted">
            Agent 执行导航或交互动作后，Node 才会提交证据事件。
          </p>
        </div>
      ) : (
        <ol className="divide-y divide-border-subtle">
          {items.slice(0, 6).map((item) => (
            <li
              key={item.evidenceId}
              className="grid grid-cols-[18px_minmax(0,1fr)_auto] gap-2 px-4 py-3"
            >
              {item.result === 'COMMITTED' ? (
                <CircleCheck size={14} className="mt-0.5 text-success" />
              ) : (
                <CircleAlert size={14} className="mt-0.5 text-warning" />
              )}
              <div className="min-w-0">
                <p className="truncate text-[11px] font-medium text-text-primary">
                  {kindLabels[item.evidenceKind]}
                  {item.mandatory ? ' · 强制留证' : ''}
                </p>
                <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
                  {item.stepId} · {item.commandId}
                </p>
                {item.result === 'FAILED' && (
                  <p className="mt-1 text-[10px] text-warning">
                    留证失败：{item.errorCode ?? 'EVIDENCE_CAPTURE_FAILED'}
                  </p>
                )}
              </div>
              <div className="text-right">
                <p className="font-mono text-[9px] text-text-secondary">
                  {formatTimestamp(item.capturedAt)}
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  {item.result === 'COMMITTED'
                    ? formatBytes(item.contentBytes)
                    : '未生成对象'}
                </p>
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
