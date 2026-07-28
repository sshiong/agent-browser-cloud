import * as Dialog from '@radix-ui/react-dialog';
import { zodResolver } from '@hookform/resolvers/zod';
import {
  ArrowLeft,
  ArrowRight,
  Check,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  Cpu,
  Database,
  LoaderCircle,
  Network,
  Puzzle,
  Rocket,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useNavigate } from 'react-router';
import { z } from 'zod';
import { currentTenantId, isSessionApiError } from '@/api/session';
import { useAuth } from '@/auth/AuthProvider';
import { useEnterpriseOverview } from '@/features/enterprise/enterpriseQueries';
import { useExtensionProfiles } from '@/features/nodes/capacityQueries';
import { useProfiles } from '@/features/profiles/profileQueries';
import { useWorkspaceGroups } from '@/features/groups/groupQueries';
import { useWorkspaceTags } from '@/features/groups/tagQueries';
import { useProxyOverview } from '@/features/proxies/proxyQueries';
import { useRuntimeBuilds } from '@/features/security/platformQueries';
import {
  useCreateSession,
  useRecoveryContracts,
} from '@/features/sessions/api/sessionQueries';
import { cn } from '@/shared/lib/utils';

const schema = z
  .object({
    name: z.string().trim().min(1, '请输入环境名称').max(128),
    groupId: z.string().trim().max(36),
    tagIds: z.array(z.string().max(36)).max(16),
    description: z.string().trim().max(500),
    accent: z.enum(['teal', 'blue', 'amber', 'violet']),
    runtimeBuildId: z.string().trim().min(1, '请选择 Runtime Build'),
    applicationId: z.string().trim().max(128),
    profileMode: z.enum(['empty', 'existing', 'checkpoint']),
    profileId: z.string().trim().max(128),
    networkMode: z.enum(['managed', 'direct']),
    region: z.string().trim().min(1, '请选择部署区域').max(32),
    executionEnvironment: z.enum([
      'SYSTEM_MANAGED',
      'CONTAINER',
      'ENHANCED_SANDBOX',
      'MICROVM',
      'NATIVE_OS',
    ]),
    onMaximumReached: z.enum([
      'PAUSE_AGENT',
      'WAIT_SAFE_POINT_MIGRATE',
      'HIBERNATE',
      'TERMINATE_STRICT',
    ]),
    strictBudgetConfirmed: z.boolean(),
    minimumTemplate: z.enum([
      'standard-v1',
      'interactive-v1',
      'heavy-v1',
      'native-standard-v1',
    ]),
    maximumCpuMillis: z.coerce.number().int().min(500).max(32000),
    maximumMemoryMib: z.coerce.number().int().min(512).max(131072),
    allowMigration: z.boolean(),
    allowHibernate: z.boolean(),
    blockMigrationDuringHumanTakeover: z.boolean(),
    adjustmentCooldownSeconds: z.coerce.number().int().min(60).max(3600),
    scaleDownWindowSeconds: z.coerce.number().int().min(300).max(86400),
    requestedTabs: z.coerce.number().int().min(1).max(64),
    agentActionsPerMinute: z.coerce.number().int().min(0).max(600),
    remoteDesktop: z.boolean(),
    mediaClass: z.enum(['M0', 'M1', 'M2', 'M3', 'M4']),
    extensionIds: z.array(z.string().max(128)).max(32),
    agentEnabled: z.boolean(),
    agentPolicy: z.enum(['balanced', 'restricted', 'interactive']),
    humanTakeover: z.boolean(),
    idleTimeoutMinutes: z.coerce.number().int().min(5).max(1440),
    snapshotPolicy: z.enum(['on-stop', 'periodic', 'manual']),
    web3Workload: z.boolean(),
  })
  .superRefine((value, context) => {
    if (value.profileMode !== 'empty' && !value.profileId) {
      context.addIssue({
        code: 'custom',
        path: ['profileId'],
        message: '请选择 Profile',
      });
    }
    if (
      value.onMaximumReached === 'TERMINATE_STRICT' &&
      !value.strictBudgetConfirmed
    ) {
      context.addIssue({
        code: 'custom',
        path: ['strictBudgetConfirmed'],
        message: '请确认严格预算可能终止环境并中断登录状态',
      });
    }
  });

type FormValues = z.infer<typeof schema>;
type Step = 1 | 2 | 3 | 4 | 5 | 6;

const steps = [
  { number: 1, short: '基础', label: '基本信息', icon: Sparkles },
  { number: 2, short: 'Runtime', label: 'Runtime 与 Profile', icon: Cpu },
  { number: 3, short: '网络', label: '网络与区域', icon: Network },
  {
    number: 4,
    short: '资源',
    label: '工作负载与资源',
    icon: SlidersHorizontal,
  },
  { number: 5, short: '能力', label: '扩展与 Agent', icon: Puzzle },
  { number: 6, short: '确认', label: '检查并创建', icon: Rocket },
] as const;

const mediaOptions = [
  { value: 'M0', label: 'M0 · 无媒体', detail: '0 streams' },
  { value: 'M1', label: 'M1 · 基础音频', detail: '1 / 1.5 Mbps' },
  { value: 'M2', label: 'M2 · 交互视频', detail: '1 / 4 Mbps' },
  { value: 'M3', label: 'M3 · 高清媒体', detail: '1 / 8 Mbps' },
  { value: 'M4', label: 'M4 · 多流编码', detail: '2 / 12 Mbps' },
] as const;

const mediaBudgets = {
  M0: { mediaWorkload: false, streams: 0, bitrate: 0 },
  M1: { mediaWorkload: true, streams: 1, bitrate: 1500 },
  M2: { mediaWorkload: true, streams: 1, bitrate: 4000 },
  M3: { mediaWorkload: true, streams: 1, bitrate: 8000 },
  M4: { mediaWorkload: true, streams: 2, bitrate: 12000 },
} as const;

const stepFields: Record<Step, (keyof FormValues)[]> = {
  1: ['name', 'groupId', 'tagIds', 'description', 'accent'],
  2: ['runtimeBuildId', 'applicationId', 'profileMode', 'profileId'],
  3: ['networkMode', 'region'],
  4: [
    'executionEnvironment',
    'onMaximumReached',
    'strictBudgetConfirmed',
    'minimumTemplate',
    'maximumCpuMillis',
    'maximumMemoryMib',
    'requestedTabs',
    'agentActionsPerMinute',
    'remoteDesktop',
    'mediaClass',
  ],
  5: [
    'extensionIds',
    'agentEnabled',
    'agentPolicy',
    'humanTakeover',
    'idleTimeoutMinutes',
    'snapshotPolicy',
    'web3Workload',
  ],
  6: [],
};

export function CreateSessionDialog({
  open,
  onOpenChange,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
}) {
  const navigate = useNavigate();
  const auth = useAuth();
  const createMutation = useCreateSession();
  const runtimeQuery = useRuntimeBuilds();
  const profilesQuery = useProfiles();
  const proxyQuery = useProxyOverview();
  const enterpriseQuery = useEnterpriseOverview();
  const extensionsQuery = useExtensionProfiles();
  const recoveryContractsQuery = useRecoveryContracts();
  const groupsQuery = useWorkspaceGroups();
  const tagsQuery = useWorkspaceTags();
  const [step, setStep] = useState<Step>(1);
  const [createdSessionId, setCreatedSessionId] = useState<string>();
  const [advancedResourcesOpen, setAdvancedResourcesOpen] = useState(false);
  const canAdministerResources = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const canUseStrictBudget = auth.hasAnyRole(['PLATFORM_ADMIN']);
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
      groupId: '',
      tagIds: [],
      description: '',
      accent: 'teal',
      runtimeBuildId: '',
      applicationId: '',
      profileMode: 'empty',
      profileId: '',
      networkMode: 'managed',
      region: '',
      requestedTabs: 4,
      agentActionsPerMinute: 60,
      executionEnvironment: 'SYSTEM_MANAGED',
      onMaximumReached: 'PAUSE_AGENT',
      strictBudgetConfirmed: false,
      minimumTemplate: 'standard-v1',
      maximumCpuMillis: 4000,
      maximumMemoryMib: 4096,
      allowMigration: true,
      allowHibernate: true,
      blockMigrationDuringHumanTakeover: true,
      adjustmentCooldownSeconds: 300,
      scaleDownWindowSeconds: 1200,
      remoteDesktop: false,
      mediaClass: 'M0',
      extensionIds: [],
      agentEnabled: true,
      agentPolicy: 'balanced',
      humanTakeover: true,
      idleTimeoutMinutes: 30,
      snapshotPolicy: 'on-stop',
      web3Workload: false,
    },
  });
  const values = watch();

  const approvedRuntimes = useMemo(
    () =>
      (runtimeQuery.data?.items ?? []).filter(
        (runtime) =>
          runtime.releaseChannel === 'STABLE' && runtime.signatureVerified
      ),
    [runtimeQuery.data?.items]
  );
  const regions = useMemo(
    () =>
      (enterpriseQuery.data?.regions ?? []).filter(
        (region) => region.admissionState === 'OPEN'
      ),
    [enterpriseQuery.data?.regions]
  );

  useEffect(() => {
    if (!values.runtimeBuildId && approvedRuntimes[0]) {
      setValue('runtimeBuildId', approvedRuntimes[0].buildId);
    }
  }, [approvedRuntimes, setValue, values.runtimeBuildId]);

  useEffect(() => {
    if (!values.region && regions[0]) {
      setValue('region', regions[0].regionId);
    }
  }, [regions, setValue, values.region]);

  useEffect(() => {
    const group = groupsQuery.data?.items.find(
      (item) => item.groupId === values.groupId
    );
    if (!group) return;
    setValue('onMaximumReached', group.defaultOnMaximumReached);
    setValue('allowMigration', group.defaultAllowMigration);
    setValue('allowHibernate', group.defaultAllowHibernate);
  }, [groupsQuery.data?.items, setValue, values.groupId]);

  const resetFlow = () => {
    setStep(1);
    setCreatedSessionId(undefined);
    createMutation.reset();
    reset();
  };

  const handleOpenChange = (nextOpen: boolean) => {
    if (!nextOpen) resetFlow();
    onOpenChange(nextOpen);
  };

  const advance = async () => {
    if (step >= 6) return;
    if (await trigger(stepFields[step])) {
      setStep((step + 1) as Step);
    }
  };

  const chooseRemoteDesktop = (checked: boolean) => {
    setValue('remoteDesktop', checked);
    if (checked && values.mediaClass === 'M0') {
      setValue('mediaClass', 'M2');
    }
  };

  const submit = handleSubmit(async (form) => {
    const budget = mediaBudgets[form.mediaClass];
    const safeName = form.name
      .toLowerCase()
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-|-$/g, '')
      .slice(0, 32);
    const generatedProfileId = `profile-${safeName || 'environment'}-${crypto.randomUUID().slice(0, 8)}`;
    const profileId =
      form.profileMode === 'empty' ? generatedProfileId : form.profileId;
    const result = await createMutation.mutateAsync({
      idempotencyKey: crypto.randomUUID(),
      request: {
        tenantId: currentTenantId(),
        profileId,
        applicationId: form.applicationId || undefined,
        groupId: form.groupId || undefined,
        tagIds: form.tagIds,
        region: form.region,
        resourcePolicy: {
          mode: 'AUTO',
          onMaximumReached: form.onMaximumReached,
          allowMigration: form.allowMigration,
          allowHibernate: form.allowHibernate,
          blockMigrationDuringHumanTakeover:
            form.blockMigrationDuringHumanTakeover,
          executionEnvironment: form.executionEnvironment,
          ...(auth.hasAnyRole([
            'TENANT_ADMIN',
            'SECURITY_ADMIN',
            'PLATFORM_ADMIN',
          ])
            ? {
                minimumTemplate: form.minimumTemplate,
                maximumCpuMillis: form.maximumCpuMillis,
                maximumMemoryMib: form.maximumMemoryMib,
                adjustmentCooldownSeconds: form.adjustmentCooldownSeconds,
                scaleDownWindowSeconds: form.scaleDownWindowSeconds,
              }
            : {}),
        },
        requestedTabs: form.requestedTabs,
        agentActionsPerMinute: form.agentActionsPerMinute,
        remoteDesktop: form.remoteDesktop,
        web3Workload: form.web3Workload,
        mediaWorkload: budget.mediaWorkload,
        requestedMediaStreams: budget.streams,
        mediaBitrateKbps: budget.bitrate,
        extensionIds: form.extensionIds,
        metadata: {
          displayName: form.name,
          description: form.description,
          visualAccent: form.accent,
          requestedRuntimeBuildId: form.runtimeBuildId,
          profileMode: form.profileMode,
          networkMode: form.networkMode,
          proxyProviderId:
            form.networkMode === 'managed'
              ? (proxyQuery.data?.provider.providerId ?? '')
              : '',
          mediaClass: form.mediaClass,
          agentEnabled: String(form.agentEnabled),
          agentPolicy: form.agentPolicy,
          humanTakeover: String(form.humanTakeover),
          idleTimeoutMinutes: String(form.idleTimeoutMinutes),
          snapshotPolicy: form.snapshotPolicy,
        },
      },
    });
    setCreatedSessionId(result.sessionId);
  });

  const requestId = isSessionApiError(createMutation.error)
    ? createMutation.error.body.requestId
    : undefined;

  return (
    <Dialog.Root open={open} onOpenChange={handleOpenChange}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/80 backdrop-blur-[2px]" />
        <Dialog.Content
          className="fixed inset-0 z-50 flex w-full flex-col bg-surface-1 shadow-2xl focus:outline-none min-[1281px]:inset-y-0 min-[1281px]:left-auto min-[1281px]:right-0 min-[1281px]:w-[clamp(620px,48vw,760px)] min-[1281px]:border-l min-[1281px]:border-border-default"
          aria-describedby="create-session-description"
        >
          <div className="flex min-h-[72px] shrink-0 items-center justify-between gap-4 border-b border-border-subtle px-5 sm:px-7">
            <div className="min-w-0">
              <div className="mb-1 flex items-center gap-2">
                <span className="font-mono text-[10px] uppercase tracking-[0.16em] text-accent">
                  Environment Provisioning
                </span>
                <span className="text-[10px] text-text-muted">
                  {step} / {steps.length}
                </span>
              </div>
              <Dialog.Title className="truncate text-[18px] font-semibold text-text-primary">
                新建浏览器环境
              </Dialog.Title>
              <Dialog.Description
                id="create-session-description"
                className="mt-0.5 hidden text-[12px] text-text-muted sm:block"
              >
                逐步声明运行需求，由 Control Plane 与 Placement 做最终裁决。
              </Dialog.Description>
            </div>
            <Dialog.Close
              className="flex h-9 w-9 shrink-0 items-center justify-center rounded-md text-text-muted hover:bg-surface-2 hover:text-text-primary"
              aria-label="关闭创建环境面板"
            >
              <X size={17} />
            </Dialog.Close>
          </div>

          <div className="shrink-0 border-b border-border-subtle px-4 py-3 sm:px-7">
            <ol className="grid grid-cols-6 gap-1" aria-label="创建步骤">
              {steps.map((item) => {
                const Icon = item.icon;
                const active = step === item.number;
                const complete = step > item.number;
                return (
                  <li key={item.number}>
                    <button
                      type="button"
                      disabled={item.number > step || createdSessionId != null}
                      onClick={() => setStep(item.number)}
                      aria-current={active ? 'step' : undefined}
                      className={cn(
                        'group flex w-full flex-col items-center gap-1.5 border-t-2 px-1 pt-2 text-center transition-colors',
                        active || complete
                          ? 'border-accent'
                          : 'border-border-subtle',
                        item.number <= step
                          ? 'text-text-secondary'
                          : 'cursor-not-allowed text-text-muted'
                      )}
                    >
                      <span
                        className={cn(
                          'flex h-6 w-6 items-center justify-center rounded-full',
                          complete
                            ? 'bg-accent text-canvas'
                            : active
                              ? 'bg-accent-soft text-accent'
                              : 'bg-surface-2'
                        )}
                      >
                        {complete ? <Check size={12} /> : <Icon size={12} />}
                      </span>
                      <span className="text-[10px] sm:hidden">
                        {item.short}
                      </span>
                      <span className="hidden truncate text-[10px] sm:block">
                        {item.label}
                      </span>
                    </button>
                  </li>
                );
              })}
            </ol>
          </div>

          <form
            onSubmit={(event) => event.preventDefault()}
            className="flex min-h-0 flex-1 flex-col"
          >
            <div className="flex-1 overflow-y-auto px-5 py-6 sm:px-7">
              {step === 1 && (
                <WizardStep
                  eyebrow="01 · Identity"
                  title="先让团队识别这个环境"
                  description="这些字段用于环境列表与治理视图，不会替代服务端生成的 Session ID。"
                >
                  <Field label="环境名称" error={errors.name?.message} required>
                    <input
                      {...register('name')}
                      autoFocus
                      placeholder="例如：CRM 新加坡生产验证"
                      className="field-input"
                    />
                  </Field>
                  <div className="grid gap-4 sm:grid-cols-2">
                    <Field
                      label="Workspace 分组"
                      error={errors.groupId?.message}
                    >
                      <select {...register('groupId')} className="field-input">
                        <option value="">不分组</option>
                        {(groupsQuery.data?.items ?? []).map((group) => (
                          <option key={group.groupId} value={group.groupId}>
                            {group.name} · {group.sessionCount} 个环境
                          </option>
                        ))}
                      </select>
                    </Field>
                    <Field
                      label="标签"
                      hint="选择租户管理员创建的正式标签，最多 16 个"
                      error={errors.tagIds?.message}
                    >
                      <div className="min-h-9 border border-border-subtle bg-surface-2 p-2">
                        {tagsQuery.isLoading ? (
                          <p className="text-[10px] text-text-muted">
                            正在加载标签…
                          </p>
                        ) : tagsQuery.isError ? (
                          <p role="alert" className="text-[10px] text-danger">
                            无法读取正式标签
                          </p>
                        ) : tagsQuery.data?.items.length ? (
                          <div className="flex flex-wrap gap-1.5">
                            {tagsQuery.data.items.map((tag) => {
                              const selected = values.tagIds.includes(
                                tag.tagId
                              );
                              return (
                                <button
                                  key={tag.tagId}
                                  type="button"
                                  aria-pressed={selected}
                                  onClick={() =>
                                    setValue(
                                      'tagIds',
                                      selected
                                        ? values.tagIds.filter(
                                            (tagId) => tagId !== tag.tagId
                                          )
                                        : [...values.tagIds, tag.tagId],
                                      { shouldValidate: true }
                                    )
                                  }
                                  className={cn(
                                    'inline-flex h-7 items-center gap-1.5 border px-2 text-[10px]',
                                    selected
                                      ? 'border-accent/60 bg-accent-soft text-accent'
                                      : 'border-border-subtle text-text-muted hover:text-text-primary'
                                  )}
                                >
                                  <span
                                    className="h-2 w-2"
                                    style={{ backgroundColor: tag.color }}
                                    aria-hidden="true"
                                  />
                                  {tag.name}
                                </button>
                              );
                            })}
                          </div>
                        ) : (
                          <p className="text-[10px] text-text-muted">
                            尚无正式标签，可稍后在“分组与标签”中创建。
                          </p>
                        )}
                      </div>
                    </Field>
                  </div>
                  <Field
                    label="用途说明"
                    hint="说明为什么需要它，便于审计与交接。"
                    error={errors.description?.message}
                  >
                    <textarea
                      {...register('description')}
                      rows={4}
                      className="field-input min-h-24 resize-y py-2.5"
                      placeholder="用于 CRM 回归验证和受控 Agent 工作流。"
                    />
                  </Field>
                  <fieldset>
                    <legend className="mb-2 text-[13px] font-medium text-text-primary">
                      识别色
                    </legend>
                    <div className="flex gap-3">
                      {(['teal', 'blue', 'amber', 'violet'] as const).map(
                        (accent) => (
                          <label
                            key={accent}
                            className="flex cursor-pointer items-center gap-2 text-[12px] capitalize text-text-secondary"
                          >
                            <input
                              type="radio"
                              value={accent}
                              {...register('accent')}
                              className="sr-only"
                            />
                            <span
                              className={cn(
                                'h-8 w-8 rounded-full border-2 border-surface-1 ring-1 transition',
                                accent === 'teal' && 'bg-accent',
                                accent === 'blue' && 'bg-accent-secondary',
                                accent === 'amber' && 'bg-warning',
                                accent === 'violet' && 'bg-purple',
                                values.accent === accent
                                  ? 'ring-text-primary'
                                  : 'ring-border-default'
                              )}
                            />
                            <span className="sr-only">{accent}</span>
                          </label>
                        )
                      )}
                    </div>
                  </fieldset>
                </WizardStep>
              )}

              {step === 2 && (
                <WizardStep
                  eyebrow="02 · Runtime & State"
                  title="选择已验证的运行时与状态来源"
                  description="Runtime 是调度偏好，平台策略与签名校验拥有最终裁决权；Profile 来自真实存储。"
                >
                  <Field
                    label="Runtime Build"
                    error={errors.runtimeBuildId?.message}
                    hint="仅显示 STABLE 且签名已验证的 Build。"
                    required
                  >
                    {runtimeQuery.isLoading ? (
                      <LoadingBlock label="正在读取 Runtime Registry" />
                    ) : runtimeQuery.isError ? (
                      <QueryError label="无法读取 Runtime Registry" />
                    ) : approvedRuntimes.length === 0 ? (
                      <QueryError label="没有可用的已签名稳定 Runtime" />
                    ) : (
                      <div className="space-y-2">
                        {approvedRuntimes.map((runtime) => (
                          <ChoiceCard
                            key={runtime.buildId}
                            name="runtime-build"
                            checked={values.runtimeBuildId === runtime.buildId}
                            onChange={() =>
                              setValue('runtimeBuildId', runtime.buildId, {
                                shouldValidate: true,
                              })
                            }
                            title={`${runtime.engine} ${runtime.version}`}
                            badge={runtime.releaseChannel}
                            description={`${runtime.platform} · ${runtime.securityTier} · ${runtime.regressionStatus}`}
                            meta={runtime.buildId}
                          />
                        ))}
                      </div>
                    )}
                  </Field>

                  <fieldset>
                    <legend className="mb-2 text-[13px] font-medium text-text-primary">
                      Profile 来源
                    </legend>
                    <div className="grid gap-2 sm:grid-cols-3">
                      {[
                        {
                          value: 'empty',
                          title: '全新 Profile',
                          detail: '创建隔离的空状态',
                        },
                        {
                          value: 'existing',
                          title: '现有 Profile',
                          detail: '继续使用持久状态',
                        },
                        {
                          value: 'checkpoint',
                          title: 'Checkpoint',
                          detail: '从最近检查点恢复',
                        },
                      ].map((option) => (
                        <label
                          key={option.value}
                          className="cursor-pointer rounded-[8px] border border-border-subtle bg-surface-2 p-3 transition-colors has-[:checked]:border-accent/60 has-[:checked]:bg-accent-soft"
                        >
                          <input
                            type="radio"
                            value={option.value}
                            {...register('profileMode')}
                            onChange={(event) => {
                              const mode = event.target
                                .value as FormValues['profileMode'];
                              setValue('profileMode', mode, {
                                shouldValidate: true,
                              });
                              if (mode === 'empty') {
                                setValue('profileId', '');
                              }
                            }}
                            className="sr-only"
                          />
                          <Database size={15} className="mb-2 text-accent" />
                          <span className="block text-[13px] font-medium text-text-primary">
                            {option.title}
                          </span>
                          <span className="mt-1 block text-[11px] text-text-muted">
                            {option.detail}
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>

                  {values.profileMode !== 'empty' && (
                    <Field
                      label={
                        values.profileMode === 'checkpoint'
                          ? '可恢复的 Profile'
                          : '现有 Profile'
                      }
                      error={errors.profileId?.message}
                      required
                    >
                      <select
                        {...register('profileId')}
                        className="field-input"
                      >
                        <option value="">请选择 Profile</option>
                        {(profilesQuery.data?.items ?? [])
                          .filter(
                            (profile) =>
                              values.profileMode !== 'checkpoint' ||
                              profile.latestCheckpointId
                          )
                          .map((profile) => (
                            <option
                              key={profile.profileId}
                              value={profile.profileId}
                            >
                              {profile.name} · {profile.restoreStatus}
                            </option>
                          ))}
                      </select>
                    </Field>
                  )}

                  <Field
                    label="业务恢复契约"
                    hint="可选。绑定后，迁移只有通过该应用的 Ready Gate 才会恢复 Agent。"
                  >
                    {recoveryContractsQuery.isLoading ? (
                      <LoadingBlock label="正在读取 Recovery Contracts" />
                    ) : recoveryContractsQuery.isError ? (
                      <QueryError label="无法读取 Recovery Contracts" />
                    ) : (
                      <select
                        {...register('applicationId')}
                        className="field-input"
                      >
                        <option value="">通用保守验证器</option>
                        {(recoveryContractsQuery.data?.items ?? [])
                          .filter((contract) => contract.enabled)
                          .map((contract) => (
                            <option
                              key={contract.contractId}
                              value={contract.applicationId}
                            >
                              {contract.applicationId} · v{contract.version} ·{' '}
                              {contract.expectedOrigins.join(', ')}
                            </option>
                          ))}
                      </select>
                    )}
                  </Field>

                  <UnavailableOption
                    title="从文件导入 Profile"
                    detail="等待 Profile Import API 和上传审计链路接入后开放。"
                  />
                </WizardStep>
              )}

              {step === 3 && (
                <WizardStep
                  eyebrow="03 · Network"
                  title="声明出口策略与部署区域"
                  description="区域来自 Enterprise API；网络策略不会在前端伪造绑定，最终由 Control Plane 处理。"
                >
                  <div className="grid gap-3 sm:grid-cols-2">
                    <ChoiceCard
                      name="network-mode"
                      checked={values.networkMode === 'managed'}
                      onChange={() => setValue('networkMode', 'managed')}
                      title="平台托管出口"
                      badge={proxyQuery.data?.provider.state ?? '正在读取'}
                      description="由平台代理提供商分配、验证并记录出口。"
                      meta={
                        proxyQuery.data?.provider.providerId ?? 'Proxy provider'
                      }
                    />
                    <ChoiceCard
                      name="network-mode"
                      checked={values.networkMode === 'direct'}
                      disabled={!import.meta.env.DEV}
                      onChange={() => setValue('networkMode', 'direct')}
                      title="直接网络"
                      badge={import.meta.env.DEV ? 'DEV ONLY' : '不可用'}
                      description="仅开发环境允许；生产策略不会回退到直连。"
                      meta="No managed proxy request"
                    />
                  </div>

                  <UnavailableOption
                    title="复用现有 Proxy Binding"
                    detail="现有 allocation 与 Session 绑定，不允许在 UI 中跨 Session 复用。"
                  />

                  <Field
                    label="部署区域"
                    error={errors.region?.message}
                    hint="只显示 admissionState=OPEN 的真实区域。"
                    required
                  >
                    {enterpriseQuery.isLoading ? (
                      <LoadingBlock label="正在读取 Region Admission" />
                    ) : enterpriseQuery.isError ? (
                      <QueryError label="无法读取区域清单" />
                    ) : regions.length === 0 ? (
                      <QueryError label="当前没有开放接入的区域" />
                    ) : (
                      <select {...register('region')} className="field-input">
                        {regions.map((region) => (
                          <option key={region.regionId} value={region.regionId}>
                            {region.regionId} · {region.role} · lag{' '}
                            {region.replicationLagSeconds}s
                          </option>
                        ))}
                      </select>
                    )}
                  </Field>

                  <div className="border border-border-subtle bg-surface-2 p-4">
                    <div className="flex items-start gap-3">
                      <ShieldCheck
                        size={17}
                        className="mt-0.5 shrink-0 text-success"
                      />
                      <div>
                        <p className="text-[13px] font-medium text-text-primary">
                          安全边界保持明确
                        </p>
                        <p className="mt-1 text-[11px] leading-5 text-text-muted">
                          出口分配、IP 校验和 direct fallback
                          规则均由服务端执行；向导仅提交已选择的策略声明。
                        </p>
                      </div>
                    </div>
                  </div>
                </WizardStep>
              )}

              {step === 4 && (
                <WizardStep
                  eyebrow="04 · Automatic Resources"
                  title="由平台持续管理运行资源"
                  description="普通用户无需判断 CPU、内存或节点类型。Control Plane 会根据真实负载和 Workspace 策略解析内部资源模板。"
                >
                  <div className="border border-accent/35 bg-accent-soft p-4">
                    <div className="flex items-start gap-3">
                      <span className="mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded-full border border-accent">
                        <span className="h-2 w-2 rounded-full bg-accent" />
                      </span>
                      <div>
                        <div className="flex flex-wrap items-center gap-2">
                          <p className="text-[13px] font-semibold text-text-primary">
                            自动分配
                          </p>
                          <span className="border border-accent/30 px-1.5 py-0.5 font-mono text-[9px] uppercase tracking-[0.12em] text-accent">
                            推荐 · AUTO
                          </span>
                        </div>
                        <p className="mt-1.5 max-w-xl text-[11px] leading-5 text-text-secondary">
                          平台会根据页面复杂度、Agent
                          任务、标签页、扩展、远程桌面和实际运行负载动态调整资源。
                          单次尖峰不会触发扩容。
                        </p>
                      </div>
                    </div>
                  </div>

                  <div className="grid gap-px overflow-hidden border border-border-subtle bg-border-subtle sm:grid-cols-2">
                    <div className="bg-surface-2 p-3">
                      <p className="text-[10px] uppercase tracking-[0.12em] text-text-muted">
                        最低基线
                      </p>
                      <p className="mt-1 text-[12px] text-text-primary">
                        标准运行基线
                      </p>
                    </div>
                    <div className="bg-surface-2 p-3">
                      <p className="text-[10px] uppercase tracking-[0.12em] text-text-muted">
                        允许上限
                      </p>
                      <p className="mt-1 text-[12px] text-text-primary">
                        由 Workspace 策略共同裁决
                      </p>
                    </div>
                  </div>

                  <fieldset>
                    <legend className="mb-2 text-[13px] font-medium text-text-primary">
                      达到资源上限时
                    </legend>
                    <div className="space-y-2">
                      {[
                        {
                          value: 'PAUSE_AGENT',
                          title: '暂停 Agent，保留浏览器',
                          detail:
                            '默认策略。登录状态保留，HumanTakeover 仍可用。',
                        },
                        {
                          value: 'WAIT_SAFE_POINT_MIGRATE',
                          title: '等待安全点并迁移',
                          detail:
                            '不会在接管、传输、表单提交或关键事务中迁移。',
                        },
                        {
                          value: 'HIBERNATE',
                          title: '自动休眠',
                          detail: '创建 Checkpoint 后释放 Browser 资源。',
                        },
                        ...(canUseStrictBudget
                          ? [
                              {
                                value: 'TERMINATE_STRICT',
                                title: '严格预算，终止环境',
                                detail:
                                  '高风险策略，仅 Platform Admin 可配置。',
                              },
                            ]
                          : []),
                      ].map((option) => (
                        <label
                          key={option.value}
                          className="flex cursor-pointer items-start gap-3 border border-border-subtle bg-surface-2 p-3 has-[:checked]:border-accent/55 has-[:checked]:bg-accent-soft"
                        >
                          <input
                            type="radio"
                            value={option.value}
                            {...register('onMaximumReached')}
                            className="mt-0.5 accent-[var(--color-accent)]"
                          />
                          <span>
                            <span className="block text-[12px] font-medium text-text-primary">
                              {option.title}
                            </span>
                            <span className="mt-0.5 block text-[10px] leading-4 text-text-muted">
                              {option.detail}
                            </span>
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>

                  {values.onMaximumReached === 'TERMINATE_STRICT' && (
                    <label className="flex items-start gap-3 border border-danger/35 bg-danger/8 p-3 text-[10px] leading-4 text-danger">
                      <input
                        type="checkbox"
                        {...register('strictBudgetConfirmed')}
                        className="mt-0.5"
                      />
                      <span>
                        我确认：达到严格预算上限后，平台可能终止 Browser
                        Session，并中断当前登录状态。
                        {errors.strictBudgetConfirmed && (
                          <span className="mt-1 block font-medium">
                            {errors.strictBudgetConfirmed.message}
                          </span>
                        )}
                      </span>
                    </label>
                  )}

                  <SwitchRow
                    checked={values.remoteDesktop}
                    onChange={chooseRemoteDesktop}
                    title="需要远程桌面 / 人工交互"
                    detail="该需求会参与自动解析，但不会成为用户可见的资源等级。"
                  />

                  {canAdministerResources && (
                    <div className="border-t border-border-subtle pt-4">
                      <button
                        type="button"
                        onClick={() =>
                          setAdvancedResourcesOpen((current) => !current)
                        }
                        className="flex w-full items-center justify-between text-left text-[12px] font-medium text-text-secondary hover:text-text-primary"
                        aria-expanded={advancedResourcesOpen}
                      >
                        管理员高级资源设置
                        <ChevronRight
                          size={14}
                          className={cn(
                            'transition-transform',
                            advancedResourcesOpen && 'rotate-90'
                          )}
                        />
                      </button>
                      {advancedResourcesOpen && (
                        <div className="mt-4 space-y-4 border-l border-border-default pl-4">
                          <Field
                            label="运行环境"
                            hint="与资源策略分离。SYSTEM_MANAGED 为默认。"
                          >
                            <select
                              {...register('executionEnvironment')}
                              className="field-input"
                            >
                              <option value="SYSTEM_MANAGED">系统推荐</option>
                              <option value="CONTAINER">Container</option>
                              <option value="ENHANCED_SANDBOX">
                                Enhanced Sandbox
                              </option>
                              <option value="MICROVM">MicroVM</option>
                              <option value="NATIVE_OS">Native OS</option>
                            </select>
                          </Field>
                          <div className="grid gap-4 sm:grid-cols-2">
                            <Field label="最大 CPU (millicores)">
                              <input
                                type="number"
                                {...register('maximumCpuMillis')}
                                className="field-input font-mono"
                              />
                            </Field>
                            <Field label="最大内存 (MiB)">
                              <input
                                type="number"
                                {...register('maximumMemoryMib')}
                                className="field-input font-mono"
                              />
                            </Field>
                            <Field label="调整冷却 (秒)">
                              <input
                                type="number"
                                {...register('adjustmentCooldownSeconds')}
                                className="field-input font-mono"
                              />
                            </Field>
                            <Field label="缩容稳定窗口 (秒)">
                              <input
                                type="number"
                                {...register('scaleDownWindowSeconds')}
                                className="field-input font-mono"
                              />
                            </Field>
                          </div>
                          <SwitchRow
                            checked={values.allowMigration}
                            onChange={(checked) =>
                              setValue('allowMigration', checked)
                            }
                            title="允许自动迁移"
                            detail="仍需等待安全点并完成 State Resync 与业务恢复验证。"
                          />
                          <SwitchRow
                            checked={values.allowHibernate}
                            onChange={(checked) =>
                              setValue('allowHibernate', checked)
                            }
                            title="允许自动休眠"
                            detail="只在 Workspace 策略与空闲条件允许时执行。"
                          />
                          <SwitchRow
                            checked={values.blockMigrationDuringHumanTakeover}
                            onChange={(checked) =>
                              setValue(
                                'blockMigrationDuringHumanTakeover',
                                checked
                              )
                            }
                            title="HumanTakeover 时禁止迁移"
                            detail="建议保持开启，避免中断连续输入。"
                          />
                        </div>
                      )}
                    </div>
                  )}

                  <fieldset>
                    <legend className="mb-2 text-[13px] font-medium text-text-primary">
                      媒体等级
                    </legend>
                    <div className="grid gap-2 sm:grid-cols-2">
                      {mediaOptions.map((media) => (
                        <label
                          key={media.value}
                          className="flex cursor-pointer items-center justify-between rounded-[7px] border border-border-subtle bg-surface-2 px-3 py-2.5 has-[:checked]:border-accent/60 has-[:checked]:bg-accent-soft"
                        >
                          <input
                            type="radio"
                            value={media.value}
                            {...register('mediaClass')}
                            className="sr-only"
                          />
                          <span className="text-[12px] text-text-primary">
                            {media.label}
                          </span>
                          <span className="font-mono text-[10px] text-text-muted">
                            {media.detail}
                          </span>
                        </label>
                      ))}
                    </div>
                  </fieldset>
                </WizardStep>
              )}

              {step === 5 && (
                <WizardStep
                  eyebrow="05 · Capabilities"
                  title="添加扩展与 Agent 运行策略"
                  description="扩展来自真实资源画像；未知或高风险扩展会由后端提升隔离与资源要求。"
                >
                  <fieldset>
                    <legend className="mb-2 text-[13px] font-medium text-text-primary">
                      扩展
                    </legend>
                    {extensionsQuery.isLoading ? (
                      <LoadingBlock label="正在读取 Extension Profiles" />
                    ) : extensionsQuery.isError ? (
                      <QueryError label="无法读取 Extension Profiles" />
                    ) : extensionsQuery.data?.items.length ? (
                      <div className="space-y-2">
                        {extensionsQuery.data.items.map((extension) => (
                          <label
                            key={extension.extensionId}
                            className="flex cursor-pointer items-start gap-3 rounded-[7px] border border-border-subtle bg-surface-2 p-3 has-[:checked]:border-accent/60 has-[:checked]:bg-accent-soft"
                          >
                            <input
                              type="checkbox"
                              value={extension.extensionId}
                              {...register('extensionIds')}
                              className="mt-0.5 h-4 w-4 accent-accent"
                            />
                            <span className="min-w-0 flex-1">
                              <span className="flex items-center justify-between gap-3">
                                <span className="truncate text-[13px] font-medium text-text-primary">
                                  {extension.displayName}
                                </span>
                                <span className="font-mono text-[9px] text-text-muted">
                                  {extension.profileState}
                                </span>
                              </span>
                              <span className="mt-1 block font-mono text-[10px] text-text-muted">
                                {extension.extensionId} · weight{' '}
                                {extension.observedMultiplier.toFixed(2)}×
                              </span>
                            </span>
                          </label>
                        ))}
                      </div>
                    ) : (
                      <div className="border border-dashed border-border-default p-4 text-[12px] text-text-muted">
                        当前没有已登记的扩展画像。环境仍可不带扩展创建。
                      </div>
                    )}
                  </fieldset>

                  <SwitchRow
                    checked={values.agentEnabled}
                    onChange={(checked) => setValue('agentEnabled', checked)}
                    title="启用 Agent 运行能力"
                    detail="这里只选择策略边界；完整工作流在 Agent 任务中编排。"
                  />

                  {values.agentEnabled && (
                    <div className="grid gap-4 sm:grid-cols-2">
                      <Field label="Agent 策略">
                        <select
                          {...register('agentPolicy')}
                          className="field-input"
                        >
                          <option value="balanced">Balanced</option>
                          <option value="restricted">Restricted</option>
                          <option value="interactive">Interactive</option>
                        </select>
                      </Field>
                      <Field label="空闲回收（分钟）">
                        <input
                          type="number"
                          min={5}
                          max={1440}
                          {...register('idleTimeoutMinutes')}
                          className="field-input font-mono"
                        />
                      </Field>
                      <Field label="快照策略">
                        <select
                          {...register('snapshotPolicy')}
                          className="field-input"
                        >
                          <option value="on-stop">停止时保存</option>
                          <option value="periodic">周期保存</option>
                          <option value="manual">仅手动</option>
                        </select>
                      </Field>
                    </div>
                  )}

                  <SwitchRow
                    checked={values.humanTakeover}
                    onChange={(checked) => setValue('humanTakeover', checked)}
                    title="允许 Human Takeover"
                    detail="接管仍需要服务端操作所有权、角色与审计检查。"
                  />
                  <SwitchRow
                    checked={values.web3Workload}
                    onChange={(checked) => setValue('web3Workload', checked)}
                    title="Web3 / Crypto 工作负载"
                    detail="标记高风险能力，后端不会为了容量而降低隔离等级。"
                    tone="warning"
                  />
                </WizardStep>
              )}

              {step === 6 && (
                <WizardStep
                  eyebrow="06 · Review"
                  title={
                    createdSessionId
                      ? '环境记录已被 Control Plane 接受'
                      : '检查配置并创建权威记录'
                  }
                  description={
                    createdSessionId
                      ? 'Session 已进入 CREATED，启动和调度将在详情页单独发起。'
                      : '提交是真实写操作。创建成功不代表 Runtime 已启动。'
                  }
                >
                  {createdSessionId ? (
                    <div className="border border-success/30 bg-success/8 p-5">
                      <div className="flex items-start gap-4">
                        <CheckCircle2
                          size={24}
                          className="shrink-0 text-success"
                        />
                        <div className="min-w-0">
                          <h3 className="text-[15px] font-semibold text-text-primary">
                            Session CREATED
                          </h3>
                          <p className="mt-1 text-[12px] text-text-muted">
                            环境配置已持久化，尚未自动启动。
                          </p>
                          <p className="mt-3 break-all font-mono text-[11px] text-success">
                            {createdSessionId}
                          </p>
                          <button
                            type="button"
                            onClick={() => {
                              handleOpenChange(false);
                              navigate(`/environments/${createdSessionId}`);
                            }}
                            className="mt-5 inline-flex h-10 items-center gap-2 rounded-[7px] bg-accent px-4 text-[13px] font-semibold text-canvas"
                          >
                            查看环境详情
                            <ChevronRight size={15} />
                          </button>
                        </div>
                      </div>
                    </div>
                  ) : (
                    <>
                      <dl className="divide-y divide-border-subtle border border-border-subtle bg-surface-2 px-4">
                        <ReviewItem
                          label="环境"
                          value={values.name}
                          detail={[
                            groupsQuery.data?.items.find(
                              (group) => group.groupId === values.groupId
                            )?.name,
                            values.tagIds.length
                              ? values.tagIds
                                  .map(
                                    (tagId) =>
                                      tagsQuery.data?.items.find(
                                        (tag) => tag.tagId === tagId
                                      )?.name
                                  )
                                  .filter(Boolean)
                                  .join(', ')
                              : null,
                          ]
                            .filter(Boolean)
                            .join(' · ')}
                        />
                        <ReviewItem
                          label="Runtime 偏好"
                          value={values.runtimeBuildId}
                          mono
                          detail="平台策略最终裁决"
                        />
                        <ReviewItem
                          label="Profile"
                          value={
                            values.profileMode === 'empty'
                              ? '创建全新 Profile'
                              : values.profileId
                          }
                          mono={values.profileMode !== 'empty'}
                        />
                        <ReviewItem
                          label="业务恢复"
                          value={
                            values.applicationId
                              ? `${values.applicationId} · Application Ready Gate`
                              : '通用保守验证器'
                          }
                          mono={Boolean(values.applicationId)}
                        />
                        <ReviewItem
                          label="网络 / 区域"
                          value={`${values.networkMode} · ${values.region}`}
                          mono
                        />
                        <ReviewItem
                          label="资源策略"
                          value="AUTO · 自动分配"
                          mono
                          detail={`达到上限：${values.onMaximumReached}`}
                        />
                        <ReviewItem
                          label="执行环境"
                          value={values.executionEnvironment}
                          detail="与资源策略独立，Node 能力最终裁决"
                        />
                        <ReviewItem
                          label="交互 / 媒体"
                          value={`${values.remoteDesktop ? 'Remote Desktop' : 'No Desktop'} · ${values.mediaClass}`}
                        />
                        <ReviewItem
                          label="Agent"
                          value={
                            values.agentEnabled
                              ? `${values.agentPolicy} · takeover ${values.humanTakeover ? 'on' : 'off'}`
                              : 'disabled'
                          }
                          mono
                        />
                        <ReviewItem
                          label="扩展"
                          value={
                            values.extensionIds.length
                              ? `${values.extensionIds.length} selected`
                              : 'none'
                          }
                          mono
                        />
                        <ReviewItem
                          label="初始状态"
                          value="CREATED"
                          mono
                          detail="不会在前端伪造启动成功"
                        />
                      </dl>

                      <div className="flex items-start gap-3 border border-warning/25 bg-warning/8 p-4">
                        <CircleAlert
                          size={17}
                          className="mt-0.5 shrink-0 text-warning"
                        />
                        <p className="text-[11px] leading-5 text-text-secondary">
                          资源策略、运行环境、上限行为和工作负载声明都会进入真实
                          PostgreSQL 与 Placement 链路。内部 Resource Template
                          只由后端解析；前端不会提交固定
                          CPU、内存或伪造调整结果。
                        </p>
                      </div>

                      {createMutation.error && (
                        <div
                          className="border border-danger/25 bg-danger/8 p-4 text-[12px] text-danger"
                          role="alert"
                        >
                          <p>
                            {createMutation.error instanceof Error
                              ? createMutation.error.message
                              : '创建失败，请稍后重试。'}
                          </p>
                          {requestId && (
                            <p className="mt-2 font-mono">
                              Request ID: {requestId}
                            </p>
                          )}
                          <p className="mt-2 text-text-muted">
                            表单内容已保留，可修改后重试。
                          </p>
                        </div>
                      )}
                    </>
                  )}
                </WizardStep>
              )}
            </div>

            <div className="flex min-h-[68px] shrink-0 items-center justify-between gap-3 border-t border-border-subtle bg-surface-1 px-5 py-3 sm:px-7">
              <p className="hidden max-w-[300px] text-[10px] leading-4 text-text-muted sm:block">
                写操作等待后端真实响应；关闭向导会清除尚未提交的内容。
              </p>
              <div className="ml-auto flex items-center gap-2">
                {step > 1 && !createdSessionId && (
                  <button
                    type="button"
                    onClick={() => setStep((step - 1) as Step)}
                    disabled={createMutation.isPending}
                    className="inline-flex h-10 items-center gap-2 rounded-[7px] border border-border-default px-4 text-[13px] text-text-secondary hover:bg-surface-2 disabled:opacity-50"
                  >
                    <ArrowLeft size={14} />
                    上一步
                  </button>
                )}
                {step < 6 ? (
                  <button
                    type="button"
                    onClick={() => void advance()}
                    className="inline-flex h-10 items-center gap-2 rounded-[7px] bg-accent px-4 text-[13px] font-semibold text-canvas hover:bg-accent/90"
                  >
                    下一步
                    <ArrowRight size={14} />
                  </button>
                ) : !createdSessionId ? (
                  <button
                    type="button"
                    onClick={() => void submit()}
                    disabled={
                      createMutation.isPending ||
                      runtimeQuery.isError ||
                      enterpriseQuery.isError
                    }
                    className="inline-flex h-10 items-center gap-2 rounded-[7px] bg-accent px-4 text-[13px] font-semibold text-canvas hover:bg-accent/90 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    {createMutation.isPending ? (
                      <LoaderCircle size={14} className="animate-spin" />
                    ) : (
                      <Rocket size={14} />
                    )}
                    {createMutation.isPending ? '正在创建' : '确认创建'}
                  </button>
                ) : (
                  <Dialog.Close className="inline-flex h-10 items-center rounded-[7px] border border-border-default px-4 text-[13px] text-text-secondary hover:bg-surface-2">
                    完成
                  </Dialog.Close>
                )}
              </div>
            </div>
          </form>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function WizardStep({
  eyebrow,
  title,
  description,
  children,
}: {
  eyebrow: string;
  title: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <section className="mx-auto w-full max-w-[650px]">
      <p className="font-mono text-[10px] uppercase tracking-[0.16em] text-accent">
        {eyebrow}
      </p>
      <h2 className="mt-2 text-[19px] font-semibold tracking-[-0.01em] text-text-primary">
        {title}
      </h2>
      <p className="mt-1 max-w-[600px] text-[12px] leading-5 text-text-muted">
        {description}
      </p>
      <div className="mt-6 space-y-5">{children}</div>
    </section>
  );
}

function Field({
  label,
  hint,
  error,
  required,
  children,
}: {
  label: string;
  hint?: string;
  error?: string;
  required?: boolean;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 flex items-center gap-1 text-[13px] font-medium text-text-primary">
        {label}
        {required && <span className="text-danger">*</span>}
      </span>
      {children}
      {error ? (
        <span className="mt-1.5 block text-[11px] text-danger">{error}</span>
      ) : hint ? (
        <span className="mt-1.5 block text-[11px] leading-4 text-text-muted">
          {hint}
        </span>
      ) : null}
    </label>
  );
}

function ChoiceCard({
  checked,
  disabled,
  onChange,
  title,
  badge,
  description,
  meta,
}: {
  name: string;
  checked: boolean;
  disabled?: boolean;
  onChange: () => void;
  title: string;
  badge?: string;
  description: string;
  meta?: string;
}) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      disabled={disabled}
      onClick={onChange}
      className={cn(
        'w-full rounded-[8px] border p-4 text-left transition-colors',
        checked
          ? 'border-accent/60 bg-accent-soft'
          : 'border-border-subtle bg-surface-2 hover:border-border-default',
        disabled && 'cursor-not-allowed opacity-45'
      )}
    >
      <span className="flex items-center justify-between gap-3">
        <span className="text-[13px] font-semibold text-text-primary">
          {title}
        </span>
        {badge && (
          <span
            className={cn(
              'shrink-0 border px-2 py-0.5 font-mono text-[9px] uppercase',
              checked
                ? 'border-accent/30 text-accent'
                : 'border-border-default text-text-muted'
            )}
          >
            {badge}
          </span>
        )}
      </span>
      <span className="mt-2 block text-[11px] leading-4 text-text-muted">
        {description}
      </span>
      {meta && (
        <span className="mt-3 block truncate font-mono text-[10px] text-text-secondary">
          {meta}
        </span>
      )}
    </button>
  );
}

function SwitchRow({
  checked,
  onChange,
  title,
  detail,
  tone = 'accent',
}: {
  checked: boolean;
  onChange: (checked: boolean) => void;
  title: string;
  detail: string;
  tone?: 'accent' | 'warning';
}) {
  return (
    <label className="flex cursor-pointer items-start justify-between gap-4 rounded-[8px] border border-border-subtle bg-surface-2 p-4">
      <span>
        <span className="block text-[13px] font-medium text-text-primary">
          {title}
        </span>
        <span className="mt-1 block text-[11px] leading-4 text-text-muted">
          {detail}
        </span>
      </span>
      <input
        type="checkbox"
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        className={cn(
          'mt-0.5 h-4 w-4 shrink-0',
          tone === 'warning' ? 'accent-warning' : 'accent-accent'
        )}
      />
    </label>
  );
}

function UnavailableOption({
  title,
  detail,
}: {
  title: string;
  detail: string;
}) {
  return (
    <div
      className="flex items-start gap-3 rounded-[7px] border border-dashed border-border-subtle p-3 opacity-65"
      aria-disabled="true"
    >
      <CircleAlert size={15} className="mt-0.5 shrink-0 text-text-muted" />
      <div>
        <p className="text-[12px] font-medium text-text-secondary">{title}</p>
        <p className="mt-0.5 text-[10px] leading-4 text-text-muted">{detail}</p>
      </div>
    </div>
  );
}

function LoadingBlock({ label }: { label: string }) {
  return (
    <div className="flex h-20 items-center justify-center gap-2 border border-border-subtle bg-surface-2 text-[12px] text-text-muted">
      <LoaderCircle size={14} className="animate-spin text-accent" />
      {label}
    </div>
  );
}

function QueryError({ label }: { label: string }) {
  return (
    <div className="flex h-20 items-center justify-center gap-2 border border-danger/25 bg-danger/8 text-[12px] text-danger">
      <CircleAlert size={14} />
      {label}
    </div>
  );
}

function ReviewItem({
  label,
  value,
  detail,
  mono,
}: {
  label: string;
  value?: string;
  detail?: string;
  mono?: boolean;
}) {
  return (
    <div className="grid gap-1 py-3 sm:grid-cols-[150px_1fr] sm:gap-4">
      <dt className="text-[11px] text-text-muted">{label}</dt>
      <dd className="min-w-0 text-left sm:text-right">
        <span
          className={cn(
            'block break-words text-[12px] text-text-primary',
            mono && 'font-mono'
          )}
        >
          {value || '—'}
        </span>
        {detail && (
          <span className="mt-0.5 block text-[10px] text-text-muted">
            {detail}
          </span>
        )}
      </dd>
    </div>
  );
}
