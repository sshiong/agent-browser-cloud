import * as Dialog from '@radix-ui/react-dialog';
import { useRef, useState } from 'react';
import {
  AlertTriangle,
  Check,
  FileJson,
  History,
  LoaderCircle,
  ShieldCheck,
  Upload,
  X,
} from 'lucide-react';
import { isSessionApiError } from '@/api/session';
import { cn } from '@/shared/lib/utils';
import type {
  EnvironmentImport,
  PreviewEnvironmentImportRequest,
} from '@/types/environmentImport';
import {
  useCommitEnvironmentImport,
  useEnvironmentImports,
  usePreviewEnvironmentImport,
} from './environmentImportQueries';

const MAX_FILE_BYTES = 256 * 1024;
const errorLabels: Record<string, string> = {
  DUPLICATE_ENVIRONMENT_SPEC: '清单内存在完全重复的环境',
  PROFILE_TENANT_CONFLICT: 'Profile 属于其他租户',
  WORKSPACE_GROUP_NOT_FOUND: '工作区分组不存在',
  WORKSPACE_TAG_NOT_FOUND: '一个或多个标签不存在',
  RECOVERY_CONTRACT_NOT_FOUND: '应用恢复契约不存在或未启用',
  RECOVERY_CONTRACT_NOT_APPROVED: '应用恢复契约尚未批准',
  RECOVERY_CONTRACT_INVALID: '应用恢复契约标识无效',
  RESOURCE_POLICY_MODE_MUST_BE_AUTO: '资源策略必须为 AUTO',
  STRICT_TERMINATION_REQUIRES_PLATFORM_ADMIN: '严格终止策略仅限平台管理员',
  MEDIA_CAPACITY_REQUIRES_MEDIA_WORKLOAD: '媒体资源要求启用 mediaWorkload',
};

export function EnvironmentImportDrawer({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [fileName, setFileName] = useState('');
  const [localError, setLocalError] = useState('');
  const [preview, setPreview] = useState<EnvironmentImport>();
  const history = useEnvironmentImports(open);
  const previewMutation = usePreviewEnvironmentImport();
  const commitMutation = useCommitEnvironmentImport();
  const busy = previewMutation.isPending || commitMutation.isPending;
  const mutationError = previewMutation.error ?? commitMutation.error;

  const reset = () => {
    setFileName('');
    setLocalError('');
    setPreview(undefined);
    previewMutation.reset();
    commitMutation.reset();
    if (fileRef.current) fileRef.current.value = '';
  };

  const handleOpenChange = (next: boolean) => {
    if (busy) return;
    if (!next) reset();
    onOpenChange(next);
  };

  const readFile = async (file?: File) => {
    setLocalError('');
    setPreview(undefined);
    previewMutation.reset();
    commitMutation.reset();
    if (!file) return;
    setFileName(file.name);
    if (file.size > MAX_FILE_BYTES) {
      setLocalError('清单不能超过 256 KiB。');
      return;
    }
    try {
      const parsed = JSON.parse(
        await file.text()
      ) as PreviewEnvironmentImportRequest;
      if (
        parsed.schemaVersion !== 1 ||
        typeof parsed.name !== 'string' ||
        !Array.isArray(parsed.environments)
      ) {
        throw new Error(
          '清单必须包含 schemaVersion: 1、name 和 environments。'
        );
      }
      const result = await previewMutation.mutateAsync(parsed);
      setPreview(result);
    } catch (error) {
      if (isSessionApiError(error)) return;
      setLocalError(
        error instanceof Error ? error.message : '无法读取这份 JSON 清单。'
      );
    }
  };

  const commit = async () => {
    if (!preview || preview.state !== 'VALIDATED') return;
    const result = await commitMutation.mutateAsync(preview);
    setPreview(result);
  };

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[900px] flex-col border-l border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex min-h-[76px] items-center justify-between border-b border-border-subtle px-5 sm:px-7">
            <div className="min-w-0">
              <p className="font-mono text-[9px] uppercase tracking-[0.18em] text-accent">
                Controlled batch provisioning
              </p>
              <Dialog.Title className="mt-1 text-[17px] font-semibold text-text-primary">
                导入浏览器环境
              </Dialog.Title>
              <Dialog.Description className="mt-0.5 text-[11px] text-text-muted">
                文件只用于提交；预检、执行结果和审计记录以 Control Plane 为准。
              </Dialog.Description>
            </div>
            <Dialog.Close
              aria-label="关闭导入工作区"
              disabled={busy}
              className="flex h-9 w-9 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:opacity-40"
            >
              <X size={17} />
            </Dialog.Close>
          </header>

          <div className="grid min-h-0 flex-1 lg:grid-cols-[220px_1fr]">
            <aside className="border-b border-border-subtle bg-surface-2/55 p-5 lg:border-b-0 lg:border-r">
              <p className="font-mono text-[9px] uppercase tracking-[0.16em] text-text-muted">
                Import protocol
              </p>
              <ol className="mt-5 space-y-5">
                <ProtocolStep
                  index="01"
                  label="选择清单"
                  active={!preview}
                  complete={Boolean(fileName)}
                />
                <ProtocolStep
                  index="02"
                  label="服务端预检"
                  active={Boolean(preview && preview.state !== 'COMMITTED')}
                  complete={Boolean(preview)}
                />
                <ProtocolStep
                  index="03"
                  label="事务提交"
                  active={preview?.state === 'COMMITTED'}
                  complete={preview?.state === 'COMMITTED'}
                />
              </ol>
              <div className="mt-8 border-t border-border-subtle pt-5">
                <p className="flex items-center gap-2 text-[11px] font-medium text-text-secondary">
                  <History size={13} /> 最近预检
                </p>
                <div className="mt-3 space-y-2">
                  {history.data?.items.slice(0, 4).map((item) => (
                    <div
                      key={item.importId}
                      className="border-l border-border-default pl-3"
                    >
                      <p className="truncate text-[11px] text-text-secondary">
                        {item.name}
                      </p>
                      <p className="mt-0.5 font-mono text-[9px] text-text-muted">
                        {item.state} · {item.readyCount}/{item.totalCount}
                      </p>
                    </div>
                  ))}
                  {!history.isLoading && !history.data?.items.length && (
                    <p className="text-[10px] leading-4 text-text-muted">
                      当前操作者还没有导入记录。
                    </p>
                  )}
                </div>
              </div>
            </aside>

            <main className="min-h-0 overflow-y-auto p-5 sm:p-7">
              {!preview ? (
                <section>
                  <div
                    className={cn(
                      'flex min-h-[250px] flex-col items-center justify-center border border-dashed px-6 text-center transition-colors',
                      busy
                        ? 'border-accent/40 bg-accent-soft/30'
                        : 'border-border-default bg-surface-2/35 hover:border-accent/50'
                    )}
                  >
                    {busy ? (
                      <LoaderCircle
                        size={25}
                        className="animate-spin text-accent"
                      />
                    ) : (
                      <span className="flex h-12 w-12 items-center justify-center border border-accent/25 bg-accent-soft text-accent">
                        <Upload size={20} />
                      </span>
                    )}
                    <h2 className="mt-5 text-[15px] font-semibold text-text-primary">
                      {busy ? 'Control Plane 正在预检' : '选择 JSON 环境清单'}
                    </h2>
                    <p className="mt-2 max-w-[460px] text-[11px] leading-5 text-text-muted">
                      每次 1–25 个环境，最大 256 KiB。不会从文件读取
                      Cookie、密码、 Token 或浏览器状态。
                    </p>
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => fileRef.current?.click()}
                      className="mt-5 inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas hover:bg-accent/90 disabled:opacity-50"
                    >
                      <FileJson size={14} />
                      {fileName || '选择清单'}
                    </button>
                    <input
                      ref={fileRef}
                      type="file"
                      accept="application/json,.json"
                      className="sr-only"
                      onChange={(event) => readFile(event.target.files?.[0])}
                    />
                  </div>
                  <div className="mt-5 grid gap-px bg-border-subtle sm:grid-cols-3">
                    <ImportRule label="资源策略" detail="只接受 AUTO" />
                    <ImportRule label="执行语义" detail="全成功或全回滚" />
                    <ImportRule
                      label="结果来源"
                      detail="真实 Session / Operation"
                    />
                  </div>
                </section>
              ) : (
                <PreviewResult environmentImport={preview} />
              )}

              {(localError || mutationError) && (
                <ErrorBanner error={localError || mutationError} />
              )}
            </main>
          </div>

          <footer className="flex min-h-[70px] flex-wrap items-center justify-between gap-3 border-t border-border-subtle bg-surface-2/55 px-5 sm:px-7">
            <p className="text-[10px] text-text-muted">
              {preview
                ? `Manifest ${preview.manifestHash.slice(0, 12)}… · Version ${preview.version}`
                : '预检不会创建 Profile、Session 或 Operation。'}
            </p>
            <div className="flex items-center gap-2">
              {preview && preview.state !== 'COMMITTED' && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={reset}
                  className="h-9 border border-border-default px-4 text-[12px] text-text-secondary hover:bg-surface-3 disabled:opacity-50"
                >
                  更换清单
                </button>
              )}
              {preview?.state === 'VALIDATED' && (
                <button
                  type="button"
                  disabled={busy}
                  onClick={commit}
                  className="inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas hover:bg-accent/90 disabled:opacity-50"
                >
                  {busy ? (
                    <LoaderCircle size={14} className="animate-spin" />
                  ) : (
                    <ShieldCheck size={14} />
                  )}
                  提交全部 {preview.totalCount} 个环境
                </button>
              )}
              {preview?.state === 'COMMITTED' && (
                <Dialog.Close className="h-9 bg-accent px-4 text-[12px] font-semibold text-canvas">
                  完成
                </Dialog.Close>
              )}
            </div>
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function PreviewResult({
  environmentImport,
}: {
  environmentImport: EnvironmentImport;
}) {
  const committed = environmentImport.state === 'COMMITTED';
  return (
    <section>
      <div className="flex flex-wrap items-start justify-between gap-4 border-b border-border-subtle pb-5">
        <div>
          <p className="font-mono text-[9px] uppercase tracking-[0.16em] text-text-muted">
            Server validation result
          </p>
          <h2 className="mt-1 text-[16px] font-semibold text-text-primary">
            {environmentImport.name}
          </h2>
          <p className="mt-1 text-[11px] text-text-muted">
            {committed
              ? '事务已提交，以下 ID 来自正式 Session 与 Operation。'
              : environmentImport.state === 'VALIDATED'
                ? '所有引用与策略均已通过，可以执行事务提交。'
                : '存在阻断项，修复源清单后重新预检。'}
          </p>
        </div>
        <StatusBadge state={environmentImport.state} />
      </div>
      <div className="grid gap-px bg-border-subtle sm:grid-cols-3">
        <ImportRule
          label="清单总数"
          detail={String(environmentImport.totalCount)}
        />
        <ImportRule
          label="预检通过"
          detail={`${environmentImport.readyCount}/${environmentImport.totalCount}`}
        />
        <ImportRule
          label="已创建"
          detail={`${environmentImport.succeededCount}/${environmentImport.totalCount}`}
        />
      </div>
      <div className="mt-5 overflow-hidden border border-border-subtle">
        {environmentImport.items.map((item) => (
          <div
            key={item.itemId}
            className="grid gap-3 border-b border-border-subtle bg-surface-1 px-4 py-3 last:border-b-0 sm:grid-cols-[36px_1fr_auto]"
          >
            <span className="font-mono text-[10px] text-text-muted">
              {String(item.itemIndex + 1).padStart(2, '0')}
            </span>
            <div className="min-w-0">
              <p className="truncate text-[12px] font-medium text-text-primary">
                {item.specification.displayName}
              </p>
              <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
                {item.specification.profileId}
                {item.specification.runtimeBuildId
                  ? ` · ${item.specification.runtimeBuildId}`
                  : ' · WORKSPACE RUNTIME'}
              </p>
              {item.validationErrors.length > 0 && (
                <ul className="mt-2 space-y-1">
                  {item.validationErrors.map((error) => (
                    <li
                      key={error}
                      className="flex items-start gap-1.5 text-[10px] text-danger"
                    >
                      <AlertTriangle size={11} className="mt-0.5 shrink-0" />
                      {errorLabels[error] ??
                        (error.startsWith('RUNTIME_')
                          ? `Runtime 不可用：${error.slice(8)}`
                          : error)}
                    </li>
                  ))}
                </ul>
              )}
              {item.sessionId && (
                <p className="mt-2 font-mono text-[9px] text-accent">
                  {item.sessionId} · {item.operationId}
                </p>
              )}
            </div>
            <span
              className={cn(
                'inline-flex h-6 items-center gap-1 border px-2 font-mono text-[9px]',
                item.validationState === 'READY'
                  ? 'border-success/30 text-success'
                  : 'border-danger/30 text-danger'
              )}
            >
              {item.executionState === 'SUCCEEDED' && <Check size={10} />}
              {item.executionState === 'SUCCEEDED'
                ? 'CREATED'
                : item.validationState}
            </span>
          </div>
        ))}
      </div>
    </section>
  );
}

function ProtocolStep({
  index,
  label,
  active,
  complete,
}: {
  index: string;
  label: string;
  active?: boolean;
  complete?: boolean;
}) {
  return (
    <li className="flex items-center gap-3">
      <span
        className={cn(
          'flex h-7 w-7 items-center justify-center border font-mono text-[9px]',
          complete
            ? 'border-success/30 text-success'
            : active
              ? 'border-accent/40 text-accent'
              : 'border-border-default text-text-muted'
        )}
      >
        {complete ? <Check size={12} /> : index}
      </span>
      <span
        className={cn(
          'text-[11px]',
          active || complete ? 'text-text-primary' : 'text-text-muted'
        )}
      >
        {label}
      </span>
    </li>
  );
}

function ImportRule({ label, detail }: { label: string; detail: string }) {
  return (
    <div className="bg-surface-2 px-4 py-3">
      <p className="font-mono text-[9px] uppercase tracking-[0.12em] text-text-muted">
        {label}
      </p>
      <p className="mt-1 text-[11px] font-medium text-text-secondary">
        {detail}
      </p>
    </div>
  );
}

function StatusBadge({ state }: { state: EnvironmentImport['state'] }) {
  const success = state === 'VALIDATED' || state === 'COMMITTED';
  return (
    <span
      className={cn(
        'inline-flex h-7 items-center border px-2.5 font-mono text-[9px]',
        success
          ? 'border-success/30 bg-success/8 text-success'
          : 'border-danger/30 bg-danger/8 text-danger'
      )}
    >
      {state}
    </span>
  );
}

function ErrorBanner({ error }: { error: unknown }) {
  const requestId = isSessionApiError(error) ? error.body.requestId : undefined;
  const message =
    typeof error === 'string'
      ? error
      : error instanceof Error
        ? error.message
        : '导入请求失败。';
  return (
    <div
      role="alert"
      className="mt-5 border border-danger/30 bg-danger/8 px-4 py-3 text-[11px] text-danger"
    >
      <p className="flex items-center gap-2 font-medium">
        <AlertTriangle size={13} /> {message}
      </p>
      {requestId && (
        <p className="mt-1 font-mono text-[9px] text-text-muted">
          Request ID: {requestId}
        </p>
      )}
    </div>
  );
}
