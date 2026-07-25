import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import { Database, LoaderCircle, X } from 'lucide-react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { isSessionApiError } from '@/api/session';
import { useCreateProfile } from '@/features/profiles/profileQueries';

const schema = z.object({
  profileId: z
    .string()
    .trim()
    .regex(/^[a-zA-Z0-9_-]{1,128}$/, '仅支持字母、数字、下划线和连字符'),
  name: z.string().trim().min(1, '请输入名称').max(128),
  description: z.string().trim().max(1024).optional(),
});

type FormValues = z.infer<typeof schema>;

export function CreateProfileDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const mutation = useCreateProfile();
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: { profileId: '', name: '', description: '' },
  });

  const setOpen = (next: boolean) => {
    if (!next) {
      reset();
      mutation.reset();
    }
    onOpenChange(next);
  };

  const submit = handleSubmit(async (values) => {
    await mutation.mutateAsync({
      ...values,
      description: values.description || undefined,
    });
    setOpen(false);
  });

  const requestId = isSessionApiError(mutation.error)
    ? mutation.error.body.requestId
    : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/75 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 w-[calc(100%-2rem)] max-w-[480px] -translate-x-1/2 -translate-y-1/2 border border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <div className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
            <div className="flex gap-3">
              <span className="flex h-9 w-9 items-center justify-center bg-accent-soft text-accent">
                <Database size={17} />
              </span>
              <div>
                <Dialog.Title className="text-[14px] font-semibold text-text-primary">
                  创建持久化 Profile
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 text-[11px] text-text-muted">
                  Core 数据会在 Runtime 安全停止后形成完整性检查点。
                </Dialog.Description>
              </div>
            </div>
            <Dialog.Close
              className="flex h-8 w-8 items-center justify-center text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭"
            >
              <X size={15} />
            </Dialog.Close>
          </div>

          <form onSubmit={submit} className="space-y-4 p-5">
            <Field
              label="Profile ID"
              error={errors.profileId?.message}
              input={
                <input
                  {...register('profileId')}
                  className="field-input font-mono"
                  placeholder="profile_customer_ops"
                  autoFocus
                />
              }
            />
            <Field
              label="显示名称"
              error={errors.name?.message}
              input={
                <input
                  {...register('name')}
                  className="field-input"
                  placeholder="Customer Operations"
                />
              }
            />
            <Field
              label="说明（可选）"
              error={errors.description?.message}
              input={
                <textarea
                  {...register('description')}
                  className="min-h-20 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 text-[12px] text-text-primary outline-none transition-colors placeholder:text-text-muted focus:border-accent"
                  placeholder="用途、数据归属和恢复要求"
                />
              }
            />

            {mutation.error && (
              <div
                role="alert"
                className="border border-danger/30 bg-danger/8 px-3 py-2 text-[11px] text-danger"
              >
                {mutation.error.message}
                {requestId && (
                  <span className="ml-2 font-mono text-text-muted">
                    {requestId}
                  </span>
                )}
              </div>
            )}

            <div className="flex justify-end gap-2 border-t border-border-subtle pt-4">
              <button
                type="button"
                onClick={() => setOpen(false)}
                className="h-8 border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2"
              >
                取消
              </button>
              <button
                type="submit"
                disabled={mutation.isPending}
                className="inline-flex h-8 items-center gap-2 bg-accent px-3 text-[12px] font-semibold text-canvas disabled:opacity-50"
              >
                {mutation.isPending && (
                  <LoaderCircle size={13} className="animate-spin" />
                )}
                创建 Profile
              </button>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({
  label,
  error,
  input,
}: {
  label: string;
  error?: string;
  input: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[11px] font-medium text-text-secondary">
        {label}
      </span>
      {input}
      {error && (
        <span className="mt-1 block text-[11px] text-danger">{error}</span>
      )}
    </label>
  );
}
