import {
  Crosshair,
  Hand,
  KeyRound,
  LoaderCircle,
  MousePointerClick,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import { useMemo, useState } from 'react';
import {
  useAuthorizeHumanAssist,
  useChallengeAutomation,
  useChallengePreview,
  useSessionChallenges,
  useSubmitChallengeOtp,
  useUpdateChallengeAutomationPolicy,
} from '@/features/sessions/api/sessionQueries';
import { isSessionApiError } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type { ChallengeEventView } from '@/types/session';

const ACTIVE = new Set([
  'SUSPECTED',
  'CONFIRMED',
  'AUTHORIZED',
  'EXECUTING',
  'TAKEOVER_REQUIRED',
]);
const AUTOMATABLE = new Set([
  'SINGLE_CLICK',
  'IMAGE_SELECTION',
  'PUZZLE',
  'MULTI_ROUND',
]);

export function ChallengeAssistCard({
  sessionId,
  canOperate,
  requestingTakeover,
  takeoverError,
  onRequestTakeover,
}: {
  sessionId: string;
  canOperate: boolean;
  requestingTakeover: boolean;
  takeoverError?: unknown;
  onRequestTakeover: () => Promise<unknown>;
}) {
  const challenges = useSessionChallenges(sessionId);
  const automation = useChallengeAutomation(sessionId);
  const updateAutomation = useUpdateChallengeAutomationPolicy(sessionId);
  const [selectedId, setSelectedId] = useState<string>();
  const active = useMemo(
    () => challenges.data?.items.find((item) => ACTIVE.has(item.status)),
    [challenges.data?.items]
  );
  const previewEventId =
    selectedId === active?.challengeEventId && active?.oneClickEligible
      ? selectedId
      : undefined;
  const preview = useChallengePreview(sessionId, previewEventId);
  const authorization = useAuthorizeHumanAssist(sessionId);
  const submitOtp = useSubmitChallengeOtp(sessionId);
  const [otp, setOtp] = useState('');
  const automationPolicy = automation.policy.data;

  if (challenges.isLoading) {
    return (
      <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
        <div className="flex items-center gap-2 text-[11px] text-text-muted">
          <LoaderCircle size={13} className="animate-spin" />
          正在读取 Challenge Detection 事件
        </div>
      </section>
    );
  }

  if (challenges.error) {
    return (
      <section className="rounded-[10px] border border-danger/25 bg-surface-1 p-5">
        <p className="text-[11px] text-danger">Challenge 状态读取失败。</p>
        <button
          type="button"
          onClick={() => challenges.refetch()}
          className="mt-3 inline-flex items-center gap-1.5 text-[10px] text-accent"
        >
          <RefreshCw size={11} /> 重试
        </button>
      </section>
    );
  }

  return (
    <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
      <header className="flex items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <Crosshair size={14} className="text-accent" />
            <h2 className="text-[13px] font-semibold text-text-primary">
              Challenge / Human Assist
            </h2>
          </div>
          <p className="mt-1.5 text-[10px] leading-5 text-text-muted">
            低风险视觉挑战优先由脱敏截图
            OCR/视觉识别执行点击、连续点击或滑动；预算耗尽后通知操作员。
          </p>
        </div>
        <span className="border border-border-default bg-surface-2 px-2 py-1 font-mono text-[9px] text-text-muted">
          {automationPolicy?.controlMode ?? 'SAFE'}
        </span>
      </header>

      {automationPolicy && (
        <div className="mt-4 flex flex-wrap items-center justify-between gap-3 border border-border-subtle bg-canvas/35 p-3">
          <div>
            <p className="text-[10px] font-medium text-text-secondary">
              Agent 控制模式
            </p>
            <p className="mt-1 text-[9px] text-text-muted">
              自动模式持续自行工作，只有确认需要人工时才通知。届时可发送 OTP 让
              Agent 填写，也可自行进入协作界面输入。
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-label="Agent 控制模式"
              disabled={!canOperate || updateAutomation.isPending}
              value={automationPolicy.controlMode}
              onChange={(event) =>
                updateAutomation.mutate({
                  controlMode: event.target.value as 'SAFE' | 'AUTONOMOUS',
                  sensitiveInputMaximumAttempts:
                    automationPolicy.sensitiveInputMaximumAttempts,
                  enabled: automationPolicy.enabled,
                  maximumAttempts: automationPolicy.maximumAttempts,
                  minimumConfidence: automationPolicy.minimumConfidence,
                  allowMultiClick: automationPolicy.allowMultiClick,
                  allowSlide: automationPolicy.allowSlide,
                })
              }
              className="h-8 rounded-[6px] border border-border-default bg-surface-1 px-2 font-mono text-[10px] text-text-primary disabled:opacity-40"
            >
              <option value="SAFE">安全模式</option>
              <option value="AUTONOMOUS">自动模式</option>
            </select>
            <select
              aria-label="敏感输入自动重试次数"
              disabled={!canOperate || updateAutomation.isPending}
              value={automationPolicy.sensitiveInputMaximumAttempts}
              onChange={(event) =>
                updateAutomation.mutate({
                  controlMode: automationPolicy.controlMode,
                  sensitiveInputMaximumAttempts: Number(event.target.value),
                  enabled: automationPolicy.enabled,
                  maximumAttempts: automationPolicy.maximumAttempts,
                  minimumConfidence: automationPolicy.minimumConfidence,
                  allowMultiClick: automationPolicy.allowMultiClick,
                  allowSlide: automationPolicy.allowSlide,
                })
              }
              className="h-8 rounded-[6px] border border-border-default bg-surface-1 px-2 font-mono text-[10px] text-text-primary disabled:opacity-40"
            >
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((attempts) => (
                <option key={attempts} value={attempts}>
                  输入重试 {attempts} 次
                </option>
              ))}
            </select>
            <select
              aria-label="Challenge 自动尝试次数"
              disabled={!canOperate || updateAutomation.isPending}
              value={
                automationPolicy.enabled ? automationPolicy.maximumAttempts : 0
              }
              onChange={(event) => {
                const maximumAttempts = Number(event.target.value);
                updateAutomation.mutate({
                  controlMode: automationPolicy.controlMode,
                  sensitiveInputMaximumAttempts:
                    automationPolicy.sensitiveInputMaximumAttempts,
                  enabled: maximumAttempts > 0,
                  maximumAttempts,
                  minimumConfidence: automationPolicy.minimumConfidence,
                  allowMultiClick: automationPolicy.allowMultiClick,
                  allowSlide: automationPolicy.allowSlide,
                });
              }}
              className="h-8 rounded-[6px] border border-border-default bg-surface-1 px-2 font-mono text-[10px] text-text-primary disabled:opacity-40"
            >
              <option value={0}>关闭</option>
              {[1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((attempts) => (
                <option key={attempts} value={attempts}>
                  {attempts} 次
                </option>
              ))}
            </select>
          </div>
        </div>
      )}

      {!active ? (
        <div className="mt-4 border border-border-subtle bg-canvas/35 p-4">
          <div className="flex items-center gap-2 text-[11px] text-text-secondary">
            <ShieldCheck size={13} className="text-success" />
            当前没有待处理挑战
          </div>
          <p className="mt-2 text-[10px] leading-5 text-text-muted">
            最近事件仍保留在 PostgreSQL 时间线中；前端不会模拟 Challenge。
          </p>
        </div>
      ) : (
        <ChallengeSummary challenge={active} />
      )}

      {active &&
        AUTOMATABLE.has(active.suspectedType) &&
        automation.policy.data?.enabled &&
        automation.run.data &&
        !['COMPLETED', 'EXHAUSTED', 'ESCALATED', 'FAILED'].includes(
          automation.run.data.state
        ) && (
          <div className="mt-4 flex items-center gap-2 border border-accent/25 bg-accent/5 p-3 text-[10px] text-accent">
            <LoaderCircle size={12} className="animate-spin" />
            视觉自动化 {automation.run.data.state} · 第{' '}
            {automation.run.data.attemptCount}/
            {automation.run.data.maximumAttempts} 次
          </div>
        )}

      {active?.status === 'TAKEOVER_REQUIRED' &&
        (!AUTOMATABLE.has(active.suspectedType) ||
          !automation.policy.data?.enabled ||
          ['EXHAUSTED', 'ESCALATED', 'FAILED'].includes(
            automation.run.data?.state ?? ''
          )) && (
          <div className="mt-4 border border-warning/30 bg-warning/5 p-4">
            <div className="flex items-center gap-2 text-[11px] font-medium text-warning">
              <TriangleAlert size={13} /> 已通知操作员
            </div>
            <p className="mt-2 text-[10px] leading-5 text-text-muted">
              {AUTOMATABLE.has(active.suspectedType)
                ? `自动视觉预算已用尽或无法可靠定位（${automation.run.data?.lastErrorCode ?? '等待人工'}）。`
                : automation.policy.data?.controlMode === 'AUTONOMOUS'
                  ? 'Agent 已确认当前步骤需要人工信息；任务和浏览器现场保持不变，不会强制接管。'
                  : '安全模式保留 OTP、设备、支付与账号安全的显式人工门禁。'}
            </p>
            {active.suspectedType === 'OTP' &&
              automation.policy.data?.controlMode === 'AUTONOMOUS' &&
              active.targetRef && (
                <form
                  className="mt-3 flex flex-wrap items-center gap-2"
                  onSubmit={(event) => {
                    event.preventDefault();
                    if (!otp.trim()) return;
                    submitOtp.mutate(
                      { eventId: active.challengeEventId, value: otp.trim() },
                      { onSuccess: () => setOtp('') }
                    );
                  }}
                >
                  <input
                    aria-label="提供给 Agent 的验证码"
                    type="password"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    value={otp}
                    onChange={(event) => setOtp(event.target.value)}
                    disabled={!canOperate || submitOtp.isPending}
                    placeholder="输入验证码，由 Agent 填写"
                    className="h-8 min-w-[190px] flex-1 rounded-[6px] border border-border-default bg-surface-1 px-3 text-[10px] text-text-primary outline-none focus:border-accent disabled:opacity-40"
                  />
                  <button
                    type="submit"
                    disabled={!canOperate || !otp.trim() || submitOtp.isPending}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[6px] bg-warning px-3 text-[10px] font-medium text-canvas disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {submitOtp.isPending ? (
                      <LoaderCircle size={11} className="animate-spin" />
                    ) : (
                      <KeyRound size={11} />
                    )}
                    发送给 Agent 填写
                  </button>
                </form>
              )}
            <button
              type="button"
              disabled={!canOperate || requestingTakeover}
              onClick={() => void onRequestTakeover()}
              className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[6px] border border-warning/35 px-3 text-[10px] font-medium text-warning hover:bg-warning/10 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {requestingTakeover ? (
                <LoaderCircle size={11} className="animate-spin" />
              ) : (
                <Hand size={11} />
              )}
              可选：进入人工协作
            </button>
          </div>
        )}

      {active?.status === 'CONFIRMED' &&
        active.oneClickEligible &&
        (!automation.policy.data?.enabled ||
          ['EXHAUSTED', 'ESCALATED', 'FAILED'].includes(
            automation.run.data?.state ?? ''
          )) && (
          <div className="mt-4">
            {!previewEventId ? (
              <button
                type="button"
                disabled={!canOperate}
                onClick={() => setSelectedId(active.challengeEventId)}
                className="inline-flex h-8 items-center gap-1.5 rounded-[6px] border border-accent/35 px-3 text-[10px] font-medium text-accent hover:bg-accent-soft disabled:cursor-not-allowed disabled:opacity-40"
              >
                <Crosshair size={11} /> 预览当前目标
              </button>
            ) : preview.isLoading ? (
              <div className="flex items-center gap-2 text-[10px] text-text-muted">
                <LoaderCircle size={12} className="animate-spin" />{' '}
                正在重新校验当前目标
              </div>
            ) : preview.data ? (
              <div className="border border-accent/25 bg-accent/5 p-4">
                <div className="flex items-start justify-between gap-3">
                  <div>
                    <p className="text-[11px] font-medium text-text-primary">
                      用户确认预览
                    </p>
                    <p className="mt-1.5 text-[10px] leading-5 text-text-muted">
                      {preview.data.challenge.targetSummary}
                    </p>
                  </div>
                  <span
                    className={cn(
                      'font-mono text-[9px]',
                      preview.data.canAuthorize
                        ? 'text-success'
                        : 'text-warning'
                    )}
                  >
                    {preview.data.canAuthorize ? 'FRESH' : 'BLOCKED'}
                  </span>
                </div>
                {preview.data.highlight && (
                  <div className="mt-3 grid grid-cols-4 gap-px border border-border-subtle bg-border-subtle font-mono text-[9px]">
                    <Metric label="X" value={preview.data.highlight.x} />
                    <Metric label="Y" value={preview.data.highlight.y} />
                    <Metric label="W" value={preview.data.highlight.width} />
                    <Metric label="H" value={preview.data.highlight.height} />
                  </div>
                )}
                <p className="mt-3 text-[10px] leading-5 text-warning">
                  只执行一次点击；失败不会自动重试，新的点击必须重新授权。
                </p>
                {preview.data.canAuthorize ? (
                  <button
                    type="button"
                    disabled={authorization.isPending}
                    onClick={() =>
                      authorization.mutate({
                        eventId: active.challengeEventId,
                        request: {
                          previewHash: preview.data.previewHash,
                          expectedStateVersion:
                            preview.data.challenge.stateVersion,
                          expectedTargetRevision:
                            preview.data.challenge.targetRevision,
                        },
                      })
                    }
                    className="mt-3 inline-flex h-8 items-center gap-1.5 rounded-[6px] bg-accent px-3 text-[10px] font-medium text-canvas hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {authorization.isPending ? (
                      <LoaderCircle size={11} className="animate-spin" />
                    ) : (
                      <MousePointerClick size={11} />
                    )}
                    确认执行一次点击
                  </button>
                ) : (
                  <p className="mt-3 font-mono text-[9px] text-warning">
                    {preview.data.blockingReason}
                  </p>
                )}
              </div>
            ) : null}
          </div>
        )}

      {Boolean(
        preview.error || authorization.error || submitOtp.error || takeoverError
      ) && (
        <p className="mt-3 border border-danger/25 bg-danger/5 p-3 text-[10px] leading-5 text-danger">
          {errorMessage(
            preview.error ??
              authorization.error ??
              submitOtp.error ??
              takeoverError
          )}
        </p>
      )}

      {(active?.status === 'AUTHORIZED' || active?.status === 'EXECUTING') && (
        <div className="mt-4 flex items-center gap-2 border border-accent/25 bg-accent/5 p-3 text-[10px] text-accent">
          <LoaderCircle size={12} className="animate-spin" />
          一次性 HumanAssist Operation 执行中，等待真实 Node 结果
        </div>
      )}

      {challenges.data && challenges.data.items.length > 1 && (
        <details className="mt-4 border-t border-border-subtle pt-3">
          <summary className="cursor-pointer text-[10px] text-text-muted">
            历史事件 {challenges.data.items.length} 条
          </summary>
          <div className="mt-3 space-y-2">
            {challenges.data.items.slice(0, 10).map((item) => (
              <div
                key={item.challengeEventId}
                className="flex items-center justify-between gap-3 text-[9px]"
              >
                <span className="truncate font-mono text-text-muted">
                  {item.challengeEventId}
                </span>
                <span className="shrink-0 text-text-secondary">
                  {item.status}
                </span>
              </div>
            ))}
          </div>
        </details>
      )}
    </section>
  );
}

function ChallengeSummary({ challenge }: { challenge: ChallengeEventView }) {
  return (
    <div className="mt-4 border border-border-subtle bg-canvas/35 p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-[11px] font-medium text-text-primary">
          {challenge.targetSummary}
        </p>
        <span className="font-mono text-[9px] text-warning">
          {challenge.status}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 text-[9px] md:grid-cols-4">
        <LabelValue label="类型" value={challenge.suspectedType} />
        <LabelValue
          label="置信度"
          value={`${Math.round(challenge.confidence * 100)}%`}
        />
        <LabelValue label="State" value={`v${challenge.stateVersion}`} />
        <LabelValue label="Target" value={`r${challenge.targetRevision}`} />
      </div>
      <p className="mt-3 font-mono text-[9px] text-text-muted">
        AUTH DEADLINE {formatTime(challenge.authorizationDeadline)}
      </p>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: number }) {
  return (
    <div className="bg-surface-1 px-2 py-2">
      <span className="text-text-muted">{label}</span>{' '}
      <span className="text-text-primary">{Math.round(value * 100) / 100}</span>
    </div>
  );
}

function LabelValue({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-text-muted">{label}</p>
      <p className="mt-1 truncate font-mono text-text-secondary">{value}</p>
    </div>
  );
}

function formatTime(value: string) {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleTimeString('zh-CN');
}

function errorMessage(error: unknown) {
  if (isSessionApiError(error)) {
    const reason = error.body.details?.reason;
    return `${error.body.message}${reason ? ` · ${String(reason)}` : ''}${error.body.requestId ? ` · ${error.body.requestId}` : ''}`;
  }
  return error instanceof Error
    ? error.message
    : '操作失败，请刷新当前状态后重试。';
}
