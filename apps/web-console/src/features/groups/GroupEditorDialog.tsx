import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import { FolderKanban, LoaderCircle, X } from 'lucide-react';
import { useEffect } from 'react';
import { useForm, type UseFormRegisterReturn } from 'react-hook-form';
import { z } from 'zod';
import { isSessionApiError } from '@/api/session';
import {
  useCreateWorkspaceGroup,
  useUpdateWorkspaceGroup,
} from './groupQueries';
import type { WorkspaceGroupRequest, WorkspaceGroupView } from '@/types/group';

const schema = z.object({
  name: z.string().trim().min(1, '请输入分组名称').max(96),
  description: z.string().trim().max(512).optional(),
  color: z.string().regex(/^#[0-9A-Fa-f]{6}$/, '请输入 6 位 HEX 颜色'),
  defaultOnMaximumReached: z.enum([
    'PAUSE_AGENT',
    'WAIT_SAFE_POINT_MIGRATE',
    'HIBERNATE',
    'TERMINATE_STRICT',
  ]),
  defaultAllowMigration: z.boolean(),
  defaultAllowHibernate: z.boolean(),
});

type FormValues = z.infer<typeof schema>;

const defaults: FormValues = {
  name: '',
  description: '',
  color: '#35D6BE',
  defaultOnMaximumReached: 'PAUSE_AGENT',
  defaultAllowMigration: true,
  defaultAllowHibernate: true,
};

export function GroupEditorDialog({
  open,
  onOpenChange,
  group,
  platformAdmin,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  group?: WorkspaceGroupView;
  platformAdmin: boolean;
}) {
  const create = useCreateWorkspaceGroup();
  const update = useUpdateWorkspaceGroup();
  const mutation = group ? update : create;
  const {
    register,
    handleSubmit,
    reset,
    watch,
    formState: { errors },
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: defaults,
  });

  useEffect(() => {
    if (!open) return;
    reset(
      group
        ? {
            name: group.name,
            description: group.description ?? '',
            color: group.color,
            defaultOnMaximumReached: group.defaultOnMaximumReached,
            defaultAllowMigration: group.defaultAllowMigration,
            defaultAllowHibernate: group.defaultAllowHibernate,
          }
        : defaults
    );
    mutation.reset();
  }, [group, open, reset]); // eslint-disable-line react-hooks/exhaustive-deps

  const setOpen = (next: boolean) => {
    if (!next) mutation.reset();
    onOpenChange(next);
  };

  const submit = handleSubmit(async (values) => {
    const body: WorkspaceGroupRequest = {
      ...values,
      description: values.description || undefined,
      color: values.color.toUpperCase(),
    };
    if (group) {
      await update.mutateAsync({ groupId: group.groupId, body });
    } else {
      await create.mutateAsync(body);
    }
    setOpen(false);
  });

  const strict = watch('defaultOnMaximumReached') === 'TERMINATE_STRICT';
  const requestId = isSessionApiError(mutation.error)
    ? mutation.error.body.requestId
    : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/75 backdrop-blur-[2px]" />
        <Dialog.Content className="fixed left-1/2 top-1/2 z-50 max-h-[calc(100vh-2rem)] w-[calc(100%-2rem)] max-w-[560px] -translate-x-1/2 -translate-y-1/2 overflow-y-auto border border-border-default bg-surface-1 shadow-2xl focus:outline-none">
          <header className="flex items-start justify-between border-b border-border-subtle px-5 py-4">
            <div className="flex gap-3">
              <span className="flex h-9 w-9 items-center justify-center bg-accent-soft text-accent">
                <FolderKanban size={17} />
              </span>
              <div>
                <Dialog.Title className="text-[14px] font-semibold text-text-primary">
                  {group ? '编辑 Workspace 分组' : '创建 Workspace 分组'}
                </Dialog.Title>
                <Dialog.Description className="mt-0.5 text-[11px] text-text-muted">
                  默认策略只在新环境未显式提交资源策略时继承。
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
            <div className="grid gap-4 sm:grid-cols-[1fr_112px]">
              <Field label="分组名称" error={errors.name?.message}>
                <input
                  {...register('name')}
                  className="field-input"
                  placeholder="客户运营"
                  autoFocus
                />
              </Field>
              <Field label="标识色" error={errors.color?.message}>
                <div className="flex gap-2">
                  <input
                    type="color"
                    {...register('color')}
                    className="h-9 w-10 border border-border-subtle bg-surface-2"
                    aria-label="分组标识色"
                  />
                  <input
                    {...register('color')}
                    className="field-input min-w-0 font-mono"
                    aria-label="分组 HEX 颜色"
                  />
                </div>
              </Field>
            </div>
            <Field label="说明（可选）" error={errors.description?.message}>
              <textarea
                {...register('description')}
                className="min-h-20 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 text-[12px] text-text-primary outline-none focus:border-accent"
                placeholder="说明数据归属、用途和运维责任"
              />
            </Field>
            <Field
              label="达到资源上限时"
              error={errors.defaultOnMaximumReached?.message}
            >
              <select
                {...register('defaultOnMaximumReached')}
                className="field-input"
              >
                <option value="PAUSE_AGENT">暂停 Agent，保留浏览器</option>
                <option value="WAIT_SAFE_POINT_MIGRATE">
                  等待安全点并迁移
                </option>
                <option value="HIBERNATE">创建检查点并休眠</option>
                <option value="TERMINATE_STRICT" disabled={!platformAdmin}>
                  严格预算，终止环境
                </option>
              </select>
            </Field>

            {strict && (
              <div
                role="alert"
                className="border border-danger/35 bg-danger/8 px-3 py-2 text-[11px] text-danger"
              >
                严格预算可能终止浏览器并中断登录状态，仅 Platform Admin 可保存。
              </div>
            )}

            <div className="grid gap-3 border border-border-subtle bg-surface-2 p-3 sm:grid-cols-2">
              <Toggle
                label="允许安全点迁移"
                input={register('defaultAllowMigration')}
              />
              <Toggle
                label="允许自动休眠"
                input={register('defaultAllowHibernate')}
              />
            </div>

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

            <footer className="flex justify-end gap-2 border-t border-border-subtle pt-4">
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
                {group ? '保存分组' : '创建分组'}
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
        <span className="mt-1 block text-[11px] text-danger">{error}</span>
      )}
    </label>
  );
}

function Toggle({
  label,
  input,
}: {
  label: string;
  input: UseFormRegisterReturn;
}) {
  return (
    <label className="flex min-h-9 items-center justify-between gap-3 text-[11px] text-text-secondary">
      {label}
      <input type="checkbox" {...input} className="h-4 w-4 accent-accent" />
    </label>
  );
}
