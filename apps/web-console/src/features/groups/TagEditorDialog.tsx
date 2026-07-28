import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import { LoaderCircle, Tag, X } from 'lucide-react';
import { useEffect } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { isSessionApiError } from '@/api/session';
import { useCreateWorkspaceTag, useUpdateWorkspaceTag } from './tagQueries';
import type { WorkspaceTagRequest, WorkspaceTagView } from '@/types/tag';

const schema = z.object({
  name: z.string().trim().min(1, '请输入标签名称').max(32),
  description: z.string().trim().max(256).optional(),
  color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, '请输入 6 位 HEX 颜色'),
});

type FormValues = z.infer<typeof schema>;

const defaults: FormValues = {
  name: '',
  description: '',
  color: '#718096',
};

export function TagEditorDialog({
  open,
  onOpenChange,
  tag,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  tag?: WorkspaceTagView;
}) {
  const create = useCreateWorkspaceTag();
  const update = useUpdateWorkspaceTag();
  const mutation = tag ? update : create;
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: defaults,
  });

  useEffect(() => {
    if (!open) return;
    reset(
      tag
        ? {
            name: tag.name,
            description: tag.description ?? '',
            color: tag.color,
          }
        : defaults
    );
    mutation.reset();
  }, [open, reset, tag]); // eslint-disable-line react-hooks/exhaustive-deps

  const setOpen = (next: boolean) => {
    if (!next) mutation.reset();
    onOpenChange(next);
  };

  const submit = handleSubmit(async (values) => {
    const body: WorkspaceTagRequest = {
      name: values.name,
      description: values.description || undefined,
      color: values.color.toUpperCase(),
    };
    if (tag) {
      await update.mutateAsync({ tagId: tag.tagId, body });
    } else {
      await create.mutateAsync(body);
    }
    setOpen(false);
  });

  const requestId = isSessionApiError(mutation.error)
    ? mutation.error.body.requestId
    : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/75 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-[500px] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
            <div className="flex gap-3">
              <span className="flex h-9 w-9 items-center justify-center bg-accent-soft text-accent">
                <Tag size={17} />
              </span>
              <div>
                <Dialog.Title className="text-[14px] font-semibold text-text-primary">
                  {tag ? '编辑 Workspace 标签' : '创建 Workspace 标签'}
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 text-[11px] text-text-muted">
                  标签是租户级权威实体，可复用于多个环境。
                </Dialog.Description>
              </div>
            </div>
            <Dialog.Close
              className="flex h-8 w-8 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭"
            >
              <X size={15} />
            </Dialog.Close>
          </header>

          <form onSubmit={submit} className="space-y-4 p-5">
            <div className="grid gap-4 sm:grid-cols-[1fr_128px]">
              <Field label="标签名称" error={errors.name?.message}>
                <input
                  {...register('name')}
                  className="field-input"
                  placeholder="Production"
                  autoFocus
                />
              </Field>
              <Field label="标识色" error={errors.color?.message}>
                <div className="flex gap-2">
                  <input
                    type="color"
                    {...register('color')}
                    className="h-9 w-10 border border-border-subtle bg-surface-2"
                    aria-label="标签标识色"
                  />
                  <input
                    {...register('color')}
                    className="field-input min-w-0 font-mono"
                    aria-label="标签 HEX 颜色"
                  />
                </div>
              </Field>
            </div>
            <Field label="说明（可选）" error={errors.description?.message}>
              <textarea
                {...register('description')}
                className="min-h-20 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 text-[12px] text-text-primary outline-none focus:border-accent"
                placeholder="说明标签用途和运维含义"
              />
            </Field>

            {mutation.error && (
              <p role="alert" className="text-[11px] text-danger">
                {mutation.error.message}
                {requestId ? ` · Request ${requestId}` : ''}
              </p>
            )}

            <footer className="flex justify-end gap-2 border-t border-border-subtle pt-4">
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="h-9 border border-border-subtle px-4 text-[12px] text-text-secondary hover:bg-surface-2"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={mutation.isPending}
                className="inline-flex h-9 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas disabled:opacity-50"
              >
                {mutation.isPending && (
                  <LoaderCircle size={13} className="animate-spin" />
                )}
                {tag ? '保存标签' : '创建标签'}
              </button>
            </footer>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({
  label,
  error,
  children,
}: {
  label: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-medium text-text-secondary">
        {label}
      </span>
      {children}
      {error && (
        <span className="mt-1 block text-[10px] text-danger">{error}</span>
      )}
    </label>
  );
}
