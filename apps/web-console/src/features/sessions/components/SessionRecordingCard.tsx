import { CircleAlert, Film, LockKeyhole, ShieldCheck } from 'lucide-react';
import type { SessionRecordingView } from '@/types/session';

function formatTime(value: string) {
  return new Intl.DateTimeFormat('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(value));
}

export function SessionRecordingCard({
  items,
  loading,
  error,
  onRetry,
}: {
  items: SessionRecordingView[];
  loading: boolean;
  error: unknown;
  onRetry: () => void;
}) {
  return (
    <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
      <header className="flex flex-wrap items-start justify-between gap-3 border-b border-border-subtle px-4 py-3">
        <div>
          <div className="flex items-center gap-2">
            <Film size={14} className="text-accent" />
            <h2 className="text-xs font-semibold text-text-primary">
              Session 录制清单
            </h2>
          </div>
          <p className="mt-1 text-[10px] text-text-muted">
            Node 权威 Manifest · PostgreSQL 投影 · 保留与 Legal Hold
          </p>
        </div>
        <span className="font-mono text-[10px] text-text-muted">
          {items.length} RECORDINGS
        </span>
      </header>

      {loading ? (
        <p className="px-4 py-5 text-[11px] text-text-muted">
          正在读取真实录制清单…
        </p>
      ) : error ? (
        <div className="flex items-center justify-between gap-3 px-4 py-4 text-[11px] text-danger">
          <span className="inline-flex items-center gap-2">
            <CircleAlert size={13} />
            录制清单读取失败
          </span>
          <button
            type="button"
            onClick={onRetry}
            className="text-accent hover:underline"
          >
            重试
          </button>
        </div>
      ) : items.length === 0 ? (
        <p className="px-4 py-5 text-[11px] text-text-muted">
          尚无已完成并入账的 Session 录制。
        </p>
      ) : (
        <div className="divide-y divide-border-subtle">
          {items.map((item) => (
            <article
              key={item.recordingId}
              className="grid gap-3 px-4 py-3 sm:grid-cols-[minmax(0,1fr)_auto]"
            >
              <div className="min-w-0">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="truncate font-mono text-[10px] text-text-primary">
                    {item.recordingId}
                  </span>
                  <span className="inline-flex items-center gap-1 rounded-full border border-success/30 px-2 py-0.5 text-[9px] text-success">
                    <ShieldCheck size={10} /> 已提交
                  </span>
                  {item.legalHold && (
                    <span className="inline-flex items-center gap-1 rounded-full border border-warning/30 px-2 py-0.5 text-[9px] text-warning">
                      <LockKeyhole size={10} /> Legal Hold
                    </span>
                  )}
                </div>
                <p className="mt-1 font-mono text-[9px] text-text-muted">
                  SHA-256 {item.manifestSha256.slice(0, 16)}… · policy v
                  {item.redactionPolicyVersion}
                </p>
              </div>
              <dl className="grid grid-cols-2 gap-x-5 gap-y-1 text-[10px] sm:text-right">
                <div>
                  <dt className="text-text-muted">帧 / 丢帧</dt>
                  <dd className="font-mono text-text-primary">
                    {item.frameCount} / {item.droppedFrames}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-muted">分段</dt>
                  <dd className="font-mono text-text-primary">
                    {item.segmentCount}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-muted">结束</dt>
                  <dd className="text-text-primary">
                    {formatTime(item.endedAt)}
                  </dd>
                </div>
                <div>
                  <dt className="text-text-muted">保留至</dt>
                  <dd className="text-text-primary">
                    {formatTime(item.retentionUntil)}
                  </dd>
                </div>
              </dl>
            </article>
          ))}
        </div>
      )}
    </section>
  );
}
