import * as Dialog from '@radix-ui/react-dialog';
import { LoaderCircle, ShieldCheck, Trash2, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { isSessionApiError } from '@/api/session';
import {
  useCreateProxyBinding,
  useDeleteProxyBinding,
  useUpdateProxyBinding,
} from '@/features/proxies/proxyQueries';
import type {
  ProxyBindingRequest,
  ProxyBindingView,
  ProxyProviderView,
} from '@/types/proxy';

export function ProxyBindingEditor({
  open,
  onOpenChange,
  binding,
  provider,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  binding?: ProxyBindingView;
  provider: ProxyProviderView;
}) {
  const create = useCreateProxyBinding();
  const update = useUpdateProxyBinding();
  const remove = useDeleteProxyBinding();
  const mutation = binding ? update : create;
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  const [region, setRegion] = useState('');
  const [credentialRef, setCredentialRef] = useState('');
  const [enabled, setEnabled] = useState(true);
  const [confirmDelete, setConfirmDelete] = useState(false);

  useEffect(() => {
    if (!open) return;
    setName(binding?.name ?? '');
    setDescription(binding?.description ?? '');
    setRegion(binding?.region ?? '');
    setCredentialRef('');
    setEnabled(binding?.enabled ?? true);
    setConfirmDelete(false);
    mutation.reset();
    remove.reset();
    // The mutation object is intentionally excluded: reset must only run when the drawer target changes.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [binding, open]);

  const submit = async (event: React.FormEvent) => {
    event.preventDefault();
    const body: ProxyBindingRequest = {
      name: name.trim(),
      description: description.trim() || undefined,
      providerId: provider.providerId,
      region: region.trim() || undefined,
      expectedExitIp: provider.expectedExitIp,
      credentialRef: credentialRef.trim() || undefined,
      enabled,
      expectedVersion: binding?.version,
    };
    if (binding) {
      await update.mutateAsync({
        bindingProfileId: binding.bindingProfileId,
        body,
      });
    } else {
      await create.mutateAsync(body);
    }
    onOpenChange(false);
  };

  const credentialMissing = !binding && !credentialRef.trim();
  const activeError = mutation.error ?? remove.error;
  const requestId = isSessionApiError(activeError)
    ? activeError.body.requestId
    : undefined;
  const rejectionReason =
    isSessionApiError(activeError) &&
    typeof activeError.body.details?.reason === 'string'
      ? activeError.body.details.reason
      : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={onOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[520px] flex-col border-l border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex min-h-[72px] items-center justify-between border-b border-border-subtle px-5 sm:px-6">
            <div>
              <p className="font-mono text-[10px] uppercase tracking-[0.16em] text-accent">
                Managed network egress
              </p>
              <Dialog.Title className="mt-1 text-[17px] font-semibold text-text-primary">
                {binding ? '编辑 Proxy Binding' : '新建 Proxy Binding'}
              </Dialog.Title>
            </div>
            <Dialog.Close
              className="flex h-9 w-9 items-center justify-center border border-border-subtle text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭"
            >
              <X size={16} />
            </Dialog.Close>
          </header>

          <form onSubmit={submit} className="flex min-h-0 flex-1 flex-col">
            <div className="min-h-0 flex-1 space-y-5 overflow-y-auto p-5 sm:p-6">
              <div className="border border-accent/30 bg-accent-soft p-4">
                <div className="flex items-start gap-3">
                  <ShieldCheck
                    size={17}
                    className="mt-0.5 shrink-0 text-accent"
                  />
                  <div>
                    <p className="text-[12px] font-semibold text-text-primary">
                      配置可复用，运行分配始终隔离
                    </p>
                    <p className="mt-1 text-[11px] leading-5 text-text-muted">
                      创建环境时会固化此配置的版本；每个 Session 仍会获得独立
                      allocation，并由 Node 验证真实出口。
                    </p>
                  </div>
                </div>
              </div>

              <Field label="名称" required>
                <input
                  className="field-input"
                  value={name}
                  onChange={(event) => setName(event.target.value)}
                  maxLength={96}
                  required
                  autoFocus
                />
              </Field>

              <Field label="说明">
                <textarea
                  className="field-input min-h-24 resize-y py-2"
                  value={description}
                  onChange={(event) => setDescription(event.target.value)}
                  maxLength={512}
                />
              </Field>

              <div className="grid gap-3 sm:grid-cols-2">
                <ReadOnlyField label="Provider" value={provider.providerId} />
                <ReadOnlyField
                  label="Expected exit"
                  value={provider.expectedExitIp || '未配置'}
                />
              </div>

              <Field
                label="限定区域"
                hint="留空表示所有区域；指定后，创建环境时必须选择同一区域。"
              >
                <input
                  className="field-input font-mono"
                  value={region}
                  onChange={(event) => setRegion(event.target.value)}
                  placeholder="例如 singapore"
                  pattern="[a-z0-9-]{1,32}"
                  maxLength={32}
                />
              </Field>

              <Field
                label="Secret 引用"
                required={!binding}
                hint={
                  binding
                    ? '服务端不会回传现有引用；留空表示保留当前值。'
                    : '只保存 Secret Manager 引用，不在此处提交用户名或密码。'
                }
              >
                <input
                  className="field-input font-mono"
                  value={credentialRef}
                  onChange={(event) => setCredentialRef(event.target.value)}
                  placeholder="vault://tenant/proxy/primary"
                  pattern="(vault|secret|aws-sm|gcp-sm|azure-kv)://\\S+"
                  maxLength={512}
                  required={!binding}
                  autoComplete="off"
                />
              </Field>

              <label className="flex cursor-pointer items-start gap-3 border border-border-subtle bg-surface-2 p-4">
                <input
                  type="checkbox"
                  checked={enabled}
                  onChange={(event) => setEnabled(event.target.checked)}
                  className="mt-0.5 h-4 w-4 accent-[var(--color-accent)]"
                />
                <span>
                  <span className="block text-[12px] font-medium text-text-primary">
                    允许新 Session 使用
                  </span>
                  <span className="mt-1 block text-[10px] leading-4 text-text-muted">
                    禁用不会改变既有 Session 的不可变快照，但会阻止新的绑定。
                  </span>
                </span>
              </label>

              {activeError && (
                <div
                  role="alert"
                  className="border border-danger/30 bg-danger/10 p-3 text-[11px] text-danger"
                >
                  {activeError instanceof Error
                    ? activeError.message
                    : 'Proxy Binding 保存失败'}
                  {rejectionReason && (
                    <span className="mt-1 block font-mono">
                      {rejectionReason}
                    </span>
                  )}
                  {requestId && (
                    <span className="mt-1 block font-mono text-[10px] opacity-80">
                      Request {requestId}
                    </span>
                  )}
                </div>
              )}
            </div>

            <footer className="flex items-center justify-between gap-3 border-t border-border-subtle bg-surface-2 px-5 py-4 sm:px-6">
              <div>
                {binding && (
                  <button
                    type="button"
                    className="inline-flex h-9 items-center gap-2 border border-danger/30 px-3 text-[11px] font-medium text-danger hover:bg-danger/10 disabled:opacity-50"
                    disabled={remove.isPending || mutation.isPending}
                    onClick={async () => {
                      if (!confirmDelete) {
                        setConfirmDelete(true);
                        return;
                      }
                      await remove.mutateAsync(binding.bindingProfileId);
                      onOpenChange(false);
                    }}
                  >
                    {remove.isPending ? (
                      <LoaderCircle size={13} className="animate-spin" />
                    ) : (
                      <Trash2 size={13} />
                    )}
                    {confirmDelete ? '再次点击确认删除' : '删除'}
                  </button>
                )}
              </div>
              <div className="flex items-center gap-2">
                <Dialog.Close className="inline-flex h-9 items-center justify-center border border-border-default bg-surface-1 px-4 text-[11px] font-medium text-text-secondary transition-colors hover:border-accent/40 hover:text-text-primary">
                  取消
                </Dialog.Close>
                <button
                  type="submit"
                  className="inline-flex h-9 min-w-28 items-center justify-center gap-2 bg-accent px-4 text-[11px] font-semibold text-canvas transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-40"
                  disabled={
                    mutation.isPending ||
                    remove.isPending ||
                    !name.trim() ||
                    credentialMissing ||
                    !provider.expectedExitIp
                  }
                >
                  {mutation.isPending && (
                    <LoaderCircle size={14} className="animate-spin" />
                  )}
                  {binding ? '保存变更' : '创建 Binding'}
                </button>
              </div>
            </footer>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({
  label,
  hint,
  required,
  children,
}: {
  label: string;
  hint?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-medium text-text-secondary">
        {label}
        {required && <span className="ml-1 text-danger">*</span>}
      </span>
      {children}
      {hint && (
        <span className="mt-1.5 block text-[10px] leading-4 text-text-muted">
          {hint}
        </span>
      )}
    </label>
  );
}

function ReadOnlyField({ label, value }: { label: string; value: string }) {
  return (
    <div className="border border-border-subtle bg-surface-2 p-3">
      <p className="text-[9px] uppercase tracking-[0.12em] text-text-muted">
        {label}
      </p>
      <p className="mt-1 truncate font-mono text-[11px] text-text-secondary">
        {value}
      </p>
    </div>
  );
}
