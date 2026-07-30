import {
  Camera,
  CircleAlert,
  CircleCheck,
  ExternalLink,
  LoaderCircle,
  LockKeyhole,
  ShieldCheck,
} from 'lucide-react';
import { useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { usePlatform } from '@/platform/PlatformProvider';
import type {
  EvidenceAccessGrantView,
  EvidenceCaptureView,
  EvidencePurpose,
  RedeemEvidenceAccessResponse,
  SessionEvidenceView,
} from '@/types/session';

const kindLabels: Record<SessionEvidenceView['evidenceKind'], string> = {
  AGENT_ACTION_SUCCESS: 'Agent 动作成功',
  AGENT_ACTION_FAILURE: 'Agent 动作失败',
  AGENT_NAVIGATION_SUCCESS: 'Agent 导航成功',
  AGENT_NAVIGATION_FAILURE: 'Agent 导航失败',
  OBSERVER_MANUAL: 'Observer 手动截图',
};

const purposeLabels: Record<EvidencePurpose, string> = {
  INCIDENT_RESPONSE: '事件响应',
  CHANGE_VALIDATION: '变更验证',
  SUPPORT_DIAGNOSTICS: '支持诊断',
  COMPLIANCE_AUDIT: '合规审计',
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

function requestId(error: unknown) {
  return isSessionApiError(error) ? error.body.requestId : undefined;
}

export function SessionEvidenceCard({
  items,
  capture,
  loading,
  error,
  canAdminister,
  running,
  humanTakeover,
  capturing,
  captureError,
  granting,
  grantError,
  redeeming,
  redeemError,
  onRetry,
  onCapture,
  onCreateAccessGrant,
  onRedeem,
}: {
  items: SessionEvidenceView[];
  capture?: EvidenceCaptureView;
  loading: boolean;
  error: unknown;
  canAdminister: boolean;
  running: boolean;
  humanTakeover: boolean;
  capturing: boolean;
  captureError: unknown;
  granting: boolean;
  grantError: unknown;
  redeeming: boolean;
  redeemError: unknown;
  onRetry: () => void;
  onCapture: (purpose: EvidencePurpose) => Promise<EvidenceCaptureView>;
  onCreateAccessGrant: (
    evidenceId: string,
    purpose: EvidencePurpose
  ) => Promise<EvidenceAccessGrantView>;
  onRedeem: (grantId: string) => Promise<RedeemEvidenceAccessResponse>;
}) {
  const platform = usePlatform();
  const [purpose, setPurpose] = useState<EvidencePurpose>(
    'SUPPORT_DIAGNOSTICS'
  );
  const [grant, setGrant] = useState<EvidenceAccessGrantView>();
  const [access, setAccess] = useState<RedeemEvidenceAccessResponse>();
  const [openingError, setOpeningError] = useState<string>();

  const captureDisabled =
    !canAdminister || !running || humanTakeover || capturing;

  const createGrant = async (evidenceId: string) => {
    setOpeningError(undefined);
    setGrant(undefined);
    setAccess(undefined);
    try {
      const created = await onCreateAccessGrant(evidenceId, purpose);
      setGrant(created);
    } catch {
      // The mutation exposes the structured API error below.
    }
  };

  const captureNow = async () => {
    try {
      await onCapture(purpose);
    } catch {
      // The mutation exposes the structured API error below.
    }
  };

  const redeem = async () => {
    if (!grant) return;
    setOpeningError(undefined);
    try {
      const redeemedAccess = await onRedeem(grant.grantId);
      setAccess(redeemedAccess);
      setGrant(undefined);
    } catch {
      // The mutation exposes the structured API error below.
    }
  };

  const openAccess = async () => {
    if (!access) return;
    setOpeningError(undefined);
    try {
      await platform.openExternal(access.downloadUrl);
    } catch (accessError) {
      setOpeningError(
        accessError instanceof Error
          ? accessError.message
          : '一次性访问链接打开失败'
      );
    }
  };

  return (
    <section className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
      <header className="border-b border-border-subtle px-4 py-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <Camera size={14} className="text-accent" />
              <h2 className="text-xs font-semibold text-text-primary">
                Session 截图证据
              </h2>
            </div>
            <p className="mt-1 text-[10px] text-text-muted">
              真实 CDP 截图 · 对象存储隔离 · purpose-bound 单次访问
            </p>
          </div>
          <span className="font-mono text-[10px] text-text-muted">
            {items.length} EVENTS
          </span>
        </div>

        {canAdminister && (
          <div className="mt-3 grid gap-2 border-t border-border-subtle pt-3 sm:grid-cols-[minmax(0,1fr)_auto]">
            <label className="grid gap-1">
              <span className="font-mono text-[9px] uppercase tracking-[0.12em] text-text-muted">
                Evidence purpose
              </span>
              <select
                value={purpose}
                onChange={(event) =>
                  setPurpose(event.target.value as EvidencePurpose)
                }
                className="h-8 rounded-[6px] border border-border-subtle bg-surface-2 px-2 text-[11px] text-text-primary outline-none focus:border-accent"
              >
                {Object.entries(purposeLabels).map(([value, label]) => (
                  <option key={value} value={value}>
                    {label}
                  </option>
                ))}
              </select>
            </label>
            <button
              type="button"
              disabled={captureDisabled}
              onClick={() => void captureNow()}
              className="self-end inline-flex h-8 items-center justify-center gap-1.5 rounded-[6px] bg-accent px-3 text-[10px] font-semibold text-surface-0 transition hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-40"
            >
              {capturing ? (
                <LoaderCircle size={12} className="animate-spin" />
              ) : (
                <Camera size={12} />
              )}
              手动留证
            </button>
          </div>
        )}

        {humanTakeover && canAdminister && (
          <p className="mt-2 text-[10px] text-warning">
            HumanTakeover 进行中：为保护人工操作隐私，手动截图已禁用。
          </p>
        )}
        {!running && canAdminister && (
          <p className="mt-2 text-[10px] text-text-muted">
            仅运行中或降级运行的 Session 可以手动留证。
          </p>
        )}
        {capture && (
          <div className="mt-2 flex flex-wrap items-center gap-x-2 gap-y-1 rounded-[6px] border border-border-subtle bg-surface-2 px-2.5 py-2">
            {capture.state === 'EXECUTING' ? (
              <LoaderCircle size={12} className="animate-spin text-accent" />
            ) : capture.state === 'COMMITTED' ? (
              <CircleCheck size={12} className="text-success" />
            ) : (
              <CircleAlert size={12} className="text-warning" />
            )}
            <span className="text-[10px] font-medium text-text-primary">
              手动留证：{capture.state}
            </span>
            <span className="font-mono text-[9px] text-text-muted">
              {capture.captureId}
            </span>
            {capture.errorCode && (
              <span className="text-[9px] text-warning">
                {capture.errorCode}
              </span>
            )}
          </div>
        )}
        {Boolean(captureError) && (
          <InlineError label="手动留证请求失败" error={captureError} />
        )}
      </header>

      {grant && (
        <div className="border-b border-accent/25 bg-accent/[0.06] px-4 py-3">
          <div className="flex items-start gap-2">
            <ShieldCheck size={14} className="mt-0.5 shrink-0 text-accent" />
            <div className="min-w-0 flex-1">
              <p className="text-[11px] font-medium text-text-primary">
                一次性访问授权已签发
              </p>
              <p className="mt-1 text-[9px] text-text-muted">
                {purposeLabels[grant.purpose]} · 授权将在{' '}
                {formatTimestamp(grant.expiresAt)}{' '}
                失效。点击后仅兑换一次，链接有效 60 秒。
              </p>
            </div>
            <button
              type="button"
              disabled={redeeming}
              onClick={() => void redeem()}
              className="inline-flex h-7 shrink-0 items-center gap-1 rounded-[6px] border border-accent/40 px-2.5 text-[10px] font-medium text-accent hover:bg-accent/10 disabled:opacity-40"
            >
              {redeeming ? (
                <LoaderCircle size={11} className="animate-spin" />
              ) : (
                <ExternalLink size={11} />
              )}
              打开证据
            </button>
          </div>
          {Boolean(redeemError) && (
            <InlineError label="访问授权兑换失败" error={redeemError} />
          )}
        </div>
      )}

      {access && (
        <div className="border-b border-success/25 bg-success/[0.05] px-4 py-3">
          <div className="flex items-start gap-2">
            <CircleCheck size={14} className="mt-0.5 shrink-0 text-success" />
            <div className="min-w-0 flex-1">
              <p className="text-[11px] font-medium text-text-primary">
                临时证据链接已就绪
              </p>
              <p className="mt-1 text-[9px] text-text-muted">
                链接将在 {formatTimestamp(access.expiresAt)}{' '}
                失效，不会保存到本地。
              </p>
            </div>
            <button
              type="button"
              onClick={() => void openAccess()}
              className="inline-flex h-7 shrink-0 items-center gap-1 rounded-[6px] border border-success/40 px-2.5 text-[10px] font-medium text-success hover:bg-success/10"
            >
              <ExternalLink size={11} />
              打开下载
            </button>
          </div>
          {openingError && (
            <InlineError label={openingError} error={undefined} />
          )}
        </div>
      )}

      {Boolean(grantError) && (
        <div className="border-b border-border-subtle px-4 pb-3">
          <InlineError label="访问授权创建失败" error={grantError} />
        </div>
      )}

      {loading ? (
        <p className="px-4 py-5 text-xs text-text-muted">正在读取证据索引…</p>
      ) : error ? (
        <div className="flex items-center justify-between gap-3 px-4 py-4">
          <InlineError label="证据索引读取失败" error={error} />
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
            管理员可按用途手动留证；Agent 导航或交互也会按策略提交证据。
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
                {item.result === 'COMMITTED' && (
                  <p
                    className={`mt-1 inline-flex items-center gap-1 text-[9px] ${
                      item.redactionState === 'LEGACY_UNVERIFIED'
                        ? 'text-warning'
                        : 'text-success'
                    }`}
                  >
                    <ShieldCheck size={10} aria-hidden="true" />
                    {item.redactionState === 'MASKED'
                      ? `已遮罩 ${item.redactedRegionCount} 个敏感区域`
                      : item.redactionState === 'NOT_REQUIRED'
                        ? '未检测到敏感区域'
                        : '旧版证据：遮罩状态未验证'}
                  </p>
                )}
                {item.result === 'FAILED' && (
                  <p className="mt-1 text-[10px] text-warning">
                    留证失败：
                    {item.redactionState === 'FAILED_CLOSED'
                      ? '敏感区域保护失败，未提交截图'
                      : (item.errorCode ?? 'EVIDENCE_CAPTURE_FAILED')}
                  </p>
                )}
              </div>
              <div className="flex min-w-[84px] flex-col items-end">
                <p className="font-mono text-[9px] text-text-secondary">
                  {formatTimestamp(item.capturedAt)}
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  {item.result === 'COMMITTED'
                    ? formatBytes(item.contentBytes)
                    : '未生成对象'}
                </p>
                {canAdminister && item.result === 'COMMITTED' && (
                  <button
                    type="button"
                    disabled={granting || redeeming}
                    onClick={() => void createGrant(item.evidenceId)}
                    className="mt-2 inline-flex h-6 items-center gap-1 rounded-[5px] border border-border-subtle px-2 text-[9px] text-text-secondary hover:border-accent/40 hover:text-accent disabled:opacity-40"
                  >
                    {granting ? (
                      <LoaderCircle size={10} className="animate-spin" />
                    ) : (
                      <LockKeyhole size={10} />
                    )}
                    申请查看
                  </button>
                )}
              </div>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}

function InlineError({ label, error }: { label: string; error: unknown }) {
  const id = requestId(error);
  return (
    <p className="mt-2 text-[10px] text-danger">
      {label}
      {id ? (
        <span className="ml-1 font-mono text-[9px] text-text-muted">
          Request ID: {id}
        </span>
      ) : null}
    </p>
  );
}
