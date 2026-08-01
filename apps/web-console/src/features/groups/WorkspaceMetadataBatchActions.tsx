import { useEffect, useMemo, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import {
  AlertTriangle,
  Ban,
  ChevronDown,
  CircleCheck,
  CircleX,
  LoaderCircle,
  Tags,
} from 'lucide-react';
import { isSessionApiError } from '@/api/session';
import type { WorkspaceBatchState } from '@/types/workspaceBatch';
import type { WorkspaceMetadataBatchAction } from '@/types/workspaceMetadataBatch';
import { groupKeys } from './groupQueries';
import { tagKeys } from './tagQueries';
import {
  useCancelWorkspaceMetadataBatchOperation,
  useCreateWorkspaceMetadataBatchOperation,
  useWorkspaceMetadataBatchOperation,
} from './workspaceMetadataBatchQueries';

const terminalStates: WorkspaceBatchState[] = [
  'SUCCEEDED',
  'PARTIAL_SUCCESS',
  'FAILED',
  'CANCELLED',
];

interface MembershipSession {
  sessionId: string;
  displayName: string;
  region: string;
  state: string;
}

export function WorkspaceMetadataBatchActions({
  targetType,
  targetId,
  targetName,
  assignedSessions,
  availableSessions,
}: {
  targetType: 'GROUP' | 'TAG';
  targetId: string;
  targetName: string;
  assignedSessions: MembershipSession[];
  availableSessions: MembershipSession[];
}) {
  const queryClient = useQueryClient();
  const create = useCreateWorkspaceMetadataBatchOperation();
  const cancel = useCancelWorkspaceMetadataBatchOperation();
  const [mode, setMode] = useState<'ASSIGN' | 'REMOVE'>('ASSIGN');
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [reason, setReason] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const [batchOperationId, setBatchOperationId] = useState<string>();
  const operationQuery = useWorkspaceMetadataBatchOperation(batchOperationId);
  const operation = operationQuery.data;
  const candidates = mode === 'ASSIGN' ? availableSessions : assignedSessions;
  const selected = useMemo(() => new Set(selectedIds), [selectedIds]);
  const action = metadataAction(targetType, mode);
  const mutationLabel = mode === 'ASSIGN' ? '批量添加' : '批量移除';
  const active = operation && !terminalStates.includes(operation.state);
  const canSubmit =
    selectedIds.length > 0 &&
    selectedIds.length <= 100 &&
    reason.trim().length >= 8 &&
    confirmed &&
    !create.isPending &&
    !active;
  const error = create.error ?? cancel.error ?? operationQuery.error;
  const requestId = isSessionApiError(error) ? error.body.requestId : undefined;

  useEffect(() => {
    if (!operation || !terminalStates.includes(operation.state)) return;
    void queryClient.invalidateQueries({ queryKey: groupKeys.all });
    void queryClient.invalidateQueries({ queryKey: tagKeys.all });
    void queryClient.invalidateQueries({ queryKey: ['sessions'] });
  }, [operation, queryClient]);

  const submit = async () => {
    if (!canSubmit) return;
    const accepted = await create.mutateAsync({
      action,
      selector: {
        tagIds: [],
        tagMatch: 'ANY',
        sessionIds: [...selectedIds].sort(),
      },
      target:
        targetType === 'GROUP'
          ? { groupId: targetId, tagIds: [] }
          : { tagIds: [targetId] },
      reason: reason.trim(),
      confirmed: true,
    });
    setBatchOperationId(accepted.batchOperationId);
    setConfirmed(false);
  };

  return (
    <details className="group border-t border-border-subtle bg-surface-2">
      <summary className="flex min-h-10 cursor-pointer list-none items-center justify-between gap-2 px-3 py-2 text-[10px] font-semibold text-text-secondary marker:content-none">
        <span className="inline-flex items-center gap-1.5">
          <Tags size={12} className="text-accent" />
          批量归属管理
        </span>
        <ChevronDown
          size={12}
          className="transition-transform group-open:rotate-180"
          aria-hidden="true"
        />
      </summary>

      <div className="border-t border-border-subtle px-3 pb-3 pt-2">
        <div className="flex flex-wrap items-center gap-2">
          <label className="min-w-36 flex-1">
            <span className="sr-only">{targetName} 批量归属动作</span>
            <select
              value={mode}
              disabled={Boolean(active)}
              onChange={(event) => {
                setMode(event.target.value as 'ASSIGN' | 'REMOVE');
                setSelectedIds([]);
                setConfirmed(false);
              }}
              className="field-input"
            >
              <option value="ASSIGN">批量添加到 {targetName}</option>
              <option value="REMOVE">从 {targetName} 批量移除</option>
            </select>
          </label>
          <button
            type="button"
            disabled={!candidates.length || Boolean(active)}
            onClick={() =>
              setSelectedIds(
                candidates.slice(0, 100).map((session) => session.sessionId)
              )
            }
            className="h-9 border border-border-default px-2 text-[10px] text-text-secondary disabled:opacity-40"
          >
            全选 {Math.min(candidates.length, 100)} 项
          </button>
          <button
            type="button"
            disabled={!selectedIds.length || Boolean(active)}
            onClick={() => setSelectedIds([])}
            className="h-9 border border-border-default px-2 text-[10px] text-text-muted disabled:opacity-40"
          >
            清空
          </button>
        </div>

        <fieldset
          className="mt-2 max-h-36 overflow-y-auto border border-border-subtle bg-surface-1"
          disabled={Boolean(active)}
        >
          <legend className="sr-only">选择要{mutationLabel}的环境</legend>
          {candidates.length ? (
            candidates.map((session) => (
              <label
                key={session.sessionId}
                className="flex min-h-9 cursor-pointer items-center gap-2 border-b border-border-subtle px-2 py-1.5 last:border-b-0 hover:bg-surface-2"
              >
                <input
                  type="checkbox"
                  checked={selected.has(session.sessionId)}
                  onChange={(event) => {
                    setSelectedIds((current) =>
                      event.target.checked
                        ? current.length >= 100
                          ? current
                          : [...current, session.sessionId]
                        : current.filter((value) => value !== session.sessionId)
                    );
                  }}
                  className="h-4 w-4 shrink-0 accent-accent"
                />
                <span className="min-w-0 flex-1 truncate text-[10px] text-text-primary">
                  {session.displayName}
                </span>
                <span className="shrink-0 font-mono text-[8px] text-text-muted">
                  {session.region} · {session.state}
                </span>
              </label>
            ))
          ) : (
            <p className="px-3 py-4 text-center text-[10px] text-text-muted">
              当前没有可{mutationLabel}的环境
            </p>
          )}
        </fieldset>

        <input
          value={reason}
          disabled={Boolean(active)}
          onChange={(event) => setReason(event.target.value)}
          maxLength={240}
          placeholder="填写变更原因，至少 8 个字符"
          className="field-input mt-2 w-full"
        />
        <label className="mt-2 flex cursor-pointer items-start gap-2 text-[10px] leading-4 text-warning">
          <input
            type="checkbox"
            checked={confirmed}
            disabled={Boolean(active)}
            onChange={(event) => setConfirmed(event.target.checked)}
            className="mt-0.5 h-4 w-4 accent-accent"
          />
          <span>
            我确认将对选中的 {selectedIds.length} 个环境执行真实 PostgreSQL
            归属变更；已开始的单项会原子完成，取消只影响尚未执行项。
          </span>
        </label>
        <button
          type="button"
          disabled={!canSubmit}
          onClick={() => void submit()}
          aria-label={`提交${mutationLabel} ${targetName}`}
          className="mt-2 inline-flex h-9 w-full items-center justify-center gap-1.5 bg-accent px-3 text-[11px] font-semibold text-canvas disabled:opacity-45"
        >
          {create.isPending ? (
            <LoaderCircle size={13} className="animate-spin" />
          ) : (
            <Tags size={13} />
          )}
          提交 {selectedIds.length} 项
        </button>

        {operation && (
          <div className="mt-3 border border-border-subtle bg-surface-1 p-3">
            <div className="flex flex-wrap items-start justify-between gap-2">
              <div>
                <p className="flex items-center gap-1.5 text-[11px] font-medium text-text-primary">
                  <MetadataStateIcon state={operation.state} />
                  {metadataStateLabel(operation.state)}
                </p>
                <p className="mt-1 font-mono text-[9px] text-text-muted">
                  {operation.batchOperationId}
                </p>
              </div>
              {active && !operation.cancellationRequested && (
                <button
                  type="button"
                  disabled={cancel.isPending}
                  onClick={() =>
                    cancel.mutate({
                      batchOperationId: operation.batchOperationId,
                      reason: 'Operator cancelled pending metadata batch items',
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
              <MetadataCount label="接受" value={operation.accepted} />
              <MetadataCount label="执行中" value={operation.executing} />
              <MetadataCount label="成功" value={operation.succeeded} />
              <MetadataCount label="失败" value={operation.failed} />
              <MetadataCount label="取消" value={operation.cancelled} />
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
            批量归属操作失败
            {requestId ? ` · Request ID ${requestId}` : ''}
          </p>
        )}
      </div>
    </details>
  );
}

function metadataAction(
  targetType: 'GROUP' | 'TAG',
  mode: 'ASSIGN' | 'REMOVE'
): WorkspaceMetadataBatchAction {
  if (targetType === 'GROUP') {
    return mode === 'ASSIGN' ? 'ASSIGN_GROUP' : 'REMOVE_GROUP';
  }
  return mode === 'ASSIGN' ? 'ASSIGN_TAGS' : 'REMOVE_TAGS';
}

function MetadataCount({ label, value }: { label: string; value: number }) {
  return (
    <span className="bg-surface-2 px-2 py-2 text-center">
      <span className="block font-mono text-[12px] text-text-primary">
        {value}
      </span>
      <span className="mt-0.5 block text-[9px] text-text-muted">{label}</span>
    </span>
  );
}

function MetadataStateIcon({ state }: { state: WorkspaceBatchState }) {
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

function metadataStateLabel(state: WorkspaceBatchState) {
  return {
    ACCEPTED: '已接受，等待 Metadata Worker',
    EXECUTING: '正在写入归属关系',
    CANCELLING: '正在取消未执行项',
    SUCCEEDED: '全部成功',
    PARTIAL_SUCCESS: '部分成功',
    FAILED: '执行失败',
    CANCELLED: '已取消',
  }[state];
}
