import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import { ArrowLeft, ArrowRight, Check, LoaderCircle, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { z } from 'zod';
import { currentTenantId } from '@/api/session';
import { isSessionApiError } from '@/api/session';
import { useCreateSession } from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';

const schema = z
  .object({
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
    requestedTabs: z.coerce.number().int().min(1).max(64),
    agentActionsPerMinute: z.coerce.number().int().min(0).max(600),
    remoteDesktop: z.boolean(),
    web3Workload: z.boolean(),
    mediaWorkload: z.boolean(),
    requestedMediaStreams: z.coerce.number().int().min(0).max(32),
    mediaBitrateKbps: z.coerce.number().int().min(0).max(1_000_000),
    extensionIds: z
      .string()
      .max(2048)
      .refine(
        (value) =>
          value
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean)
            .every((item) => /^[a-zA-Z0-9_.-]{1,128}$/.test(item)),
        '扩展 ID 仅支持字母、数字、点、下划线和连字符'
      ),
  })
  .superRefine((value, context) => {
    const hasMediaBudget =
      value.requestedMediaStreams > 0 && value.mediaBitrateKbps > 0;
    if (value.mediaWorkload !== hasMediaBudget) {
      context.addIssue({
        code: 'custom',
        path: ['requestedMediaStreams'],
        message: value.mediaWorkload
          ? '媒体任务需要正数流数量和聚合码率'
          : '未启用媒体任务时媒体预算必须为 0',
      });
    }
  });

type FormValues = z.infer<typeof schema>;

const resourceOptions = [
  { value: 'L1', label: 'L1 · Lite', description: '轻量只读与低频任务' },
  { value: 'L2', label: 'L2 · Standard', description: '标准 Agent 自动化' },
  { value: 'L3', label: 'L3 · Interactive', description: '远程桌面与人工接管' },
  { value: 'L4', label: 'L4 · Heavy', description: '高并发标签页与重型扩展' },
  { value: 'L5', label: 'L5 · Native', description: '原生系统隔离专用节点' },
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
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
    setValue,
    trigger,
    watch,
  } = useForm<FormValues>({
    resolver: zodResolver(schema),
    defaultValues: {
      name: '',
      profileId: 'profile-local-default',
      region: 'local',
      resourceClass: 'L2',
      requestedTabs: 2,
      agentActionsPerMinute: 60,
      remoteDesktop: false,
      web3Workload: false,
      mediaWorkload: false,
      requestedMediaStreams: 0,
      mediaBitrateKbps: 0,
      extensionIds: '',
    },
  });

  const values = watch();

  useEffect(() => {
    if (!values.mediaWorkload) {
      setValue('requestedMediaStreams', 0);
      setValue('mediaBitrateKbps', 0);
    }
  }, [setValue, values.mediaWorkload]);

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
    const fields =
      step === 1
        ? (['name', 'profileId', 'region', 'resourceClass'] as const)
        : ([
            'requestedTabs',
            'agentActionsPerMinute',
            'remoteDesktop',
            'web3Workload',
            'mediaWorkload',
            'requestedMediaStreams',
            'mediaBitrateKbps',
            'extensionIds',
          ] as const);
    if (await trigger(fields)) setStep((current) => (current + 1) as 2 | 3);
  };

  const submit = handleSubmit(async (form) => {
    const result = await createMutation.mutateAsync({
      idempotencyKey: crypto.randomUUID(),
      request: {
        tenantId: currentTenantId(),
        profileId: form.profileId,
        region: form.region,
        resourceClass: form.resourceClass,
        requestedTabs: form.requestedTabs,
        agentActionsPerMinute: form.agentActionsPerMinute,
        remoteDesktop: form.remoteDesktop,
        web3Workload: form.web3Workload,
        mediaWorkload: form.mediaWorkload,
        requestedMediaStreams: form.requestedMediaStreams,
        mediaBitrateKbps: form.mediaBitrateKbps,
        extensionIds: form.extensionIds
          .split(',')
          .map((item) => item.trim())
          .filter(Boolean),
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
                创建真实 Session，并将工作负载、扩展和隔离需求提交给 Placement。
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
            <ol className="grid grid-cols-3 gap-3" aria-label="创建步骤">
              {[
                { number: 1, label: '基础配置' },
                { number: 2, label: '工作负载' },
                { number: 3, label: '确认创建' },
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
                    <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
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
              ) : step === 2 ? (
                <div className="space-y-5">
                  <div className="grid grid-cols-2 gap-4">
                    <Field
                      label="请求标签页"
                      error={errors.requestedTabs?.message}
                      hint="Placement 会同时受 Resource Class 的 Tab Budget 限制。"
                    >
                      <input
                        type="number"
                        min={1}
                        max={64}
                        {...register('requestedTabs')}
                        className="field-input font-mono"
                      />
                    </Field>
                    <Field
                      label="Agent 动作/分钟"
                      error={errors.agentActionsPerMinute?.message}
                      hint="用于估算 CPU、网络和 Planner 压力。"
                    >
                      <input
                        type="number"
                        min={0}
                        max={600}
                        {...register('agentActionsPerMinute')}
                        className="field-input font-mono"
                      />
                    </Field>
                  </div>

                  <Field
                    label="Extension IDs"
                    error={errors.extensionIds?.message}
                    hint="逗号分隔。未知扩展自动进入 Probation，并提升资源等级。"
                  >
                    <textarea
                      {...register('extensionIds')}
                      rows={3}
                      spellCheck={false}
                      placeholder="wallet.example, accessibility.helper"
                      className="field-input min-h-20 resize-y font-mono"
                    />
                  </Field>

                  <label className="flex cursor-pointer items-start gap-3 border border-border-subtle bg-surface-2 p-3">
                    <input
                      type="checkbox"
                      {...register('remoteDesktop')}
                      className="mt-0.5 h-4 w-4 accent-accent"
                    />
                    <span>
                      <span className="block text-[12px] font-medium text-text-primary">
                        需要远程桌面
                      </span>
                      <span className="mt-1 block text-[10px] text-text-muted">
                        自动要求支持 Xvfb/x11vnc 的 Node，并至少提升到 L3。
                      </span>
                    </span>
                  </label>

                  <label className="flex cursor-pointer items-start gap-3 border border-border-subtle bg-surface-2 p-3">
                    <input
                      type="checkbox"
                      {...register('mediaWorkload')}
                      className="mt-0.5 h-4 w-4 accent-accent"
                    />
                    <span>
                      <span className="block text-[12px] font-medium text-text-primary">
                        Media / Encoder 工作负载
                      </span>
                      <span className="mt-1 block text-[10px] text-text-muted">
                        使用独立媒体槽位、租户并发流和聚合码率配额，并至少提升到
                        L4。
                      </span>
                    </span>
                  </label>

                  {values.mediaWorkload ? (
                    <div className="grid grid-cols-2 gap-4">
                      <Field
                        label="并发媒体流"
                        error={errors.requestedMediaStreams?.message}
                      >
                        <input
                          type="number"
                          min={1}
                          max={32}
                          {...register('requestedMediaStreams')}
                          className="field-input font-mono"
                        />
                      </Field>
                      <Field
                        label="聚合码率 (kbps)"
                        error={errors.mediaBitrateKbps?.message}
                      >
                        <input
                          type="number"
                          min={1}
                          max={1_000_000}
                          {...register('mediaBitrateKbps')}
                          className="field-input font-mono"
                        />
                      </Field>
                    </div>
                  ) : null}

                  <label className="flex cursor-pointer items-start gap-3 border border-border-subtle bg-surface-2 p-3">
                    <input
                      type="checkbox"
                      {...register('web3Workload')}
                      className="mt-0.5 h-4 w-4 accent-accent"
                    />
                    <span>
                      <span className="block text-[12px] font-medium text-text-primary">
                        Web3 / Crypto 工作负载
                      </span>
                      <span className="mt-1 block text-[10px] text-text-muted">
                        强制高风险隔离策略；不会为了容量降低安全等级。
                      </span>
                    </span>
                  </label>
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
                    <ReviewItem
                      label="标签页 / Agent 速率"
                      value={`${values.requestedTabs} / ${values.agentActionsPerMinute} min⁻¹`}
                      mono
                    />
                    <ReviewItem
                      label="远程桌面 / Web3"
                      value={`${values.remoteDesktop ? 'YES' : 'NO'} / ${values.web3Workload ? 'YES' : 'NO'}`}
                      mono
                    />
                    <ReviewItem
                      label="Media streams / bitrate"
                      value={
                        values.mediaWorkload
                          ? `${values.requestedMediaStreams} / ${values.mediaBitrateKbps} kbps`
                          : 'NONE'
                      }
                      mono
                    />
                    <ReviewItem
                      label="扩展"
                      value={values.extensionIds || 'NONE'}
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
                {step > 1 && (
                  <button
                    type="button"
                    onClick={() => setStep((current) => (current - 1) as 1 | 2)}
                    disabled={createMutation.isPending}
                    className="inline-flex h-8 items-center gap-1.5 rounded-[7px] border border-border-default px-3 text-[12px] text-text-secondary hover:bg-surface-2 disabled:opacity-50"
                  >
                    <ArrowLeft size={13} />
                    上一步
                  </button>
                )}
                {step < 3 ? (
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
