import {
  AlertTriangle,
  CheckCircle2,
  GitCompareArrows,
  History,
  LoaderCircle,
  RotateCcw,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { useAuth } from '@/auth/AuthProvider';
import {
  useRecoveryContractDiff,
  useRecoveryContractRevisions,
  useRestoreRecoveryContractRevision,
} from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';
import type {
  RecoveryContractApprovalState,
  RecoveryContractView,
} from '@/types/session';

const fieldLabels: Record<string, string> = {
  expectedOrigins: 'Expected Origins',
  readyRoutePrefixes: 'Ready Route Prefixes',
  loginRoutePrefixes: 'Login Route Prefixes',
  requiredTargets: 'Ready 必需目标',
  loginTargets: 'Login 目标',
  permissionDeniedTargets: 'Permission Denied 目标',
  accountMismatchTargets: 'Account Mismatch 目标',
  requiredExtensionIds: 'Required Extensions',
  allowDepthLimited: '接受受限状态',
  recoveryAction: '恢复动作',
  recoveryExtensionId: '恢复 Extension',
  maximumAutoRecovery: '自动恢复预算',
  enabled: '启用状态',
};

function approvalLabel(state?: RecoveryContractApprovalState) {
  switch (state) {
    case 'APPROVED':
      return 'APPROVED';
    case 'REQUESTED':
      return 'PENDING';
    case 'REJECTED':
      return 'REJECTED';
    default:
      return 'DRAFT';
  }
}

function readableValue(value: string) {
  try {
    const parsed = JSON.parse(value) as unknown;
    return JSON.stringify(parsed, null, 2);
  } catch {
    return value;
  }
}

function timeLabel(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime())
    ? 'TIME UNAVAILABLE'
    : parsed.toLocaleString();
}

export function RecoveryContractHistory({
  contract,
  dirty,
  onRestored,
}: {
  contract: RecoveryContractView;
  dirty: boolean;
  onRestored: (contract: RecoveryContractView) => void;
}) {
  const auth = useAuth();
  const revisionsQuery = useRecoveryContractRevisions(contract.applicationId);
  const restoreMutation = useRestoreRecoveryContractRevision();
  const resetRestoreMutation = restoreMutation.reset;
  const [selectedVersion, setSelectedVersion] = useState<number>();
  const [reason, setReason] = useState('');
  const [armed, setArmed] = useState(false);
  const revisions = useMemo(
    () => revisionsQuery.data?.items ?? [],
    [revisionsQuery.data?.items]
  );

  useEffect(() => {
    const currentSelection = revisions.some(
      (item) => item.version === selectedVersion
    );
    if (!currentSelection) {
      setSelectedVersion(
        revisions.find((item) => item.version < contract.version)?.version ??
          contract.version
      );
    }
  }, [contract.version, revisions, selectedVersion]);

  useEffect(() => {
    setArmed(false);
    resetRestoreMutation();
  }, [resetRestoreMutation, selectedVersion]);

  const selected = revisions.find((item) => item.version === selectedVersion);
  const diffQuery = useRecoveryContractDiff(
    contract.applicationId,
    selected?.version,
    contract.version
  );
  const isAdmin = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const restorable =
    Boolean(selected) &&
    selected!.version < contract.version &&
    selected!.approvalState === 'APPROVED';
  const requestId = isSessionApiError(restoreMutation.error)
    ? restoreMutation.error.body.requestId
    : undefined;

  const restore = async () => {
    if (!selected || !restorable || !reason.trim()) return;
    const saved = await restoreMutation.mutateAsync({
      applicationId: contract.applicationId,
      body: {
        expectedCurrentVersion: contract.version,
        sourceContractVersion: selected.version,
        reason: reason.trim(),
      },
    });
    setReason('');
    setArmed(false);
    onRestored(saved);
  };

  return (
    <section
      aria-label="恢复契约版本历史"
      className="border border-border-subtle bg-surface-2"
    >
      <div className="flex flex-col gap-3 border-b border-border-subtle px-4 py-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <History size={14} className="text-accent" />
            <h3 className="text-[11px] font-semibold text-text-primary">
              不可变版本历史
            </h3>
          </div>
          <p className="mt-1 max-w-2xl text-[9px] leading-4 text-text-muted">
            对比精确快照。恢复会创建新的 DRAFT 版本并重新走双人审批；现有
            Session 仍绑定原版本。
          </p>
        </div>
        <span className="font-mono text-[9px] text-text-muted">
          {revisions.length} SNAPSHOTS
        </span>
      </div>

      {revisionsQuery.isLoading ? (
        <div className="flex h-24 items-center justify-center gap-2 text-[10px] text-text-muted">
          <LoaderCircle size={13} className="animate-spin" />
          读取版本快照
        </div>
      ) : revisionsQuery.error ? (
        <div role="alert" className="px-4 py-3 text-[10px] text-danger">
          {revisionsQuery.error.message}
        </div>
      ) : (
        <div className="grid min-h-64 lg:grid-cols-[220px_minmax(0,1fr)]">
          <div className="border-b border-border-subtle lg:border-b-0 lg:border-r">
            {revisions.map((revision) => {
              const active = revision.version === selectedVersion;
              const isCurrent = revision.version === contract.version;
              return (
                <button
                  key={revision.version}
                  type="button"
                  onClick={() => setSelectedVersion(revision.version)}
                  className={cn(
                    'flex w-full items-start justify-between gap-3 border-b border-border-subtle px-3 py-2.5 text-left',
                    active ? 'bg-accent-soft' : 'hover:bg-surface-3'
                  )}
                >
                  <span>
                    <span
                      className={cn(
                        'block font-mono text-[10px] font-semibold',
                        active ? 'text-accent' : 'text-text-primary'
                      )}
                    >
                      v{revision.version}
                      {isCurrent ? ' · CURRENT' : ''}
                    </span>
                    <span className="mt-1 block text-[8px] text-text-muted">
                      {timeLabel(revision.updatedAt)}
                    </span>
                  </span>
                  <span
                    className={cn(
                      'px-1.5 py-0.5 font-mono text-[8px]',
                      revision.approvalState === 'APPROVED'
                        ? 'bg-success/10 text-success'
                        : revision.approvalState === 'REJECTED'
                          ? 'bg-danger/10 text-danger'
                          : revision.approvalState === 'REQUESTED'
                            ? 'bg-warning/10 text-warning'
                            : 'bg-surface-3 text-text-muted'
                    )}
                  >
                    {approvalLabel(revision.approvalState)}
                  </span>
                </button>
              );
            })}
          </div>

          <div className="min-w-0">
            <div className="flex items-center justify-between border-b border-border-subtle px-3 py-2">
              <div className="flex items-center gap-2">
                <GitCompareArrows size={13} className="text-accent" />
                <span className="font-mono text-[9px] text-text-primary">
                  {selected && selected.version !== contract.version
                    ? `v${selected.version} → v${contract.version}`
                    : `v${contract.version} CURRENT SNAPSHOT`}
                </span>
              </div>
              {diffQuery.data && (
                <span className="font-mono text-[8px] text-text-muted">
                  {diffQuery.data.total} CHANGES
                </span>
              )}
            </div>

            {selected?.version === contract.version ? (
              <div className="flex min-h-28 items-center justify-center px-4 text-center text-[10px] text-text-muted">
                这是当前精确快照。选择左侧历史版本查看字段差异。
              </div>
            ) : diffQuery.isLoading ? (
              <div className="flex min-h-28 items-center justify-center gap-2 text-[10px] text-text-muted">
                <LoaderCircle size={13} className="animate-spin" />
                计算服务端差异
              </div>
            ) : diffQuery.error ? (
              <div role="alert" className="px-4 py-3 text-[10px] text-danger">
                {diffQuery.error.message}
              </div>
            ) : diffQuery.data?.changes.length ? (
              <div className="max-h-72 overflow-y-auto">
                {diffQuery.data.changes.map((change) => (
                  <div
                    key={change.field}
                    className="grid gap-2 border-b border-border-subtle px-3 py-2.5 xl:grid-cols-[150px_minmax(0,1fr)_minmax(0,1fr)]"
                  >
                    <div>
                      <span className="block text-[9px] font-semibold text-text-primary">
                        {fieldLabels[change.field] ?? change.field}
                      </span>
                      <span className="mt-1 inline-flex bg-warning/10 px-1.5 py-0.5 font-mono text-[8px] text-warning">
                        {change.changeType}
                      </span>
                    </div>
                    <pre className="overflow-x-auto whitespace-pre-wrap break-all border-l-2 border-text-muted/25 bg-surface-1 px-2 py-1.5 font-mono text-[9px] leading-4 text-text-muted">
                      {readableValue(change.beforeValue)}
                    </pre>
                    <pre className="overflow-x-auto whitespace-pre-wrap break-all border-l-2 border-accent/60 bg-accent/5 px-2 py-1.5 font-mono text-[9px] leading-4 text-text-primary">
                      {readableValue(change.afterValue)}
                    </pre>
                  </div>
                ))}
              </div>
            ) : (
              <div className="flex min-h-28 items-center justify-center gap-2 px-4 text-[10px] text-success">
                <CheckCircle2 size={13} />
                两个版本的策略字段一致
              </div>
            )}

            {selected && selected.version < contract.version && (
              <div className="space-y-3 border-t border-border-subtle bg-surface-1 px-3 py-3">
                <div className="flex items-start gap-2 text-[9px] leading-4 text-text-muted">
                  <AlertTriangle
                    size={13}
                    className="mt-0.5 shrink-0 text-warning"
                  />
                  <span>
                    仅已批准快照可恢复。操作会生成 v{contract.version + 1}{' '}
                    DRAFT，不会切换任何已运行 Session。
                  </span>
                </div>
                {isAdmin ? (
                  <>
                    <textarea
                      value={reason}
                      onChange={(event) => {
                        setReason(event.target.value.slice(0, 500));
                        setArmed(false);
                      }}
                      disabled={
                        !restorable || dirty || restoreMutation.isPending
                      }
                      className="min-h-16 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 text-[10px] text-text-primary outline-none focus:border-accent disabled:opacity-50"
                      placeholder={
                        restorable
                          ? '填写恢复原因和验证依据'
                          : '此历史版本未批准，不能作为恢复来源'
                      }
                    />
                    <div className="flex flex-wrap items-center gap-2">
                      {!armed ? (
                        <button
                          type="button"
                          disabled={!restorable || dirty || !reason.trim()}
                          onClick={() => setArmed(true)}
                          className="inline-flex h-8 items-center gap-2 border border-warning/50 px-3 text-[9px] font-semibold text-warning disabled:cursor-not-allowed disabled:opacity-45"
                        >
                          <RotateCcw size={12} />
                          准备恢复为新草稿
                        </button>
                      ) : (
                        <>
                          <button
                            type="button"
                            disabled={restoreMutation.isPending}
                            onClick={() => void restore()}
                            className="inline-flex h-8 items-center gap-2 bg-warning px-3 text-[9px] font-semibold text-canvas disabled:opacity-45"
                          >
                            {restoreMutation.isPending ? (
                              <LoaderCircle
                                size={12}
                                className="animate-spin"
                              />
                            ) : (
                              <RotateCcw size={12} />
                            )}
                            确认创建 v{contract.version + 1} DRAFT
                          </button>
                          <button
                            type="button"
                            onClick={() => setArmed(false)}
                            className="h-8 px-3 text-[9px] text-text-muted hover:text-text-primary"
                          >
                            取消
                          </button>
                        </>
                      )}
                      {dirty && (
                        <span className="text-[9px] text-warning">
                          请先保存或撤销当前未发布修改
                        </span>
                      )}
                    </div>
                  </>
                ) : (
                  <p className="text-[9px] text-text-muted">
                    只读角色可查看历史和差异；恢复需要管理员权限。
                  </p>
                )}
                {restoreMutation.error && (
                  <div
                    role="alert"
                    className="flex items-start gap-2 border border-danger/30 bg-danger/8 px-3 py-2 text-[9px] text-danger"
                  >
                    <AlertTriangle size={12} className="mt-0.5 shrink-0" />
                    <span>
                      {restoreMutation.error.message}
                      {requestId ? ` · Request ID ${requestId}` : ''}
                    </span>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      )}
    </section>
  );
}
