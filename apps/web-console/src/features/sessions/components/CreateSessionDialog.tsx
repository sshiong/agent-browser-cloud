import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, ArrowRight, Check, LoaderCircle, X } from 'lucide-react';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { z } from 'zod';
import { currentTenantId } from '@/api/session';
import { isSessionApiError } from '@/api/session';
import { useCreateSession } from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';

const schema = z.object({
  name: z
    .string()
    .trim()
    .min(1, '请输入环境名称')
    .max(128, '环境名称不能超过 128 个字符'),
  profileId: z.string().trim().min(1, '请输入 Profile ID').max(128),
  region: z
    .string()
    .trim()
    .regex(/^[a-z0-9-]{1,32}$/, '仅支持小写字母、数字和连字符'),
  resourceClass: z.enum(['L0', 'L1', 'L2', 'L3', 'L4', 'L5']),
});

type FormValues = z.infer<typeof schema>;

const resourceOptions = [
  { value: 'L1', label: 'L1 · Lite', description: '轻量只读与低频任务' },
  { value: 'L2', label: 'L2 · Standard', description: '标准 Agent 自动化' },
  { value: 'L3', label: 'L3 · Interactive', description: '远程桌面与人工接管' },
] as const;

export function CreateSessionDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const createMutation = useCreateSession();
  const [step, setStep] = useState<1 | 2>(1);
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    trigger,
    watch,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '',
      profileId: 'profile-local-default',
      region: 'local',
      resourceClass: 'L2',
    },
  });

  const values = watch();

  const resetFlow = () => {
    setStep(1);
    createMutation.reset();
    reset();
  };

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) resetFlow();
    onOpenChange(nextOpen);
  };

  const advance = async () => {
    if (await trigger()) setStep(2);
  };

  const submit = handleSubmit(async (form) => {
    const result = await createMutation.mutateAsync({
      idempotencyKey: crypto.randomUUID(),
      request: {
        tenantId: currentTenantId(),
        profileId: form.profileId,
        region: form.region,
        resourceClass: form.resourceClass,
        metadata: { displayName: form.name },
      },
    });
    resetFlow();
    navigate(`/environments/${result.sessionId}`, { replace: true });
  });

  const requestId = isSessionApiError(createMutation.error)
    ? createMutation.error.body.requestId
    : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/75 backdrop-blur-[2px]" />
        <Dialog.Content
          className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[520px] flex-col border-l border-border-default bg-surface-1 shadow-2xl focus:outline-none"
          aria-describedby="create-session-description"
        >
          <div className="flex h-16 items-center justify-between border-b border-border-subtle px-6">
            <div>
              <Dialog.Title className="text-[15px] font-semibold text-text-primary">
                新建浏览器环境
              </Dialog.Title>
              <Dialog.Description
                id="create-session-description"
                className="mt-0.5 text-[11px] text-text-muted"
              >
                创建真实 Session；Runtime、Proxy 与扩展配置将在后续契约中开放。
              </Dialog.Description>
            </div>
            <Dialog.Close
              className="flex h-8 w-8 items-center justify-center rounded-md text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭创建环境面板"
            >
              <X size={16} />
            </Dialog.Close>
          </div>

          <div className="border-b border-border-subtle px-6 py-4">
            <ol className="grid grid-cols-2 gap-3" aria-label="创建步骤">
              {[
                { number: 1, label: '基础配置' },
                { number: 2, label: '确认创建' },
              ].map((item) => (
                <li
                  key={item.number}
                  className={cn(
                    'flex items-center gap-2 border-t-2 pt-2 text-[11px]',
                    step >= item.number
                      ? 'border-accent text-text-primary'
                      : 'border-border-subtle text-text-muted'
                  )}
                >
                  <span
                    className={cn(
                      'flex h-5 w-5 items-center justify-center rounded-full',
                      step > item.number
                        ? 'bg-accent text-canvas'
                        : step === item.number
                          ? 'bg-accent-soft text-accent'
                          : 'bg-surface-3'
                    )}
                  >
                    {step > item.number ? <Check size={11} /> : item.number}
                  </span>
                  {item.label}
                </li>
              ))}
            </ol>
          </div>

          <form
            onSubmit={(event) => event.preventDefault()}
            className="flex min-h-0 flex-1 flex-col"
          >
            <div className="flex-1 overflow-y-auto p-6">
              {step === 1 ? (
                <div className="space-y-5">
                  <Field
                    label="环境名称"
                    error={errors.name?.message}
                    hint="作为 Session metadata 保存，不影响服务端生成的 Session ID。"
                  >
                    <input
                      {...register('name')}
                      autoFocus
                      placeholder="例如：CRM Singapore"
                      className="field-input"
                    />
                  </Field>

                  <Field label="Profile ID" error={errors.profileId?.message}>
                    <input
                      {...register('profileId')}
                      spellCheck={false}
                      className="field-input font-mono"
                    />
                  </Field>

                  <Field
                    label="部署区域"
                    error={errors.region?.message}
                    hint="当前本地 Control Plane 默认使用 local。"
                  >
                    <input
                      {...register('region')}
                      spellCheck={false}
                      className="field-input font-mono"
                    />
                  </Field>

                  <fieldset>
                    <legend className="mb-2 text-[12px] font-medium text-text-primary">
                      资源等级
                    </legend>
                    <div className="grid grid-cols-3 gap-2">
                      {resourceOptions.map((option) => (
                        <label
                          key={option.value}
                          className="cursor-pointer rounded-[8px] border border-border-subtle bg-surface-2 p-3 transition-colors has-[:checked]:border-accent/60 has-[:checked]:bg-accent-soft"
                        >
                          <input
                            type="radio"
                            value={option.value}
                            {...register('resourceClass')}
                            className="sr-only"
                          />
                          <span className="block text-[12px] font-medium text-text-primary">
                            {option.label}
                          </span>
                          <span className="mt-1 block text-[10px] leading-4 text-text-muted">
                            {option.description}
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>
                </div>
              ) : (
                <div>
                  <h3 className="text-[13px] font-semibold text-text-primary">
                    确认 Session 配置
                  </h3>
                  <p className="mt-1 text-[11px] text-text-muted">
                    提交后由 Control Plane
                    创建权威记录；启动操作需要在详情页单独发起。
                  </p>
                  <dl className="mt-5 divide-y divide-border-subtle rounded-[10px] border border-border-subtle bg-surface-2 px-4">
                    <ReviewItem label="名称" value={values.name} />
                    <ReviewItem label="租户" value={currentTenantId()} mono />
                    <ReviewItem label="Profile" value={values.profileId} mono />
                    <ReviewItem label="区域" value={values.region} mono />
                    <ReviewItem
                      label="资源等级"
                      value={values.resourceClass}
                      mono
                    />
                    <ReviewItem label="初始状态" value="CREATED" mono />
                  </dl>

                  {createMutation.error && (
                    <div
                      className="mt-4 rounded-[8px] border border-danger/25 bg-danger/8 p-3 text-[11px] text-danger"
                      role="alert"
                    >
                      <p>
                        {createMutation.error instanceof Error
                          ? createMutation.error.message
                          : '创建失败，请稍后重试。'}
                      </p>
                      {requestId && (
                        <p className="mt-1 font-mono">
                          Request ID: {requestId}
                        </p>
                      )}
                    </div>
                  )}
                </div>
              )}
            </div>

            <div className="flex items-center justify-between border-t border-border-subtle px-6 py-4">
              <p className="text-[10px] text-text-muted">
                所有写操作均等待后端真实响应，不进行前端伪成功。
              </p>
              <div className="flex items-center gap-2">
                {step === 2 && (
                  <button
                    type="button"
                    onClick={() => setStep(1)}
                    disabled={createMutation.isPending}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2 disabled:opacity-50"
                  >
                    <ArrowLeft size={13} />
                    上一步
                  </button>
                )}
                {step === 1 ? (
                  <button
                    type="button"
                    onClick={advance}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[12px] font-medium text-canvas hover:bg-accent/90"
                  >
                    下一步
                    <ArrowRight size={13} />
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => void submit()}
                    disabled={createMutation.isPending}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] bg-accent px-3 text-[12px] font-medium text-canvas hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    {createMutation.isPending && (
                      <LoaderCircle size={13} className="animate-spin" />
                    )}
                    {createMutation.isPending ? '正在创建' : '确认创建'}
                  </button>
                )}
              </div>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Field({
  label,
  hint,
  error,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 block text-[12px] font-medium text-text-primary">
        {label}
      </span>
      {children}
      {error ? (
        <span className="mt-1 block text-[10px] text-danger">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[10px] text-text-muted">{hint}</span>
      ) : null}
    </label>
  );
}

function ReviewItem({
  label,
  value,
  mono,
}: {
  label: string;
  value?: string;
  mono?: boolean;
}) {
  return (
    <div className="flex items-center justify-between gap-6 py-3">
      <dt className="text-[11px] text-text-muted">{label}</dt>
      <dd
        className={cn(
          'truncate text-[12px] text-text-primary',
          mono && 'font-mono'
        )}
      >
        {value || '—'}
      </dd>
    </div>
  );
}
