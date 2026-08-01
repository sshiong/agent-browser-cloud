import { useState } from 'react';
import {
  AlertTriangle,
  Ban,
  CircleCheck,
  CircleX,
  LoaderCircle,
  Play,
} from 'lucide-react';
import { isSessionApiError } from '@/api/session';
import type {
  WorkspaceBatchAction,
  WorkspaceBatchSelector,
  WorkspaceBatchState,
} from '@/types/workspaceBatch';
import {
  useCancelWorkspaceBatchOperation,
  useCreateWorkspaceBatchOperation,
  useWorkspaceBatchOperation,
} from './workspaceBatchQueries';

const actionLabels: Record<WorkspaceBatchAction, string> = {
  START: '批量启动',
  PAUSE_AGENT: '暂停 Agent，保留浏览器',
  MIGRATE: '等待安全点并迁移',
  HIBERNATE: '等待安全点并休眠',
};

const terminalStates: WorkspaceBatchState[] = [
  'SUCCEEDED',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
];

export function WorkspaceBatchActions({
  selector,
  targetCount,
  label,
}: {
  selector: WorkspaceBatchSelector;
  targetCount: number;
  label: string;
}) {
  const create = useCreateWorkspaceBatchOperation();
  const cancel = useCancelWorkspaceBatchOperation();
  const [action, setAction] = useState<WorkspaceBatchAction>('START');
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [batchOperationId, setBatchOperationId] = useState<string>();
  const operationQuery = useWorkspaceBatchOperation(batchOperationId);
  const operation = operationQuery.data;
  const risky = action !== 'START';
  const canSubmit =
    targetCount > 0 &&
    !create.isPending &&
    (!risky || (confirmed && reason.trim().length >= 8));
  const error = create.error ?? cancel.error ?? operationQuery.error;
  const requestId = isSessionApiError(error) ? error.body.requestId : undefined;

  const submit = async () => {
    if (!canSubmit) return;
    const accepted = await create.mutateAsync({
      action,
      selector,
      reason: reason.trim() || undefined,
      confirmed: risky ? confirmed : false,
    });
    setBatchOperationId(accepted.batchOperationId);
    setConfirmed(false);
  };

  return (
    <section className="border-t border-border-subtle bg-surface-2 px-3 py-3">
      <div className="flex flex-wrap items-center gap-2">
        <label className="min-w-[190px] flex-1">
          <span className="sr-only">{label}批量动作</span>
          <select
            value={action}
            disabled={Boolean(
              operation && !terminalStates.includes(operation.state)
            )}
            onChange={(event) => {
              setAction(event.target.value as WorkspaceBatchAction);
              setConfirmed(false);
            }}
            className="field-input"
          >
            {(Object.keys(actionLabels) as WorkspaceBatchAction[]).map(
              (value) => (
                <option key={value} value={value}>
                  {actionLabels[value]}
                </option>
              )
            )}
          </select>
        </label>
        <button
          type="button"
          disabled={!canSubmit}
          onClick={() => void submit()}
          className="inline-flex h-9 items-center gap-1.5 bg-accent px-3 text-[11px] font-semibold text-canvas disabled:opacity-45"
        >
          {create.isPending ? (
            <LoaderCircle size={13} className="animate-spin" />
          ) : (
            <Play size={13} />
          )}
          提交 {targetCount} 项
        </button>
      </div>

      {risky && (
        <div className="mt-2 space-y-2">
          <input
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            maxLength={240}
            placeholder="填写操作原因，至少 8 个字符"
            className="field-input w-full"
          />
          <label className="flex cursor-pointer items-start gap-2 text-[10px] leading-4 text-warning">
            <input
              type="checkbox"
              checked={confirmed}
              onChange={(event) => setConfirmed(event.target.checked)}
              className="mt-0.5 h-4 w-4 accent-accent"
            />
            <span>
              我确认该动作可能重启 Browser、重连网络；迁移和休眠会等待真实 Safe
              Point，不会绕过 HumanTakeover 或关键事务屏障。
            </span>
          </label>
        </div>
      )}

      {operation && (
        <div className="mt-3 border border-border-subtle bg-surface-1 p-3">
          <div className="flex flex-wrap items-start justify-between gap-2">
            <div>
              <p className="flex items-center gap-1.5 text-[11px] font-medium text-text-primary">
                <BatchStateIcon state={operation.state} />
                {stateLabel(operation.state)}
              </p>
              <p className="mt-1 font-mono text-[9px] text-text-muted">
                {operation.batchOperationId}
              </p>
            </div>
            {!terminalStates.includes(operation.state) &&
              !operation.cancellationRequested && (
                <button
                  type="button"
                  disabled={cancel.isPending}
                  onClick={() =>
                    cancel.mutate({
                      batchOperationId: operation.batchOperationId,
                      reason: 'Operator cancelled pending batch items',
                    })
                  }
                  className="inline-flex h-8 items-center gap-1.5 border border-border-default px-2 text-[10px] text-text-muted hover:text-warning"
                >
                  <Ban size={12} />
                  取消未执行项
                </button>
              )}
          </div>
          <div className="mt-3 grid grid-cols-3 gap-px bg-border-subtle sm:grid-cols-5">
            <BatchCount label="接受" value={operation.accepted} />
            <BatchCount label="执行中" value={operation.executing} />
            <BatchCount label="成功" value={operation.succeeded} />
            <BatchCount label="失败" value={operation.failed} />
            <BatchCount label="取消" value={operation.cancelled} />
          </div>
          {operation.items.some((item) => item.failureCode) && (
            <div className="mt-2 space-y-1">
              {operation.items
                .filter((item) => item.failureCode)
                .slice(0, 4)
                .map((item) => (
                  <p
                    key={item.batchItemId}
                    className="flex items-start gap-1.5 font-mono text-[9px] text-danger"
                  >
                    <AlertTriangle size={11} className="mt-0.5 shrink-0" />
                    {item.sessionId}: {item.failureCode}
                  </p>
                ))}
            </div>
          )}
        </div>
      )}

      {error && (
        <p className="mt-2 text-[10px] text-danger" role="alert">
          批量操作失败
          {requestId ? ` · Request ID ${requestId}` : ''}
        </p>
      )}
    </section>
  );
}

function BatchCount({ label, value }: { label: string; value: number }) {
  return (
    <span className="bg-surface-2 px-2 py-2 text-center">
      <span className="block font-mono text-[12px] text-text-primary">
        {value}
      </span>
      <span className="mt-0.5 block text-[9px] text-text-muted">{label}</span>
    </span>
  );
}

function BatchStateIcon({ state }: { state: WorkspaceBatchState }) {
  if (state === 'SUCCEEDED') {
    return <CircleCheck size={13} className="text-accent" />;
  }
  if (state === 'FAILED') {
    return <CircleX size={13} className="text-danger" />;
  }
  if (state === 'PARTIAL_SUCCESS') {
    return <AlertTriangle size={13} className="text-warning" />;
  }
  if (state === 'CANCELLED') {
    return <Ban size={13} className="text-text-muted" />;
  }
  return <LoaderCircle size={13} className="animate-spin text-accent" />;
}

function stateLabel(state: WorkspaceBatchState) {
  return {
    ACCEPTED: '已接受，等待 Coordinator',
    EXECUTING: '正在执行',
    CANCELLING: '正在取消未执行项',
    SUCCEEDED: '全部成功',
    PARTIAL_SUCCESS: '部分成功',
    FAILED: '执行失败',
    CANCELLED: '已取消',
  }[state];
}
