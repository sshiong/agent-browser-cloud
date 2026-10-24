import { useState } from 'react';
import { isSessionApiError } from '@/api/session';
import type {
  RetentionPolicyView,
  RecordingCloudHoldView,
} from '@/types/enterprise';
import { useUpsertRetentionPolicy } from './enterpriseQueries';

function recordingHoldLabel(evidence?: RecordingCloudHoldView): string {
  if (!evidence) return '未取得云端证明';
  switch (evidence.status) {
    case 'VERIFIED':
      if (evidence.desiredMode === 'ON') return '云端保全已验证（托管录制）';
      if (evidence.desiredMode === 'OFF') return '已验证解除（托管录制）';
      return '云端状态不可用';
    case 'UNKNOWN':
      return '云端结果未知';
    case 'PENDING':
      return '等待云端回执';
    case 'LOGICAL_ONLY':
      return '仅逻辑保全';
    case 'NOT_REQUESTED':
      return '尚未请求云端保全';
    case 'NO_RECORDINGS':
      return '尚无可托管录制';
    default:
      return '云端状态不可用';
  }
}

function policyError(error: unknown): string {
  const reason = isSessionApiError(error)
    ? error.body.details?.reason
    : undefined;
  switch (reason) {
    case 'RECORDING_CLOUD_HOLD_NOT_RELEASED':
      return '请先解除期望保全，并等待全部云端解除回执。';
    case 'OBJECT_LOCK_RETENTION_MINIMUM':
      return '保留期低于对象锁允许的最短期限。';
    case 'RECORDING_DELETION_ALREADY_AUTHORIZED':
      return '部分录制已授权删除，无法再承诺保全或延长保留期。';
    default:
      return '保存请求未确认，请刷新状态后重试。';
  }
}

type Policy = Pick<
  RetentionPolicyView,
  | 'retentionDays'
  | 'legalHold'
  | 'residencyRegion'
  | 'cloudHoldRequired'
  | 'recordingCloudHold'
>;

export function RecordingRetentionPolicy({
  policy,
  canManage,
  isNew = false,
}: {
  policy: Policy;
  canManage: boolean;
  isNew?: boolean;
}) {
  const mutation = useUpsertRetentionPolicy();
  const [days, setDays] = useState(String(policy.retentionDays));
  const [region, setRegion] = useState(policy.residencyRegion);
  const [hold, setHold] = useState(policy.legalHold);
  const [cloud, setCloud] = useState(policy.cloudHoldRequired ?? false);
  const evidence = policy.recordingCloudHold;
  const supportsCloud = evidence !== undefined;
  const releasePending = Boolean(
    policy.cloudHoldRequired &&
    evidence &&
    (evidence.pendingRecordings > 0 ||
      evidence.unknownRecordings > 0 ||
      evidence.verifiedOnRecordings > 0)
  );
  const fieldClass =
    'w-full border border-border-subtle bg-surface-2 px-2 py-1.5 text-text-primary';

  return (
    <div className="space-y-3 p-4 text-[12px]">
      <div className="flex flex-wrap items-start justify-between gap-2">
        <div>
          <p className="font-medium text-text-primary">录制保留策略</p>
          <p className="mt-1 text-text-secondary">
            {policy.residencyRegion} · {policy.retentionDays} 天 · 期望保全：
            {policy.legalHold ? '开启' : '解除'}
          </p>
        </div>
        <p
          role="status"
          className={
            evidence?.status === 'UNKNOWN' || evidence?.status === 'PENDING'
              ? 'text-warning'
              : 'text-text-secondary'
          }
        >
          {isNew ? '尚未配置' : recordingHoldLabel(evidence)}
        </p>
      </div>
      {evidence && (
        <p className="text-text-muted">
          待验证 {evidence.pendingRecordings} · 未知{' '}
          {evidence.unknownRecordings} · 已验证开启{' '}
          {evidence.verifiedOnRecordings} · 已验证解除{' '}
          {evidence.verifiedOffRecordings}
        </p>
      )}
      {evidence?.reconciliationExhausted && (
        <p role="alert" className="text-warning">
          核对次数已用尽，请核查对象存储状态；保全仍保留。
        </p>
      )}
      {canManage && (
        <details open={isNew || undefined}>
          <summary className="cursor-pointer text-text-secondary">
            {isNew ? '配置录制保留策略' : '编辑期望策略'}
          </summary>
          <form
            className="mt-3 space-y-3"
            onSubmit={(event) => {
              event.preventDefault();
              mutation.mutate({
                dataClass: 'REMOTE_DESKTOP_RECORDING',
                retentionDays: Number(days),
                legalHold: hold,
                residencyRegion: region,
                ...(supportsCloud ? { cloudHoldRequired: cloud } : {}),
              });
            }}
          >
            <fieldset disabled={mutation.isPending} className="space-y-3">
              <label className="block space-y-1">
                <span>保留天数</span>
                <input
                  type="number"
                  min={1}
                  max={3650}
                  required
                  className={fieldClass}
                  value={days}
                  onChange={(event) => {
                    setDays(event.target.value);
                    mutation.reset();
                  }}
                />
              </label>
              <label className="block space-y-1">
                <span>存储区域</span>
                <input
                  required
                  pattern="[a-z0-9-]{1,32}"
                  className={fieldClass}
                  value={region}
                  onChange={(event) => {
                    setRegion(event.target.value);
                    mutation.reset();
                  }}
                />
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={hold}
                  onChange={(event) => {
                    setHold(event.target.checked);
                    mutation.reset();
                  }}
                />
                期望保全（解除后仍需等待云端证明）
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={cloud}
                  disabled={!supportsCloud || releasePending}
                  onChange={(event) => {
                    setCloud(event.target.checked);
                    mutation.reset();
                  }}
                />
                云端对象版本保全
              </label>
              {releasePending && (
                <p className="text-text-muted">
                  请先解除期望保全，等待解除验证完成，再关闭云端管理。
                </p>
              )}
              {!supportsCloud && (
                <p className="text-text-muted">
                  {isNew
                    ? '保存策略后将读取云端保全能力与状态。'
                    : '服务端尚未提供云保全状态，请先升级服务端。'}
                </p>
              )}
              <button
                type="submit"
                className="border border-border-subtle bg-surface-2 px-3 py-2 text-text-primary disabled:opacity-50"
              >
                {mutation.isPending ? '正在保存…' : '保存期望策略'}
              </button>
            </fieldset>
            {mutation.isSuccess && (
              <p role="status" className="text-text-secondary">
                策略已保存；云端执行结果以上方证明状态为准。
              </p>
            )}
            {mutation.isError && (
              <p role="alert" className="text-warning">
                {policyError(mutation.error)}
              </p>
            )}
          </form>
        </details>
      )}
    </div>
  );
}
