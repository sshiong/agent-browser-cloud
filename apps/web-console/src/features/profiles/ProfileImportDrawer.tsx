import * as Dialog from '@radix-ui/react-dialog';
import { sha256 } from '@noble/hashes/sha2.js';
import { bytesToHex } from '@noble/hashes/utils.js';
import {
  Archive,
  CheckCircle2,
  FileCheck2,
  HardDriveUpload,
  History,
  LoaderCircle,
  ShieldCheck,
  X,
} from 'lucide-react';
import { useMemo, useRef, useState } from 'react';
import { isSessionApiError } from '@/api/session';
import { useRuntimeBuilds } from '@/features/security/platformQueries';
import { cn } from '@/shared/lib/utils';
import type { ProfileImportView } from '@/types/profile';
import {
  useImportProfileCheckpoint,
  useProfileImports,
} from './profileQueries';

const MAX_ARCHIVE_BYTES = 256 * 1024 * 1024;

export function ProfileImportDrawer({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [profileId, setProfileId] = useState('');
  const [profileName, setProfileName] = useState('');
  const [description, setDescription] = useState('');
  const [runtimeBuildId, setRuntimeBuildId] = useState('');
  const [archive, setArchive] = useState<File>();
  const [archiveSha256, setArchiveSha256] = useState('');
  const [hashProgress, setHashProgress] = useState(0);
  const [hashing, setHashing] = useState(false);
  const [localError, setLocalError] = useState('');
  const [result, setResult] = useState<ProfileImportView>();
  const runtimes = useRuntimeBuilds();
  const imports = useProfileImports(open);
  const mutation = useImportProfileCheckpoint();
  const stableRuntimes = useMemo(
    () =>
      (runtimes.data?.items ?? []).filter(
        (runtime) =>
          runtime.releaseChannel === 'STABLE' &&
          runtime.regressionStatus === 'STABLE'
      ),
    [runtimes.data?.items]
  );
  const busy = hashing || mutation.isPending;
  const canSubmit =
    !busy &&
    Boolean(
      profileId.trim() &&
      profileName.trim() &&
      runtimeBuildId &&
      archive &&
      archiveSha256
    );

  const reset = () => {
    setProfileId('');
    setProfileName('');
    setDescription('');
    setRuntimeBuildId('');
    setArchive(undefined);
    setArchiveSha256('');
    setHashProgress(0);
    setHashing(false);
    setLocalError('');
    setResult(undefined);
    mutation.reset();
    if (fileRef.current) fileRef.current.value = '';
  };

  const handleOpenChange = (next: boolean) => {
    if (busy) return;
    if (!next) reset();
    onOpenChange(next);
  };

  const selectArchive = async (file?: File) => {
    setLocalError('');
    setResult(undefined);
    mutation.reset();
    setArchive(undefined);
    setArchiveSha256('');
    setHashProgress(0);
    if (!file) return;
    if (file.size === 0 || file.size > MAX_ARCHIVE_BYTES) {
      setLocalError('Checkpoint 归档必须大于 0，且不能超过 256 MiB。');
      return;
    }
    if (!file.name.toLowerCase().endsWith('.tar.zst')) {
      setLocalError('请选择由 Agent Browser Cloud 导出的 .tar.zst 归档。');
      return;
    }
    setArchive(file);
    setHashing(true);
    try {
      setArchiveSha256(
        await hashFile(file, (processed) =>
          setHashProgress(Math.round((processed / file.size) * 100))
        )
      );
      setHashProgress(100);
    } catch {
      setArchive(undefined);
      setLocalError('浏览器无法读取归档，请重新选择文件。');
    } finally {
      setHashing(false);
    }
  };

  const submit = async () => {
    if (!canSubmit || !archive) return;
    setLocalError('');
    try {
      setResult(
        await mutation.mutateAsync({
          profileId: profileId.trim(),
          profileName: profileName.trim(),
          profileDescription: description.trim() || undefined,
          runtimeBuildId,
          archiveSha256,
          archive,
        })
      );
    } catch {
      // The shared error panel renders the formal API envelope.
    }
  };

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[920px] flex-col border-l border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex min-h-[76px] items-center justify-between border-b border-border-subtle px-5 sm:px-7">
            <div>
              <p className="font-mono text-[9px] uppercase tracking-[0.18em] text-accent">
                Verified checkpoint ingress
              </p>
              <Dialog.Title className="mt-1 text-[17px] font-semibold text-text-primary">
                导入 Profile Checkpoint
              </Dialog.Title>
              <Dialog.Description className="mt-0.5 text-[11px] text-text-muted">
                有界上传、三重 SHA-256 校验、Storage Helper
                归一化与对象存储提交。
              </Dialog.Description>
            </div>
            <Dialog.Close
              aria-label="关闭 Profile 导入"
              disabled={busy}
              className="flex h-9 w-9 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary disabled:opacity-40"
            >
              <X size={17} />
            </Dialog.Close>
          </header>

          <div className="grid min-h-0 flex-1 lg:grid-cols-[230px_1fr]">
            <aside className="border-b border-border-subtle bg-surface-2/55 p-5 lg:border-b-0 lg:border-r">
              <p className="font-mono text-[9px] uppercase tracking-[0.16em] text-text-muted">
                Commit path
              </p>
              <ol className="mt-5 space-y-5">
                <ProtocolStep
                  index="01"
                  label="本地计算 SHA-256"
                  active={!result && !mutation.isPending}
                  complete={Boolean(archiveSha256)}
                />
                <ProtocolStep
                  index="02"
                  label="mTLS 流式上传"
                  active={mutation.isPending}
                  complete={Boolean(result)}
                />
                <ProtocolStep
                  index="03"
                  label="Helper 校验与提交"
                  active={result?.state === 'COMMITTED'}
                  complete={result?.state === 'COMMITTED'}
                />
              </ol>

              <div className="mt-8 border-t border-border-subtle pt-5">
                <p className="flex items-center gap-2 text-[11px] font-medium text-text-secondary">
                  <History size={13} /> 最近导入
                </p>
                <div className="mt-3 space-y-2">
                  {imports.data?.items.slice(0, 5).map((item) => (
                    <div
                      key={item.importId}
                      className="border-l border-border-default pl-3"
                    >
                      <p className="truncate text-[11px] text-text-secondary">
                        {item.profileName}
                      </p>
                      <p className="mt-0.5 font-mono text-[9px] text-text-muted">
                        {item.state} · {item.operationId.slice(0, 12)}…
                      </p>
                    </div>
                  ))}
                  {!imports.isLoading && !imports.data?.items.length && (
                    <p className="text-[10px] leading-4 text-text-muted">
                      当前操作者还没有导入记录。
                    </p>
                  )}
                </div>
              </div>
            </aside>

            <main className="min-h-0 overflow-y-auto p-5 sm:p-7">
              {result ? (
                <ImportResult result={result} />
              ) : (
                <div className="space-y-6">
                  <section>
                    <SectionLabel index="01" label="目标 Profile" />
                    <div className="mt-3 grid gap-3 sm:grid-cols-2">
                      <Field label="Profile ID" required>
                        <input
                          value={profileId}
                          onChange={(event) => setProfileId(event.target.value)}
                          className="field-input font-mono"
                          placeholder="profile_crm_sg"
                          maxLength={128}
                        />
                      </Field>
                      <Field label="显示名称" required>
                        <input
                          value={profileName}
                          onChange={(event) =>
                            setProfileName(event.target.value)
                          }
                          className="field-input"
                          placeholder="CRM Singapore"
                          maxLength={128}
                        />
                      </Field>
                    </div>
                    <Field label="说明">
                      <textarea
                        value={description}
                        onChange={(event) => setDescription(event.target.value)}
                        className="field-input mt-3 min-h-20 resize-y py-2"
                        placeholder="导入来源与用途，不填写敏感信息"
                        maxLength={1024}
                      />
                    </Field>
                  </section>

                  <section className="border-t border-border-subtle pt-5">
                    <SectionLabel index="02" label="兼容 Runtime" />
                    <Field label="已批准 Runtime Build" required>
                      <select
                        value={runtimeBuildId}
                        onChange={(event) =>
                          setRuntimeBuildId(event.target.value)
                        }
                        className="field-input mt-3"
                      >
                        <option value="">选择 STABLE Runtime</option>
                        {stableRuntimes.map((runtime) => (
                          <option key={runtime.buildId} value={runtime.buildId}>
                            {runtime.buildId} · {runtime.version} ·{' '}
                            {runtime.platform}
                          </option>
                        ))}
                      </select>
                    </Field>
                    {!runtimes.isLoading && stableRuntimes.length === 0 && (
                      <p className="mt-2 text-[10px] text-danger">
                        当前没有通过发布 Gate 的 STABLE Runtime。
                      </p>
                    )}
                  </section>

                  <section className="border-t border-border-subtle pt-5">
                    <SectionLabel index="03" label="Checkpoint 归档" />
                    <button
                      type="button"
                      disabled={busy}
                      onClick={() => fileRef.current?.click()}
                      onDragOver={(event) => event.preventDefault()}
                      onDrop={(event) => {
                        event.preventDefault();
                        void selectArchive(event.dataTransfer.files[0]);
                      }}
                      className={cn(
                        'mt-3 flex min-h-40 w-full items-center gap-5 border border-dashed px-5 text-left transition-colors',
                        archive
                          ? 'border-accent/45 bg-accent-soft/20'
                          : 'border-border-default bg-surface-2/35 hover:border-accent/50'
                      )}
                    >
                      <span className="flex h-11 w-11 shrink-0 items-center justify-center border border-accent/25 bg-accent-soft text-accent">
                        {hashing ? (
                          <LoaderCircle size={19} className="animate-spin" />
                        ) : (
                          <Archive size={19} />
                        )}
                      </span>
                      <span className="min-w-0">
                        <span className="block truncate text-[13px] font-medium text-text-primary">
                          {archive?.name || '选择或拖入 .tar.zst'}
                        </span>
                        <span className="mt-1 block text-[10px] leading-4 text-text-muted">
                          最大 256
                          MiB。Cache、Crashpad、符号链接和归档外路径会被 Storage
                          Helper 丢弃或拒绝。
                        </span>
                        {archive && (
                          <span className="mt-2 block font-mono text-[9px] text-text-secondary">
                            {formatBytes(archive.size)} · SHA-256{' '}
                            {archiveSha256
                              ? `${archiveSha256.slice(0, 18)}…`
                              : `${hashProgress}%`}
                          </span>
                        )}
                      </span>
                    </button>
                    <input
                      ref={fileRef}
                      type="file"
                      accept=".tar.zst,application/zstd,application/octet-stream"
                      className="sr-only"
                      onChange={(event) =>
                        void selectArchive(event.target.files?.[0])
                      }
                    />
                  </section>

                  <div className="grid gap-px bg-border-subtle sm:grid-cols-3">
                    <Boundary label="Control Plane" value="不入业务存储" />
                    <Boundary label="Browser Node" value="受限 staging" />
                    <Boundary label="Storage Helper" value="对象提交权威" />
                  </div>
                </div>
              )}

              {(localError || mutation.error) && (
                <ErrorPanel error={localError || mutation.error} />
              )}
            </main>
          </div>

          <footer className="flex min-h-[70px] flex-wrap items-center justify-between gap-3 border-t border-border-subtle bg-surface-2/55 px-5 sm:px-7">
            <p className="max-w-[560px] text-[10px] leading-4 text-text-muted">
              {result
                ? `Request ${result.requestId} · Operation ${result.operationId}`
                : '只有对象存储 COMMITTED 后，Profile 元数据才会进入 PostgreSQL。'}
            </p>
            {result ? (
              <Dialog.Close className="h-9 bg-accent px-4 text-[12px] font-semibold text-canvas">
                完成
              </Dialog.Close>
            ) : (
              <button
                type="button"
                disabled={!canSubmit}
                onClick={() => void submit()}
                className="inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-45"
              >
                {mutation.isPending ? (
                  <LoaderCircle size={14} className="animate-spin" />
                ) : (
                  <HardDriveUpload size={14} />
                )}
                {mutation.isPending ? '验证并提交中' : '导入 Checkpoint'}
              </button>
            )}
          </footer>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

async function hashFile(file: File, onProgress: (bytes: number) => void) {
  const hash = sha256.create();
  const reader = file.stream().getReader();
  let processed = 0;
  let complete = false;
  while (!complete) {
    const { done, value } = await reader.read();
    if (done) {
      complete = true;
      continue;
    }
    hash.update(value);
    processed += value.byteLength;
    onProgress(processed);
  }
  return bytesToHex(hash.digest());
}

function ImportResult({ result }: { result: ProfileImportView }) {
  return (
    <section>
      <div className="flex items-start justify-between gap-4 border-b border-border-subtle pb-5">
        <div>
          <p className="font-mono text-[9px] uppercase tracking-[0.16em] text-accent">
            Object storage committed
          </p>
          <h2 className="mt-1 text-[17px] font-semibold text-text-primary">
            {result.profileName}
          </h2>
          <p className="mt-1 font-mono text-[10px] text-text-muted">
            {result.profileId}
          </p>
        </div>
        <span className="inline-flex items-center gap-1.5 border border-success/30 bg-success/10 px-2 py-1 text-[10px] font-semibold text-success">
          <CheckCircle2 size={12} /> COMMITTED
        </span>
      </div>
      <div className="mt-5 grid gap-px bg-border-subtle sm:grid-cols-2">
        <ResultValue label="Checkpoint" value={result.checkpointId} mono />
        <ResultValue label="Runtime" value={result.runtimeBuildId} mono />
        <ResultValue
          label="Core / 文件"
          value={`${formatBytes(result.coreSizeBytes ?? 0)} · ${(result.checkpointFileCount ?? 0).toLocaleString()} files`}
        />
        <ResultValue label="Node" value={result.nodeId ?? 'UNKNOWN'} mono />
        <ResultValue label="Operation ID" value={result.operationId} mono />
        <ResultValue label="Request ID" value={result.requestId} mono />
      </div>
      <div className="mt-5 flex gap-3 border border-success/25 bg-success/5 p-4">
        <ShieldCheck size={16} className="mt-0.5 shrink-0 text-success" />
        <p className="text-[11px] leading-5 text-text-secondary">
          归档身份已重建为当前 Tenant/Profile，文件清单和哈希已重新计算。后续
          Session 会从该 Checkpoint 恢复，不会继承源归档中的租户标识。
        </p>
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
  active: boolean;
  complete: boolean;
}) {
  return (
    <li className="flex items-center gap-3">
      <span
        className={cn(
          'flex h-7 w-7 items-center justify-center border font-mono text-[9px]',
          complete
            ? 'border-success/35 bg-success/10 text-success'
            : active
              ? 'border-accent/40 bg-accent-soft text-accent'
              : 'border-border-default text-text-muted'
        )}
      >
        {complete ? <FileCheck2 size={12} /> : index}
      </span>
      <span
        className={cn(
          'text-[11px]',
          active || complete ? 'text-text-secondary' : 'text-text-muted'
        )}
      >
        {label}
      </span>
    </li>
  );
}

function SectionLabel({ index, label }: { index: string; label: string }) {
  return (
    <p className="font-mono text-[9px] uppercase tracking-[0.16em] text-text-muted">
      {index} / {label}
    </p>
  );
}

function Field({
  label,
  required,
  children,
}: {
  label: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-[10px] font-medium text-text-secondary">
        {label}
        {required && <span className="ml-1 text-accent">*</span>}
      </span>
      <span className="mt-1.5 block">{children}</span>
    </label>
  );
}

function Boundary({ label, value }: { label: string; value: string }) {
  return (
    <div className="bg-surface-2 px-3 py-3">
      <p className="font-mono text-[8px] uppercase tracking-[0.14em] text-text-muted">
        {label}
      </p>
      <p className="mt-1 text-[10px] text-text-secondary">{value}</p>
    </div>
  );
}

function ResultValue({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="min-w-0 bg-surface-2 px-4 py-3">
      <p className="text-[9px] uppercase tracking-[0.12em] text-text-muted">
        {label}
      </p>
      <p
        className={cn(
          'mt-1 truncate text-[11px] text-text-secondary',
          mono && 'font-mono'
        )}
        title={value}
      >
        {value}
      </p>
    </div>
  );
}

function ErrorPanel({ error }: { error: unknown }) {
  const apiError = isSessionApiError(error) ? error : null;
  const details = apiError?.body.details;
  const reason =
    details && typeof details.reason === 'string' ? details.reason : null;
  return (
    <div className="mt-5 border border-danger/30 bg-danger/8 px-4 py-3">
      <p className="text-[11px] font-semibold text-danger">
        Profile 导入未完成
      </p>
      <p className="mt-1 text-[10px] leading-4 text-text-secondary">
        {typeof error === 'string'
          ? error
          : reason || apiError?.body.message || '无法提交 Checkpoint 归档。'}
      </p>
      {apiError?.body.requestId && (
        <p className="mt-2 font-mono text-[9px] text-text-muted">
          Request {apiError.body.requestId}
        </p>
      )}
    </div>
  );
}

function formatBytes(value: number) {
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KiB`;
  return `${(value / (1024 * 1024)).toFixed(1)} MiB`;
}
