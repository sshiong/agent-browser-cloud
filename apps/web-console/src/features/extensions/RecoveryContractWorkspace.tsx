import { zodResolver } from '@hookform/resolvers/zod';
import {
  AlertTriangle,
  Braces,
  CheckCircle2,
  FilePlus2,
  LoaderCircle,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Trash2,
} from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { z } from 'zod';
import { isSessionApiError } from '@/api/session';
import {
  useRecoveryContracts,
  useUpsertRecoveryContract,
} from '@/features/sessions/api/sessionQueries';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import { cn } from '@/shared/lib/utils';
import type {
  BusinessRecoveryAction,
  RecoveryContractView,
  RecoveryTargetIndicator,
} from '@/types/session';
import {
  emptyRecoveryContractForm,
  isChromiumExtensionId,
  isValidExpectedOrigin,
  isValidRoutePrefix,
  parseContractLines,
  recoveryContractRequest,
  recoveryContractToForm,
  type RecoveryContractFormValues,
} from './recoveryContractForm';

const applicationIdPattern = /^[a-zA-Z0-9_.-]{1,128}$/;
const extensionIdPattern = /^[a-zA-Z0-9_.-]{1,128}$/;
const targetRolePattern = /^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/;

const targetSchema = z.object({
  role: z
    .string()
    .trim()
    .min(1, '请输入语义角色')
    .regex(targetRolePattern, '角色只能包含字母、数字、_ 或 -'),
  name: z.string().trim().min(1, '请输入可访问名称').max(160),
});

const formSchema = z
  .object({
    applicationId: z
      .string()
      .trim()
      .regex(applicationIdPattern, '请输入合法 Application ID'),
    expectedOrigins: z.string(),
    readyRoutePrefixes: z.string(),
    loginRoutePrefixes: z.string(),
    requiredTargets: z.array(targetSchema).max(32),
    loginTargets: z.array(targetSchema).max(32),
    permissionDeniedTargets: z.array(targetSchema).max(32),
    accountMismatchTargets: z.array(targetSchema).max(32),
    requiredExtensionIds: z.string(),
    allowDepthLimited: z.boolean(),
    recoveryAction: z.enum([
      'NONE',
      'RELOAD',
      'NAVIGATE_HOME',
      'REOPEN_KNOWN_ROUTE',
      'REFRESH_SESSION',
      'RESTART_EXTENSION',
    ]),
    recoveryExtensionId: z.string(),
    maximumAutoRecovery: z.number().int().min(0).max(10),
    enabled: z.boolean(),
  })
  .superRefine((values, context) => {
    const origins = parseContractLines(values.expectedOrigins);
    if (origins.length === 0 || origins.length > 16) {
      context.addIssue({
        code: 'custom',
        path: ['expectedOrigins'],
        message: '需要 1—16 个 Origin',
      });
    } else if (origins.some((origin) => !isValidExpectedOrigin(origin))) {
      context.addIssue({
        code: 'custom',
        path: ['expectedOrigins'],
        message: 'Origin 只能包含 http(s)、主机和可选端口',
      });
    }

    for (const field of ['readyRoutePrefixes', 'loginRoutePrefixes'] as const) {
      const routes = parseContractLines(values[field]);
      if (
        routes.length > 32 ||
        routes.some((route) => !isValidRoutePrefix(route))
      ) {
        context.addIssue({
          code: 'custom',
          path: [field],
          message: '最多 32 项；必须以 / 开头且不能包含 ..、? 或 #',
        });
      }
    }

    const extensionIds = parseContractLines(values.requiredExtensionIds);
    if (
      extensionIds.length > 32 ||
      extensionIds.some((id) => !extensionIdPattern.test(id))
    ) {
      context.addIssue({
        code: 'custom',
        path: ['requiredExtensionIds'],
        message: '最多 32 项；Extension ID 格式不合法',
      });
    }

    const noAction = values.recoveryAction === 'NONE';
    if ((values.maximumAutoRecovery === 0) !== noAction) {
      context.addIssue({
        code: 'custom',
        path: ['maximumAutoRecovery'],
        message: noAction
          ? '未启用动作时预算必须为 0'
          : '启用自动动作时预算必须大于 0',
      });
    }

    if (values.recoveryAction === 'RESTART_EXTENSION') {
      const target = values.recoveryExtensionId.trim();
      if (!isChromiumExtensionId(target) || !extensionIds.includes(target)) {
        context.addIssue({
          code: 'custom',
          path: ['recoveryExtensionId'],
          message: '必须选择 Required Extensions 中的 32 位 Chromium ID',
        });
      }
    }
  });

const recoveryActions: Array<{
  value: BusinessRecoveryAction;
  label: string;
  detail: string;
}> = [
  {
    value: 'NONE',
    label: '不自动执行',
    detail: '只返回 Verdict，保持人工恢复',
  },
  { value: 'RELOAD', label: '重新加载页面', detail: '保留缓存的普通 Reload' },
  {
    value: 'REFRESH_SESSION',
    label: '忽略缓存刷新',
    detail: '执行 Page.reload(ignoreCache)',
  },
  {
    value: 'NAVIGATE_HOME',
    label: '返回契约首页',
    detail: '仅导航到首个 Origin 根路径',
  },
  {
    value: 'REOPEN_KNOWN_ROUTE',
    label: '重开已知路由',
    detail: '仅导航到契约允许的 Route Prefix',
  },
  {
    value: 'RESTART_EXTENSION',
    label: '重启受信 Extension',
    detail: '只在匹配的 Extension CDP Context 执行',
  },
];

export function RecoveryContractWorkspace() {
  const contractsQuery = useRecoveryContracts();
  const mutation = useUpsertRecoveryContract();
  const [selection, setSelection] = useState<string | null | undefined>(
    undefined
  );
  const contracts = useMemo(
    () => contractsQuery.data?.items ?? [],
    [contractsQuery.data?.items]
  );
  const selected = useMemo(
    () => contracts.find((item) => item.applicationId === selection),
    [contracts, selection]
  );

  useEffect(() => {
    if (selection === undefined && contractsQuery.isSuccess) {
      setSelection(contracts[0]?.applicationId ?? null);
    }
  }, [contracts, contractsQuery.isSuccess, selection]);

  if (contractsQuery.isLoading) {
    return (
      <div className="border border-border-subtle bg-surface-1">
        <LoadingPanel label="正在读取 Application Recovery Contracts" />
      </div>
    );
  }

  if (contractsQuery.isError) {
    return (
      <div className="border border-border-subtle bg-surface-1">
        <ErrorState
          error={contractsQuery.error}
          onRetry={() => contractsQuery.refetch()}
          title="无法加载恢复契约"
        />
      </div>
    );
  }

  return (
    <section className="border border-border-subtle bg-surface-1">
      <header className="flex flex-col gap-3 border-b border-border-subtle px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <ShieldCheck size={15} className="text-accent" />
            <h2 className="text-[13px] font-semibold text-text-primary">
              Application Recovery Contract
            </h2>
            <span className="bg-surface-3 px-2 py-0.5 font-mono text-[9px] text-text-muted">
              {contracts.length} REGISTERED
            </span>
          </div>
          <p className="mt-1 text-[10px] text-text-muted">
            版本化声明式 Ready Gate；不执行租户 JavaScript、正则表达式或任意 CDP
            Method。
          </p>
        </div>
        <button
          type="button"
          onClick={() => {
            mutation.reset();
            setSelection(null);
          }}
          className="inline-flex h-9 items-center justify-center gap-2 bg-accent px-3 text-[11px] font-semibold text-canvas hover:bg-accent/90"
        >
          <FilePlus2 size={14} />
          新建恢复契约
        </button>
      </header>

      <div className="grid min-h-[650px] xl:grid-cols-[280px_minmax(0,1fr)]">
        <ContractIndex
          contracts={contracts}
          selection={selection}
          onSelect={(applicationId) => {
            mutation.reset();
            setSelection(applicationId);
          }}
        />
        <ContractEditor
          key={selected?.contractId ?? 'new'}
          contract={selected}
          creating={selection === null}
          mutation={mutation}
          onSaved={(contract) => setSelection(contract.applicationId)}
          onConflictRefresh={() => {
            mutation.reset();
            void contractsQuery.refetch();
          }}
        />
      </div>
    </section>
  );
}

function ContractIndex({
  contracts,
  selection,
  onSelect,
}: {
  contracts: RecoveryContractView[];
  selection: string | null | undefined;
  onSelect: (applicationId: string) => void;
}) {
  return (
    <aside className="border-b border-border-subtle xl:border-b-0 xl:border-r">
      <div className="border-b border-border-subtle px-3 py-2 font-mono text-[9px] uppercase tracking-[0.12em] text-text-muted">
        Contract registry
      </div>
      {contracts.length === 0 ? (
        <EmptyState
          title="尚无恢复契约"
          description="创建后，Session 可将 Application Ready Gate 固化为一等绑定。"
        />
      ) : (
        <div className="max-h-[330px] overflow-y-auto xl:max-h-[610px]">
          {contracts.map((contract) => {
            const active = selection === contract.applicationId;
            return (
              <button
                type="button"
                key={contract.contractId}
                onClick={() => onSelect(contract.applicationId)}
                className={cn(
                  'group flex w-full items-start gap-3 border-b border-border-subtle px-3 py-3 text-left transition-colors',
                  active
                    ? 'bg-accent-soft'
                    : 'hover:bg-surface-2 focus-visible:bg-surface-2'
                )}
              >
                <span
                  className={cn(
                    'mt-1 h-2 w-2 shrink-0',
                    contract.enabled ? 'bg-success' : 'bg-text-muted'
                  )}
                  aria-hidden="true"
                />
                <span className="min-w-0 flex-1">
                  <span
                    className={cn(
                      'block truncate font-mono text-[11px] font-semibold',
                      active ? 'text-accent' : 'text-text-primary'
                    )}
                  >
                    {contract.applicationId}
                  </span>
                  <span className="mt-1 flex items-center justify-between gap-2 text-[9px] text-text-muted">
                    <span>
                      v{contract.version} · {contract.recoveryAction}
                    </span>
                    <span>{contract.maximumAutoRecovery} ATTEMPT</span>
                  </span>
                  <span className="mt-1 block truncate text-[9px] text-text-muted">
                    {contract.expectedOrigins.join(', ')}
                  </span>
                </span>
              </button>
            );
          })}
        </div>
      )}
    </aside>
  );
}

function ContractEditor({
  contract,
  creating,
  mutation,
  onSaved,
  onConflictRefresh,
}: {
  contract?: RecoveryContractView;
  creating: boolean;
  mutation: ReturnType<typeof useUpsertRecoveryContract>;
  onSaved: (contract: RecoveryContractView) => void;
  onConflictRefresh: () => void;
}) {
  const {
    register,
    handleSubmit,
    reset,
    setValue,
    watch,
    formState: { errors, isDirty },
  } = useForm<RecoveryContractFormValues>({
    resolver: zodResolver(formSchema),
    defaultValues: contract
      ? recoveryContractToForm(contract)
      : emptyRecoveryContractForm,
  });

  useEffect(() => {
    reset(
      contract ? recoveryContractToForm(contract) : emptyRecoveryContractForm
    );
  }, [contract, reset]);

  if (!contract && !creating) {
    return (
      <div className="flex min-h-[420px] items-center justify-center">
        <LoadingPanel label="正在定位契约版本" />
      </div>
    );
  }

  const values = watch();
  const action = values.recoveryAction;
  const extensionOptions = parseContractLines(
    values.requiredExtensionIds
  ).filter(isChromiumExtensionId);
  const requestId = isSessionApiError(mutation.error)
    ? mutation.error.body.requestId
    : undefined;
  const versionConflict =
    isSessionApiError(mutation.error) && mutation.error.status === 409;

  const submit = handleSubmit(async (formValues) => {
    const saved = await mutation.mutateAsync({
      applicationId: formValues.applicationId.trim(),
      body: recoveryContractRequest(formValues, contract?.version ?? 0),
    });
    reset(recoveryContractToForm(saved));
    onSaved(saved);
  });

  const setTargets = (
    field:
      | 'requiredTargets'
      | 'loginTargets'
      | 'permissionDeniedTargets'
      | 'accountMismatchTargets',
    targets: RecoveryTargetIndicator[]
  ) => setValue(field, targets, { shouldDirty: true, shouldValidate: true });

  return (
    <form onSubmit={submit} className="min-w-0">
      <header className="flex flex-col gap-3 border-b border-border-subtle px-4 py-3 sm:flex-row sm:items-center sm:justify-between">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <Braces size={14} className="text-accent" />
            <span className="font-mono text-[11px] font-semibold text-text-primary">
              {contract
                ? `${contract.applicationId} / v${contract.version}`
                : 'NEW_CONTRACT / v0'}
            </span>
            {isDirty && (
              <span className="bg-warning/10 px-2 py-0.5 text-[9px] text-warning">
                UNSAVED
              </span>
            )}
          </div>
          <p className="mt-1 text-[9px] text-text-muted">
            保存使用 expectedVersion CAS；并发更新会返回 409，不会覆盖较新版本。
          </p>
        </div>
        <button
          type="submit"
          disabled={mutation.isPending || !isDirty}
          className="inline-flex h-9 items-center justify-center gap-2 bg-accent px-4 text-[11px] font-semibold text-canvas disabled:cursor-not-allowed disabled:opacity-45"
        >
          {mutation.isPending ? (
            <LoaderCircle size={14} className="animate-spin" />
          ) : (
            <Save size={14} />
          )}
          {contract ? '发布新版本' : '创建契约'}
        </button>
      </header>

      <div className="space-y-5 p-4 sm:p-5">
        <section className="grid gap-4 lg:grid-cols-2">
          <Field
            label="Application ID"
            hint="创建后不可改名；Session 会固化绑定此 ID。"
            error={errors.applicationId?.message}
          >
            <input
              {...register('applicationId')}
              readOnly={Boolean(contract)}
              className="field-input font-mono read-only:cursor-not-allowed read-only:opacity-65"
              placeholder="crm.singapore"
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <ToggleField
              label="启用契约"
              detail="可供新 Session 绑定"
              checked={values.enabled}
              onChange={(checked) =>
                setValue('enabled', checked, { shouldDirty: true })
              }
            />
            <ToggleField
              label="接受受限状态"
              detail="Depth Limited 时允许警告放行"
              checked={values.allowDepthLimited}
              onChange={(checked) =>
                setValue('allowDepthLimited', checked, { shouldDirty: true })
              }
            />
          </div>
        </section>

        <section className="grid gap-4 lg:grid-cols-2">
          <Field
            label="Expected Origins"
            hint="每行一个，仅允许 Origin，不含 Path、Query 或 Fragment。"
            error={errors.expectedOrigins?.message}
          >
            <textarea
              {...register('expectedOrigins')}
              className="min-h-24 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 font-mono text-[11px] text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
              placeholder={
                'https://crm.example.com\nhttps://crm-sg.example.com'
              }
            />
          </Field>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field
              label="Ready Route Prefix"
              hint="可为空；每行一个。"
              error={errors.readyRoutePrefixes?.message}
            >
              <textarea
                {...register('readyRoutePrefixes')}
                className="min-h-24 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 font-mono text-[11px] text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
                placeholder={'/workspace\n/accounts'}
              />
            </Field>
            <Field
              label="Login Route Prefix"
              hint="命中后判定需登录。"
              error={errors.loginRoutePrefixes?.message}
            >
              <textarea
                {...register('loginRoutePrefixes')}
                className="min-h-24 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 font-mono text-[11px] text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
                placeholder={'/login\n/sign-in'}
              />
            </Field>
          </div>
        </section>

        <section>
          <div className="mb-2 flex items-center justify-between">
            <div>
              <h3 className="text-[11px] font-semibold text-text-primary">
                可访问性证据
              </h3>
              <p className="mt-0.5 text-[9px] text-text-muted">
                使用语义 Role + Accessible Name，不使用 CSS Selector。
              </p>
            </div>
            <span className="font-mono text-[9px] text-text-muted">
              BOUNDED DSL
            </span>
          </div>
          <div className="grid gap-px bg-border-subtle lg:grid-cols-2">
            <TargetListEditor
              title="Ready 必需目标"
              targets={values.requiredTargets}
              onChange={(targets) => setTargets('requiredTargets', targets)}
            />
            <TargetListEditor
              title="Login 目标"
              targets={values.loginTargets}
              onChange={(targets) => setTargets('loginTargets', targets)}
            />
            <TargetListEditor
              title="Permission Denied 目标"
              targets={values.permissionDeniedTargets}
              onChange={(targets) =>
                setTargets('permissionDeniedTargets', targets)
              }
            />
            <TargetListEditor
              title="Account Mismatch 目标"
              targets={values.accountMismatchTargets}
              onChange={(targets) =>
                setTargets('accountMismatchTargets', targets)
              }
            />
          </div>
        </section>

        <details
          className="group border border-border-subtle bg-surface-2"
          open
        >
          <summary className="flex cursor-pointer list-none items-center justify-between px-4 py-3">
            <span>
              <span className="block text-[11px] font-semibold text-text-primary">
                有界自动恢复
              </span>
              <span className="mt-0.5 block text-[9px] text-text-muted">
                只有声明式 Verdict 允许时才执行，随后必须 State Resync
                并二次验证。
              </span>
            </span>
            <span className="font-mono text-[9px] text-warning">
              SAFETY BUDGET
            </span>
          </summary>
          <div className="grid gap-4 border-t border-border-subtle p-4 lg:grid-cols-2">
            <Field
              label="Recovery Action"
              error={errors.recoveryAction?.message}
            >
              <select
                {...register('recoveryAction')}
                className="field-input"
                onChange={(event) => {
                  const next = event.target.value as BusinessRecoveryAction;
                  setValue('recoveryAction', next, {
                    shouldDirty: true,
                    shouldValidate: true,
                  });
                  setValue(
                    'maximumAutoRecovery',
                    next === 'NONE'
                      ? 0
                      : Math.max(values.maximumAutoRecovery, 1),
                    { shouldDirty: true, shouldValidate: true }
                  );
                  if (next !== 'RESTART_EXTENSION') {
                    setValue('recoveryExtensionId', '', {
                      shouldDirty: true,
                      shouldValidate: true,
                    });
                  }
                }}
              >
                {recoveryActions.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label} — {item.detail}
                  </option>
                ))}
              </select>
            </Field>
            <Field
              label="Maximum Auto Recovery"
              hint="每次迁移的持久尝试预算，范围 0—10。"
              error={errors.maximumAutoRecovery?.message}
            >
              <input
                {...register('maximumAutoRecovery', { valueAsNumber: true })}
                type="number"
                min={0}
                max={10}
                disabled={action === 'NONE'}
                className="field-input font-mono disabled:opacity-55"
              />
            </Field>

            <Field
              label="Required Extensions"
              hint="每行一个可信扩展 ID；迁移时会与 Placement 交叉校验。"
              error={errors.requiredExtensionIds?.message}
            >
              <textarea
                {...register('requiredExtensionIds')}
                className="min-h-20 w-full resize-y border border-border-subtle bg-surface-2 px-3 py-2 font-mono text-[11px] text-text-primary outline-none placeholder:text-text-muted focus:border-accent"
                placeholder="jdgnleokimdbblcflcfcohbinohmmmlb"
              />
            </Field>

            {action === 'RESTART_EXTENSION' ? (
              <Field
                label="Recovery Extension"
                hint="必须同时属于 Required Extensions。"
                error={errors.recoveryExtensionId?.message}
              >
                <select
                  {...register('recoveryExtensionId')}
                  className="field-input font-mono"
                >
                  <option value="">选择受信 Chromium Extension</option>
                  {extensionOptions.map((id) => (
                    <option key={id} value={id}>
                      {id}
                    </option>
                  ))}
                </select>
              </Field>
            ) : (
              <div className="flex items-center border border-border-subtle bg-surface-1 px-3 py-2">
                <CheckCircle2 size={14} className="mr-2 text-success" />
                <p className="text-[10px] text-text-muted">
                  当前动作不接受 Extension 目标，前端不会提交该字段。
                </p>
              </div>
            )}
          </div>
        </details>

        {mutation.error && (
          <div
            role="alert"
            className="flex items-start gap-3 border border-danger/30 bg-danger/8 px-3 py-2.5 text-[11px] text-danger"
          >
            <AlertTriangle size={14} className="mt-0.5 shrink-0" />
            <div>
              <p>
                {versionConflict
                  ? '契约已被其他管理员更新。请刷新列表后重新应用本次修改。'
                  : mutation.error.message}
              </p>
              {requestId && (
                <p className="mt-1 font-mono text-[9px] text-text-muted">
                  Request ID · {requestId}
                </p>
              )}
            </div>
            {versionConflict && (
              <button
                type="button"
                onClick={onConflictRefresh}
                className="ml-auto inline-flex h-7 shrink-0 items-center gap-1 border border-danger/35 px-2 text-[9px]"
              >
                <RefreshCw size={11} />
                刷新
              </button>
            )}
          </div>
        )}
      </div>
    </form>
  );
}

function TargetListEditor({
  title,
  targets,
  onChange,
}: {
  title: string;
  targets: RecoveryTargetIndicator[];
  onChange: (targets: RecoveryTargetIndicator[]) => void;
}) {
  const invalid = targets.some(
    (target) =>
      !targetRolePattern.test(target.role.trim()) ||
      !target.name.trim() ||
      target.name.trim().length > 160
  );
  const update = (
    index: number,
    field: keyof RecoveryTargetIndicator,
    value: string
  ) =>
    onChange(
      targets.map((target, current) =>
        current === index ? { ...target, [field]: value } : target
      )
    );

  return (
    <div className="bg-surface-2 p-3">
      <div className="mb-2 flex items-center justify-between">
        <span className="text-[10px] font-medium text-text-secondary">
          {title}
        </span>
        <button
          type="button"
          onClick={() => onChange([...targets, { role: 'status', name: '' }])}
          disabled={targets.length >= 32}
          className="inline-flex h-7 items-center gap-1 px-2 text-[9px] text-accent hover:bg-accent-soft disabled:opacity-40"
        >
          <Plus size={11} />
          添加证据
        </button>
      </div>
      {targets.length === 0 ? (
        <div className="flex min-h-14 items-center justify-center border border-dashed border-border-default text-[9px] text-text-muted">
          未配置该类语义目标
        </div>
      ) : (
        <div className="space-y-1.5">
          {targets.map((target, index) => (
            <div
              key={`${title}-${index}`}
              className="grid grid-cols-[112px_minmax(0,1fr)_28px] gap-1.5"
            >
              <input
                value={target.role}
                onChange={(event) => update(index, 'role', event.target.value)}
                className="field-input min-w-0 font-mono"
                aria-label={`${title} ${index + 1} 角色`}
                placeholder="status"
              />
              <input
                value={target.name}
                onChange={(event) => update(index, 'name', event.target.value)}
                className="field-input min-w-0"
                aria-label={`${title} ${index + 1} 可访问名称`}
                placeholder="Recovered workspace"
              />
              <button
                type="button"
                onClick={() =>
                  onChange(targets.filter((_, current) => current !== index))
                }
                className="flex h-9 items-center justify-center text-text-muted hover:bg-danger/10 hover:text-danger"
                aria-label={`删除 ${title} ${index + 1}`}
              >
                <Trash2 size={12} />
              </button>
            </div>
          ))}
        </div>
      )}
      {invalid && (
        <p className="mt-1.5 text-[9px] text-danger">
          每条证据都需要合法语义角色和 1—160 字符的可访问名称。
        </p>
      )}
    </div>
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
      <span className="mb-1.5 flex items-baseline justify-between gap-3">
        <span className="text-[10px] font-semibold text-text-secondary">
          {label}
        </span>
        {hint && (
          <span className="text-right text-[9px] text-text-muted">{hint}</span>
        )}
      </span>
      {children}
      {error && (
        <span className="mt-1 block text-[10px] text-danger">{error}</span>
      )}
    </label>
  );
}

function ToggleField({
  label,
  detail,
  checked,
  onChange,
}: {
  label: string;
  detail: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="flex min-h-[68px] cursor-pointer items-center justify-between gap-3 border border-border-subtle bg-surface-2 px-3 py-2">
      <span>
        <span className="block text-[10px] font-medium text-text-primary">
          {label}
        </span>
        <span className="mt-1 block text-[9px] text-text-muted">{detail}</span>
      </span>
      <span
        className={cn(
          'flex h-6 w-10 shrink-0 items-center border p-0.5 transition-colors',
          checked
            ? 'border-accent/40 bg-accent-soft'
            : 'border-border-default bg-surface-3'
        )}
      >
        <input
          type="checkbox"
          checked={checked}
          onChange={(event) => onChange(event.target.checked)}
          className="sr-only"
        />
        <span
          className={cn(
            'h-4 w-4 transition-transform',
            checked ? 'translate-x-4 bg-accent' : 'translate-x-0 bg-text-muted'
          )}
        />
      </span>
    </label>
  );
}
