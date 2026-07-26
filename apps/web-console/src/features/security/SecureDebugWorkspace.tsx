import { useMemo, useState } from 'react';
import {
  Bug,
  Eye,
  LockKeyhole,
  RefreshCw,
  ShieldCheck,
  Square,
} from 'lucide-react';
import { currentActorId } from '@/api/session';
import type {
  BreakGlassRequestView,
  SecureDebugSnapshotView,
} from '@/types/platform';
import { ErrorState, LoadingRows } from '@/components/feedback/AsyncStates';
import { cn } from '@/shared/lib/utils';
import {
  useEndSecureDebugSession,
  useReadSecureDebugSnapshot,
  useSecureDebugSessions,
  useStartSecureDebugSession,
} from './platformQueries';

export function SecureDebugWorkspace({
  grants,
}: {
  grants: BreakGlassRequestView[];
}) {
  const sessions = useSecureDebugSessions();
  const start = useStartSecureDebugSession();
  const read = useReadSecureDebugSnapshot();
  const end = useEndSecureDebugSession();
  const [snapshot, setSnapshot] = useState<SecureDebugSnapshotView | null>(
    null
  );
  const eligibleGrants = useMemo(() => {
    const consumed = new Set(
      (sessions.data?.items ?? []).map((item) => item.breakGlassRequestId)
    );
    return grants.filter(
      (grant) =>
        grant.state === 'ACTIVE' &&
        grant.resourceType === 'SESSION' &&
        grant.requestedScope === 'SECURE_DEBUG' &&
        grant.requestedBy === currentActorId() &&
        !consumed.has(grant.requestId)
    );
  }, [grants, sessions.data?.items]);
  const error = start.error ?? read.error ?? end.error;

  async function readSnapshot(debugSessionId: string) {
    const next = await read.mutateAsync(debugSessionId);
    setSnapshot(next);
  }

  async function endSession(debugSessionId: string) {
    await end.mutateAsync(debugSessionId);
    setSnapshot((current) =>
      current?.debugSessionId === debugSessionId ? null : current
    );
  }

  return (
    <section className="mt-4 border border-border-subtle bg-surface-1">
      <header className="flex flex-col gap-3 border-b border-border-subtle bg-surface-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <Bug size={15} className="text-accent" />
          <div>
            <h2 className="text-[12px] font-semibold text-text-primary">
              Secure Debug 数据面
            </h2>
            <p className="mt-0.5 text-[10px] text-text-muted">
              一次性授权 · 最长 15 分钟 · 最小字段投影 · 每次读取形成证据
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => sessions.refetch()}
          className="inline-flex h-8 items-center justify-center gap-1.5 border border-border-default px-3 text-[11px] text-text-secondary hover:text-text-primary"
        >
          <RefreshCw size={12} />
          刷新会话
        </button>
      </header>

      <div className="border-b border-border-subtle bg-accent/[0.025] px-4 py-3">
        <div className="flex items-start gap-2 text-[10px] leading-5 text-text-muted">
          <ShieldCheck size={13} className="mt-0.5 shrink-0 text-success" />
          <p>
            只返回运行状态、Runtime、版本、URL Origin、State
            Hash/质量与目标数量。 完整
            URL、Query、标题、DOM、目标名称/坐标、截图、Cookie 和 Profile
            内容不会进入该数据面。
          </p>
        </div>
      </div>

      {eligibleGrants.length > 0 && (
        <div className="border-b border-border-subtle px-4 py-3">
          <p className="mb-2 text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
            可消费授权
          </p>
          <div className="flex flex-wrap gap-2">
            {eligibleGrants.map((grant) => (
              <button
                key={grant.requestId}
                type="button"
                disabled={start.isPending}
                onClick={() => void start.mutateAsync(grant.requestId)}
                className="inline-flex h-8 items-center gap-2 border border-accent/40 px-3 text-[10px] text-accent hover:bg-accent/8 disabled:opacity-50"
              >
                <LockKeyhole size={12} />
                启动 {grant.resourceId}
              </button>
            ))}
          </div>
        </div>
      )}

      {error && (
        <p
          role="alert"
          className="border-b border-danger/30 bg-danger/8 px-4 py-2 text-[11px] text-danger"
        >
          {error.message}
        </p>
      )}

      {sessions.isLoading ? (
        <LoadingRows rows={2} />
      ) : sessions.isError ? (
        <ErrorState
          error={sessions.error}
          onRetry={() => sessions.refetch()}
          title="无法读取 Secure Debug 会话"
        />
      ) : (sessions.data?.items.length ?? 0) === 0 ? (
        <div className="px-4 py-6 text-center">
          <p className="text-[11px] text-text-secondary">尚无调试会话</p>
          <p className="mt-1 text-[10px] text-text-muted">
            创建 SESSION / SECURE_DEBUG 请求并由另一位管理员审批后才能启动。
          </p>
        </div>
      ) : (
        <div className="divide-y divide-border-subtle">
          {sessions.data?.items.map((session) => {
            const ownSession = session.operatorId === currentActorId();
            const active = session.state === 'ACTIVE';
            return (
              <article
                key={session.debugSessionId}
                className="grid gap-3 px-4 py-3 lg:grid-cols-[190px_1fr_190px_auto] lg:items-center"
              >
                <div>
                  <p className="font-mono text-[10px] text-accent">
                    {session.debugSessionId}
                  </p>
                  <p className="mt-1 font-mono text-[9px] text-text-muted">
                    {session.breakGlassRequestId}
                  </p>
                </div>
                <div className="min-w-0">
                  <p className="truncate text-[11px] text-text-primary">
                    {session.resourceType}/{session.resourceId}
                  </p>
                  <p className="mt-1 truncate text-[10px] text-text-muted">
                    Operator {session.operatorId} · 读取 {session.accessCount}{' '}
                    次
                  </p>
                </div>
                <div className="text-[10px] text-text-muted">
                  <p>到期 {new Date(session.expiresAt).toLocaleTimeString()}</p>
                  <p
                    className="mt-1 truncate font-mono text-[9px]"
                    title={session.evidenceHeadHash ?? undefined}
                  >
                    EVIDENCE {session.evidenceHeadHash ?? '尚未形成'}
                  </p>
                </div>
                <div className="flex flex-wrap items-center gap-2 lg:justify-end">
                  <span
                    className={cn(
                      'font-mono text-[10px] font-semibold',
                      active ? 'text-danger' : 'text-text-muted'
                    )}
                  >
                    {session.state}
                  </span>
                  {active && ownSession && (
                    <>
                      <button
                        type="button"
                        disabled={read.isPending}
                        onClick={() =>
                          void readSnapshot(session.debugSessionId)
                        }
                        className="inline-flex h-7 items-center gap-1 border border-accent/40 px-2 text-[10px] text-accent hover:bg-accent/8 disabled:opacity-50"
                      >
                        <Eye size={11} />
                        读取最小快照
                      </button>
                      <button
                        type="button"
                        disabled={end.isPending}
                        onClick={() => void endSession(session.debugSessionId)}
                        className="inline-flex h-7 items-center gap-1 border border-danger/40 px-2 text-[10px] text-danger hover:bg-danger/8 disabled:opacity-50"
                      >
                        <Square size={10} />
                        结束
                      </button>
                    </>
                  )}
                  {active && !ownSession && (
                    <span className="text-[9px] text-text-muted">
                      仅原申请人可读取
                    </span>
                  )}
                </div>
              </article>
            );
          })}
        </div>
      )}

      {snapshot && (
        <div
          data-testid="secure-debug-snapshot"
          className="border-t border-accent/30 bg-accent/[0.035] p-4"
        >
          <div className="mb-3 flex items-center justify-between gap-3">
            <div>
              <p className="text-[11px] font-semibold text-text-primary">
                SENSITIVE_MINIMIZED
              </p>
              <p className="mt-1 font-mono text-[9px] text-text-muted">
                {new Date(snapshot.capturedAt).toLocaleString()}
              </p>
            </div>
            <span className="border border-success/30 bg-success/8 px-2 py-1 text-[9px] font-semibold text-success">
              ACCESS #{snapshot.accessCount} RECORDED
            </span>
          </div>
          <dl className="grid gap-px bg-border-subtle sm:grid-cols-2 xl:grid-cols-4">
            <DebugFact label="Session" value={snapshot.sessionId} />
            <DebugFact
              label="运行状态"
              value={`${snapshot.sessionState} / ${snapshot.stateQuality}`}
            />
            <DebugFact
              label="Runtime"
              value={snapshot.runtimeBuildId ?? '未绑定'}
            />
            <DebugFact
              label="URL Origin"
              value={snapshot.urlOrigin ?? '状态不可用'}
            />
            <DebugFact
              label="Context"
              value={`E${snapshot.contextEpoch} · G${snapshot.browserGeneration} · N${snapshot.networkRevision}`}
            />
            <DebugFact
              label="State"
              value={`v${snapshot.stateVersion} · target r${snapshot.targetRevision}`}
            />
            <DebugFact
              label="目标计数"
              value={`${snapshot.interactiveTargetCount} total · ${snapshot.sensitiveTargetCount} sensitive`}
            />
            <DebugFact
              label="State Hash"
              value={snapshot.stateHash ?? '不可用'}
              mono
            />
          </dl>
          <p
            className="mt-3 truncate font-mono text-[9px] text-text-muted"
            title={snapshot.accessEvidenceHash}
          >
            ACCESS EVIDENCE {snapshot.accessEvidenceHash}
          </p>
        </div>
      )}
    </section>
  );
}

function DebugFact({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="min-w-0 bg-surface-1 px-3 py-2.5">
      <dt className="text-[9px] uppercase tracking-[0.1em] text-text-muted">
        {label}
      </dt>
      <dd
        className={cn(
          'mt-1 truncate text-[10px] text-text-primary',
          mono && 'font-mono'
        )}
        title={value}
      >
        {value}
      </dd>
    </div>
  );
}
