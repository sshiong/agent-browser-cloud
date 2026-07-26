import { useState } from 'react';
import { KeyRound, Plus, ShieldAlert, X } from 'lucide-react';
import {
  useCompleteKeyRotationRequest,
  useCreateKeyRotationRequest,
  useKeyRotationRequests,
  useTransitionKeyRotationRequest,
} from './platformQueries';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { DEFAULT_ACTOR_ID } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type {
  CompleteKeyRotationRequest,
  CreateKeyRotationRequest,
  KeyRotationRequestView,
} from '@/types/platform';

const initialRequest: CreateKeyRotationRequest = {
  keyScope: 'NODE_MTLS',
  oldKeyId: '',
  newKeyId: '',
  rotationTrigger: 'SCHEDULED',
  reason: '',
  overlapMinutes: 30,
};

const initialCompletion: CompleteKeyRotationRequest = {
  newKeyWriteVerified: false,
  oldKeyReadVerified: false,
  plaintextRejected: false,
  affectedWorkloads: 1,
  verificationReference: '',
};

export function KeyRotationWorkspace() {
  const query = useKeyRotationRequests();
  const create = useCreateKeyRotationRequest();
  const transition = useTransitionKeyRotationRequest();
  const complete = useCompleteKeyRotationRequest();
  const [requesting, setRequesting] = useState(false);
  const [completingId, setCompletingId] = useState<string | null>(null);
  const [form, setForm] = useState(initialRequest);
  const [completion, setCompletion] = useState(initialCompletion);
  const mutationError = create.error ?? transition.error ?? complete.error;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    await create.mutateAsync(form);
    setForm(initialRequest);
    setRequesting(false);
  }

  async function submitCompletion(event: React.FormEvent) {
    event.preventDefault();
    if (!completingId) return;
    await complete.mutateAsync({ rotationId: completingId, completion });
    setCompletion(initialCompletion);
    setCompletingId(null);
  }

  return (
    <section className="mt-4 border border-border-subtle bg-surface-1">
      <header className="flex flex-col gap-3 border-b border-border-subtle bg-surface-2 px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="flex items-center gap-3">
          <KeyRound size={15} className="text-accent" />
          <div>
            <h2 className="text-[12px] font-semibold text-text-primary">
              Key Rotation 控制面
            </h2>
            <p className="mt-0.5 text-[10px] text-text-muted">
              双人审批 · Dual-read / Single-write · 验证后退休旧密钥
            </p>
          </div>
        </div>
        <button
          type="button"
          onClick={() => setRequesting((value) => !value)}
          className="inline-flex h-8 items-center justify-center gap-1.5 border border-accent/40 px-3 text-[11px] font-medium text-accent hover:bg-accent/8"
        >
          {requesting ? <X size={12} /> : <Plus size={12} />}
          {requesting ? '取消轮换' : '发起密钥轮换'}
        </button>
      </header>

      {requesting && (
        <form
          onSubmit={(event) => void submit(event)}
          className="grid gap-3 border-b border-border-subtle bg-accent/[0.025] p-4 lg:grid-cols-12"
        >
          <label className="lg:col-span-3">
            <span className="field-label">密钥范围</span>
            <select
              className="field-input"
              value={form.keyScope}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  keyScope: event.target
                    .value as CreateKeyRotationRequest['keyScope'],
                }))
              }
            >
              {[
                'NODE_MTLS',
                'RUNTIME_SIGNING',
                'PROFILE_KEK',
                'REMOTE_DESKTOP',
                'AGENT_CAPABILITY',
              ].map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">旧 Key ID</span>
            <input
              required
              minLength={3}
              maxLength={200}
              className="field-input font-mono"
              value={form.oldKeyId}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  oldKeyId: event.target.value,
                }))
              }
              placeholder="node-ca-v1"
            />
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">新 Key ID</span>
            <input
              required
              minLength={3}
              maxLength={200}
              className="field-input font-mono"
              value={form.newKeyId}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  newKeyId: event.target.value,
                }))
              }
              placeholder="node-ca-v2"
            />
          </label>
          <label className="lg:col-span-3">
            <span className="field-label">触发原因</span>
            <select
              className="field-input"
              value={form.rotationTrigger}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  rotationTrigger: event.target
                    .value as CreateKeyRotationRequest['rotationTrigger'],
                }))
              }
            >
              {[
                'SCHEDULED',
                'PERSONNEL_CHANGE',
                'POLICY_CHANGE',
                'SUSPECTED_COMPROMISE',
                'TENANT_REQUEST',
              ].map((value) => (
                <option key={value}>{value}</option>
              ))}
            </select>
          </label>
          <label className="lg:col-span-9">
            <span className="field-label">轮换原因与影响（20–500 字符）</span>
            <input
              required
              minLength={20}
              maxLength={500}
              className="field-input"
              value={form.reason}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  reason: event.target.value,
                }))
              }
              placeholder="说明到期、风险、影响范围和回退依据"
            />
          </label>
          <label className="lg:col-span-1">
            <span className="field-label">重叠分钟</span>
            <input
              required
              type="number"
              min={0}
              max={1440}
              className="field-input"
              value={form.overlapMinutes}
              onChange={(event) =>
                setForm((current) => ({
                  ...current,
                  overlapMinutes: Number(event.target.value),
                }))
              }
            />
          </label>
          <div className="flex items-end lg:col-span-2">
            <button
              type="submit"
              disabled={create.isPending}
              className="h-9 w-full bg-accent px-3 text-[11px] font-semibold text-surface-0 disabled:opacity-50"
            >
              {create.isPending ? '提交中…' : '提交双人审批'}
            </button>
          </div>
        </form>
      )}

      {mutationError && (
        <p
          role="alert"
          className="border-b border-danger/30 bg-danger/8 px-4 py-2 text-[11px] text-danger"
        >
          {mutationError.message}
        </p>
      )}

      {query.isLoading ? (
        <LoadingRows rows={3} />
      ) : query.isError ? (
        <ErrorState
          error={query.error}
          onRetry={() => query.refetch()}
          title="无法读取密钥轮换请求"
        />
      ) : !query.data?.items.length ? (
        <EmptyState
          title="没有进行中的密钥轮换"
          description="按到期策略或安全事件发起轮换；旧验证器只能在明确的重叠窗口内保留。"
        />
      ) : (
        <div className="divide-y divide-border-subtle">
          {query.data.items.map((item) => (
            <div key={item.rotationId}>
              <KeyRotationRow
                item={item}
                busy={transition.isPending || complete.isPending}
                completing={completingId === item.rotationId}
                onComplete={() =>
                  setCompletingId((current) =>
                    current === item.rotationId ? null : item.rotationId
                  )
                }
                onTransition={(transitionName) =>
                  transition.mutateAsync({
                    rotationId: item.rotationId,
                    transition: transitionName,
                  })
                }
              />
              {completingId === item.rotationId && (
                <CompletionEvidenceForm
                  value={completion}
                  busy={complete.isPending}
                  onChange={setCompletion}
                  onSubmit={submitCompletion}
                />
              )}
            </div>
          ))}
        </div>
      )}
    </section>
  );
}

function KeyRotationRow({
  item,
  busy,
  completing,
  onComplete,
  onTransition,
}: {
  item: KeyRotationRequestView;
  busy: boolean;
  completing: boolean;
  onComplete: () => void;
  onTransition: (transition: 'approve' | 'revoke') => Promise<unknown>;
}) {
  const ownRequest = item.requestedBy === DEFAULT_ACTOR_ID;
  const tone = {
    REQUESTED: 'text-warning',
    ROTATING: 'text-accent',
    COMPLETED: 'text-success',
    REVOKED: 'text-text-muted',
    FAILED: 'text-danger',
  }[item.state];

  return (
    <article className="grid gap-3 px-4 py-3 lg:grid-cols-[180px_1fr_190px_auto] lg:items-center">
      <div>
        <p className="font-mono text-[10px] text-accent">{item.keyScope}</p>
        <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
          {item.rotationId}
        </p>
      </div>
      <div className="min-w-0">
        <p className="truncate font-mono text-[11px] text-text-primary">
          {item.oldKeyId}
          <span className="mx-2 text-border-strong">→</span>
          {item.newKeyId}
        </p>
        <p className="mt-1 truncate text-[10px] text-text-muted">
          {item.rotationTrigger} · {item.reason}
        </p>
      </div>
      <div className="text-[10px] text-text-muted">
        <p>
          申请 {item.requestedBy}
          {item.approvedBy ? ` · 审批 ${item.approvedBy}` : ''}
        </p>
        <p className="mt-1 font-mono">
          overlap {item.requestedOverlapMinutes}m · progress{' '}
          {item.progressPercent}%
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-2 lg:justify-end">
        <span className={cn('font-mono text-[10px] font-semibold', tone)}>
          {item.state}
        </span>
        {item.state === 'REQUESTED' && !ownRequest && (
          <Action
            label="批准轮换"
            busy={busy}
            onClick={() => onTransition('approve')}
          />
        )}
        {item.state === 'REQUESTED' && ownRequest && (
          <span className="text-[9px] text-text-muted">等待另一位管理员</span>
        )}
        {item.state === 'ROTATING' && (
          <>
            <Action
              label={completing ? '收起证据' : '提交验证证据'}
              busy={busy}
              onClick={async () => onComplete()}
            />
            <Action
              label="撤销"
              busy={busy}
              danger
              onClick={() => onTransition('revoke')}
            />
          </>
        )}
        {item.state === 'COMPLETED' && item.completionEvidenceHash && (
          <span
            className="max-w-[90px] truncate font-mono text-[9px] text-success"
            title={item.completionEvidenceHash}
          >
            {item.completionEvidenceHash}
          </span>
        )}
      </div>
    </article>
  );
}

function CompletionEvidenceForm({
  value,
  busy,
  onChange,
  onSubmit,
}: {
  value: CompleteKeyRotationRequest;
  busy: boolean;
  onChange: (value: CompleteKeyRotationRequest) => void;
  onSubmit: (event: React.FormEvent) => void;
}) {
  return (
    <form
      onSubmit={onSubmit}
      className="grid gap-3 border-t border-border-subtle bg-surface-2/50 px-4 py-3 lg:grid-cols-[1fr_1fr_1fr_100px_2fr_auto]"
    >
      {(
        [
          ['新密钥写入验证', 'newKeyWriteVerified'],
          ['旧密钥读取验证', 'oldKeyReadVerified'],
          ['明文链路拒绝', 'plaintextRejected'],
        ] as const
      ).map(([label, key]) => (
        <label
          key={key}
          className="flex min-h-9 items-center gap-2 border border-border-subtle px-3 text-[10px] text-text-secondary"
        >
          <input
            type="checkbox"
            checked={value[key]}
            onChange={(event) =>
              onChange({ ...value, [key]: event.target.checked })
            }
          />
          {label}
        </label>
      ))}
      <label>
        <span className="sr-only">受影响工作负载</span>
        <input
          type="number"
          min={1}
          required
          className="field-input"
          value={value.affectedWorkloads}
          onChange={(event) =>
            onChange({
              ...value,
              affectedWorkloads: Number(event.target.value),
            })
          }
          aria-label="受影响工作负载"
        />
      </label>
      <label>
        <span className="sr-only">验证证据引用</span>
        <input
          required
          minLength={8}
          maxLength={500}
          className="field-input"
          value={value.verificationReference}
          onChange={(event) =>
            onChange({ ...value, verificationReference: event.target.value })
          }
          placeholder="验证证据引用，例如 gameday/run-2026-07"
          aria-label="验证证据引用"
        />
      </label>
      <button
        type="submit"
        disabled={busy}
        className="inline-flex h-9 items-center gap-2 bg-success px-3 text-[10px] font-semibold text-surface-0 disabled:opacity-50"
      >
        <ShieldAlert size={12} />
        完成轮换
      </button>
    </form>
  );
}

function Action({
  label,
  busy,
  danger = false,
  onClick,
}: {
  label: string;
  busy: boolean;
  danger?: boolean;
  onClick: () => Promise<unknown>;
}) {
  return (
    <button
      type="button"
      disabled={busy}
      onClick={() => void onClick()}
      className={cn(
        'h-7 border px-2 text-[10px] disabled:opacity-50',
        danger
          ? 'border-danger/40 text-danger hover:bg-danger/8'
          : 'border-border-default text-text-secondary hover:text-text-primary'
      )}
    >
      {label}
    </button>
  );
}
