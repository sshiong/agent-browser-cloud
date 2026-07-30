import { useEffect, useMemo, useState } from 'react';
import type { FormEvent, ReactNode } from 'react';
import {
  AlertTriangle,
  ArrowRight,
  Ban,
  CheckCircle2,
  ChevronRight,
  CircleDot,
  FileWarning,
  LoaderCircle,
  LockKeyhole,
  MousePointerClick,
  Plus,
  RefreshCw,
  Route,
  ScrollText,
  ShieldCheck,
  TextCursorInput,
  Timer,
  Trash2,
  UserRoundCheck,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import {
  useBrowserState,
  useSessionResourceStream,
  useSessions,
} from '@/features/sessions/api/sessionQueries';
import {
  useAcceptAgentHandoff,
  useAgentTasks,
  useApproveAgentTask,
  useCreateAgentTask,
  useExecuteAgentTask,
  useRejectAgentHandoff,
  useRejectAgentTask,
} from '@/features/automation/agentQueries';
import { cn } from '@/shared/lib/utils';
import type {
  AgentRiskClass,
  CreateAgentActionRequest,
  AgentTaskView,
  InstructionSourceType,
} from '@/types/agent';
import type {
  AgentPolicy,
  BrowserStateView,
  InteractiveTargetView,
} from '@/types/session';

const inputClass =
  'h-9 w-full rounded-[6px] border border-border-default bg-surface-2 px-3 text-[11px] text-text-primary outline-none transition-colors placeholder:text-text-muted focus:border-accent/60';

const riskLabels: Record<AgentRiskClass, string> = {
  R0_READ_ONLY: 'R0 只读',
  R1_LOW_RISK_CHANGE: 'R1 低风险',
  R2_DATA_CHANGE: 'R2 数据变更',
  R3_ACCOUNT_CHANGE: 'R3 账号变更',
  R4_FINANCIAL: 'R4 财务',
  R5_SECURITY: 'R5 安全',
};

const sourceOptions: Array<{
  value: InstructionSourceType;
  label: string;
}> = [
  { value: 'WEB_CONTENT', label: '网页内容' },
  { value: 'EMAIL', label: '邮件' },
  { value: 'DOCUMENT', label: '文档' },
  { value: 'APPLICATION_DATA', label: '应用数据' },
  { value: 'THIRD_PARTY_WIDGET', label: '第三方组件' },
];

type DraftAction = CreateAgentActionRequest & { clientId: string };

const actionOptions: Array<{
  value: DraftAction['toolId'];
  label: string;
}> = [
  { value: 'CLICK_TARGET', label: '点击目标' },
  { value: 'TYPE_TEXT', label: '输入文本' },
  { value: 'SCROLL', label: '滚动页面' },
  { value: 'WAIT_FOR', label: '等待状态' },
  { value: 'REQUEST_HUMAN_TAKEOVER', label: '请求人工接管' },
];

const agentPolicyBudgets: Record<
  AgentPolicy,
  {
    defaultMaxActions: number;
    maximumMaxActions: number;
    replanBudget: number;
  }
> = {
  DISABLED: { defaultMaxActions: 1, maximumMaxActions: 1, replanBudget: 0 },
  RESTRICTED: { defaultMaxActions: 5, maximumMaxActions: 6, replanBudget: 0 },
  BALANCED: { defaultMaxActions: 8, maximumMaxActions: 12, replanBudget: 1 },
  INTERACTIVE: {
    defaultMaxActions: 12,
    maximumMaxActions: 20,
    replanBudget: 2,
  },
};

export function AutomationPage() {
  const tasksQuery = useAgentTasks();
  const sessionsQuery = useSessions({ state: 'RUNNING', limit: 100 });
  const createTask = useCreateAgentTask();
  const executeTask = useExecuteAgentTask();
  const approveTask = useApproveAgentTask();
  const rejectTask = useRejectAgentTask();
  const acceptHandoff = useAcceptAgentHandoff();
  const rejectHandoff = useRejectAgentHandoff();
  const [selectedTaskId, setSelectedTaskId] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [goal, setGoal] = useState('');
  const [startUrl, setStartUrl] = useState('');
  const [allowedDomains, setAllowedDomains] = useState('');
  const [externalContent, setExternalContent] = useState('');
  const [sourceType, setSourceType] =
    useState<InstructionSourceType>('WEB_CONTENT');
  const [actions, setActions] = useState<DraftAction[]>([]);
  const browserState = useBrowserState(sessionId, Boolean(sessionId));
  useSessionResourceStream(sessionId, Boolean(sessionId));

  const tasks = tasksQuery.data?.items ?? [];
  const selectedTask =
    tasks.find((task) => task.taskId === selectedTaskId) ?? tasks[0];
  const selectedSession = sessionsQuery.data?.items.find(
    (session) => session.sessionId === sessionId
  );
  const agentPolicy = selectedSession?.agentPolicy ?? 'BALANCED';
  const policyBudget = agentPolicyBudgets[agentPolicy];
  const permittedActionOptions = actionOptions.filter((option) => {
    if (agentPolicy === 'DISABLED') return false;
    if (
      agentPolicy === 'RESTRICTED' &&
      !['WAIT_FOR', 'REQUEST_HUMAN_TAKEOVER'].includes(option.value)
    ) {
      return false;
    }
    return !(
      option.value === 'REQUEST_HUMAN_TAKEOVER' &&
      selectedSession?.humanTakeoverEnabled === false
    );
  });

  useEffect(() => {
    if (!sessionId && sessionsQuery.data?.items[0]) {
      setSessionId(sessionsQuery.data.items[0].sessionId);
    }
  }, [sessionId, sessionsQuery.data]);

  useEffect(() => {
    if (allowedDomains.trim() || !browserState.data?.url) return;
    try {
      setAllowedDomains(new URL(browserState.data.url).hostname);
    } catch {
      // Node URL 仍可能是浏览器内部页；此时必须由用户明确填写授权域名。
    }
  }, [allowedDomains, browserState.data?.url]);

  const plannedCount = tasks.filter((task) => task.state === 'PLANNED').length;
  const completedCount = tasks.filter(
    (task) => task.state === 'COMPLETED'
  ).length;
  const blockedCount = tasks.filter((task) => task.state === 'BLOCKED').length;
  const securityEventCount = tasks.reduce(
    (total, task) => total + task.securityEvents.length,
    0
  );
  const navigationConflict = Boolean(startUrl.trim() && actions.length);
  const actionsValid = actions.every((action) =>
    isActionComplete(action, browserState.data?.targetRevision)
  );
  const endsWithHandoff = actions.at(-1)?.toolId === 'REQUEST_HUMAN_TAKEOVER';
  const requiredActionBudget =
    (startUrl.trim() ? 4 : 3) + actions.length - (endsWithHandoff ? 2 : 0);
  const policyConflict =
    agentPolicy === 'DISABLED'
      ? '该 Session 在创建时已禁用 Agent，服务端会拒绝所有计划。'
      : startUrl.trim() && agentPolicy === 'RESTRICTED'
        ? 'Restricted 策略禁止 Agent 导航；请在浏览器中先打开目标页面。'
        : actions.some(
              (action) =>
                !permittedActionOptions.some(
                  (option) => option.value === action.toolId
                )
            )
          ? '当前 Session 策略不允许已有动作，请移除受限动作后再提交。'
          : requiredActionBudget > policyBudget.maximumMaxActions
            ? `计划需要 ${requiredActionBudget} 个动作，超过 ${agentPolicy} 上限 ${policyBudget.maximumMaxActions}。`
            : '';

  async function submit(event: FormEvent) {
    event.preventDefault();
    const domains = allowedDomains
      .split(/[\s,]+/)
      .map((domain) => domain.trim())
      .filter(Boolean);
    const task = await createTask.mutateAsync({
      sessionId,
      request: {
        goal,
        startUrl: startUrl.trim() || undefined,
        allowedDomains: domains,
        maxActions: Math.max(
          policyBudget.defaultMaxActions,
          requiredActionBudget
        ),
        replanBudget: policyBudget.replanBudget,
        contextSources: externalContent.trim()
          ? [
              {
                sourceId: `console-${crypto.randomUUID()}`,
                sourceType,
                classification: 'PUBLIC',
                content: externalContent,
              },
            ]
          : [],
        actions: actions.map(toActionRequest),
      },
    });
    setSelectedTaskId(task.taskId);
    setGoal('');
    setExternalContent('');
    setActions([]);
  }

  return (
    <div>
      <TopContextBar
        title="Agent 执行控制台"
        subtitle="结构化动作、权威状态绑定与人工治理闭环"
      />

      <div className="border-b border-border-subtle px-6 py-3">
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-[11px]">
          <Signal label="可执行计划" value={plannedCount} tone="text-success" />
          <Signal label="已验证" value={completedCount} tone="text-accent" />
          <Signal label="已拦截" value={blockedCount} tone="text-danger" />
          <Signal
            label="安全事件"
            value={securityEventCount}
            tone="text-warning"
          />
          <span className="ml-auto inline-flex items-center gap-1.5 font-mono text-[10px] uppercase tracking-[0.12em] text-warning">
            <CircleDot size={10} />
            Node actions live · durable step recovery
          </span>
        </div>
      </div>

      <div className="grid min-h-[calc(100vh-122px)] xl:grid-cols-[minmax(0,1.7fr)_390px]">
        <section className="min-w-0 border-b border-border-subtle xl:border-b-0 xl:border-r">
          <div className="flex h-12 items-center justify-between border-b border-border-subtle px-5">
            <div>
              <h2 className="text-[12px] font-semibold text-text-primary">
                计划审查队列
              </h2>
              <p className="text-[10px] text-text-muted">
                外部内容只作为数据，不具备指令权限
              </p>
            </div>
            <button
              type="button"
              onClick={() => tasksQuery.refetch()}
              className="inline-flex h-7 items-center gap-1.5 rounded-[6px] border border-border-default px-2 text-[10px] text-text-secondary transition-colors hover:text-text-primary"
            >
              <RefreshCw size={11} />
              刷新
            </button>
          </div>

          {tasksQuery.isLoading ? (
            <LoadingPanel label="正在读取 Agent 任务" />
          ) : tasksQuery.isError ? (
            <ErrorState
              error={tasksQuery.error}
              onRetry={() => tasksQuery.refetch()}
              title="Agent 任务不可用"
            />
          ) : tasks.length === 0 ? (
            <EmptyState
              title="尚无安全计划"
              description="从右侧创建第一个受限任务。系统会先完成 Intent Guard 与 Plan Validation，不会直接执行。"
            />
          ) : (
            <div className="grid min-h-[580px] lg:grid-cols-[minmax(300px,0.82fr)_minmax(0,1.18fr)]">
              <div className="border-b border-border-subtle lg:border-b-0 lg:border-r">
                {tasks.map((task) => (
                  <TaskRow
                    key={task.taskId}
                    task={task}
                    selected={task.taskId === selectedTask?.taskId}
                    onSelect={() => setSelectedTaskId(task.taskId)}
                  />
                ))}
              </div>
              {selectedTask && (
                <TaskInspector
                  task={selectedTask}
                  onExecute={() => executeTask.mutate(selectedTask.taskId)}
                  isExecuting={
                    executeTask.isPending &&
                    executeTask.variables === selectedTask.taskId
                  }
                  executionError={
                    executeTask.isError ? executeTask.error : undefined
                  }
                  onApprove={() => approveTask.mutate(selectedTask.taskId)}
                  onReject={() => rejectTask.mutate(selectedTask.taskId)}
                  onAcceptHandoff={() =>
                    acceptHandoff.mutate(selectedTask.taskId)
                  }
                  onRejectHandoff={() =>
                    rejectHandoff.mutate(selectedTask.taskId)
                  }
                  governancePending={
                    approveTask.isPending ||
                    rejectTask.isPending ||
                    acceptHandoff.isPending ||
                    rejectHandoff.isPending
                  }
                  governanceError={
                    approveTask.error ||
                    rejectTask.error ||
                    acceptHandoff.error ||
                    rejectHandoff.error
                  }
                />
              )}
            </div>
          )}
        </section>

        <aside className="bg-surface-1">
          <div className="border-b border-border-subtle px-5 py-4">
            <div className="flex items-start gap-3">
              <div className="mt-0.5 flex h-8 w-8 items-center justify-center rounded-[7px] bg-accent-soft text-accent">
                <Plus size={15} />
              </div>
              <div>
                <h2 className="text-[13px] font-semibold text-text-primary">
                  创建受限计划
                </h2>
                <p className="mt-0.5 text-[10px] leading-4 text-text-muted">
                  动作必须绑定当前 Target Revision；敏感输入目标不可被 Agent
                  使用。
                </p>
              </div>
            </div>
          </div>

          <form onSubmit={submit} className="space-y-4 p-5">
            <Field label="运行中的 Session" required>
              <select
                value={sessionId}
                onChange={(event) => setSessionId(event.target.value)}
                required
                className={inputClass}
              >
                <option value="">选择 Session</option>
                {sessionsQuery.data?.items.map((session) => (
                  <option key={session.sessionId} value={session.sessionId}>
                    {session.displayName} · {session.agentPolicy ?? 'BALANCED'}{' '}
                    · {session.sessionId.slice(-6)}
                  </option>
                ))}
              </select>
            </Field>

            {selectedSession && (
              <div className="border border-border-subtle bg-surface-2/55 px-3 py-2">
                <div className="flex items-center justify-between gap-3">
                  <span className="text-[10px] text-text-muted">
                    Session Agent Policy
                  </span>
                  <span className="font-mono text-[10px] font-semibold text-accent">
                    {agentPolicy}
                  </span>
                </div>
                <p className="mt-1 text-[9px] leading-4 text-text-muted">
                  动作上限 {policyBudget.maximumMaxActions} · Replan{' '}
                  {policyBudget.replanBudget}
                  {selectedSession.humanTakeoverEnabled === false
                    ? ' · HumanTakeover disabled'
                    : ''}
                </p>
              </div>
            )}

            <Field label="用户目标" required>
              <textarea
                value={goal}
                onChange={(event) => setGoal(event.target.value)}
                required
                maxLength={2000}
                rows={3}
                placeholder="例如：打开授权页面并总结当前内容"
                className={cn(inputClass, 'min-h-20 resize-y py-2 leading-4')}
              />
            </Field>

            <Field label="起始 URL">
              <input
                value={startUrl}
                onChange={(event) => setStartUrl(event.target.value)}
                disabled={
                  agentPolicy === 'DISABLED' || agentPolicy === 'RESTRICTED'
                }
                placeholder="https://example.com/dashboard"
                className={cn(
                  inputClass,
                  'font-mono disabled:cursor-not-allowed disabled:opacity-40'
                )}
              />
            </Field>

            <Field label="授权域名" required hint="精确匹配">
              <input
                value={allowedDomains}
                onChange={(event) => setAllowedDomains(event.target.value)}
                required
                placeholder="example.com"
                className={cn(inputClass, 'font-mono')}
              />
            </Field>

            <StateBindingPanel
              isLoading={browserState.isLoading}
              state={browserState.data}
            />

            <div className="border-t border-border-subtle pt-4">
              <div className="mb-3 flex items-start justify-between gap-3">
                <div>
                  <p className="text-[10px] font-medium uppercase tracking-[0.08em] text-text-muted">
                    结构化动作
                  </p>
                  <p className="mt-1 text-[10px] leading-4 text-text-muted">
                    每个点击/输入都在创建时绑定当前目标版本。
                  </p>
                </div>
                <button
                  type="button"
                  disabled={
                    permittedActionOptions.length === 0 ||
                    requiredActionBudget + 1 > policyBudget.maximumMaxActions ||
                    actions.at(-1)?.toolId === 'REQUEST_HUMAN_TAKEOVER'
                  }
                  onClick={() => {
                    const option = permittedActionOptions[0];
                    if (!option) return;
                    setActions((current) => [
                      ...current,
                      createDraftAction(
                        option.value,
                        browserState.data?.targetRevision
                      ),
                    ]);
                  }}
                  className="inline-flex h-7 shrink-0 items-center gap-1 rounded-[6px] border border-border-default px-2 text-[10px] text-text-secondary transition-colors hover:border-accent/40 hover:text-accent disabled:cursor-not-allowed disabled:opacity-35"
                >
                  <Plus size={11} />
                  添加
                </button>
              </div>

              {actions.length === 0 ? (
                <div className="border border-dashed border-border-default bg-surface-2/40 px-3 py-4 text-center text-[10px] text-text-muted">
                  无写动作；计划仅执行状态读取与验证。
                </div>
              ) : (
                <div className="space-y-2">
                  {actions.map((action, index) => (
                    <ActionEditor
                      key={action.clientId}
                      index={index}
                      action={action}
                      targets={browserState.data?.targets ?? []}
                      targetRevision={browserState.data?.targetRevision}
                      options={permittedActionOptions}
                      onChange={(next) =>
                        setActions((current) =>
                          current.map((item) =>
                            item.clientId === action.clientId ? next : item
                          )
                        )
                      }
                      onRemove={() =>
                        setActions((current) =>
                          current.filter(
                            (item) => item.clientId !== action.clientId
                          )
                        )
                      }
                    />
                  ))}
                </div>
              )}

              {navigationConflict && (
                <p className="mt-2 flex gap-1.5 text-[10px] leading-4 text-warning">
                  <AlertTriangle className="mt-0.5 shrink-0" size={11} />
                  当前版本要求先导航、重采集状态，再创建绑定动作的任务。
                </p>
              )}
              {policyConflict && (
                <p
                  role="alert"
                  className="mt-2 flex gap-1.5 text-[10px] leading-4 text-warning"
                >
                  <LockKeyhole className="mt-0.5 shrink-0" size={11} />
                  {policyConflict}
                </p>
              )}
            </div>

            <details className="group border-t border-border-subtle pt-4">
              <summary className="flex cursor-pointer list-none items-center justify-between text-[11px] font-medium text-text-secondary">
                外部上下文安全测试
                <ChevronRight
                  size={13}
                  className="transition-transform group-open:rotate-90"
                />
              </summary>
              <div className="mt-3 space-y-3">
                <Field label="来源类型">
                  <select
                    value={sourceType}
                    onChange={(event) =>
                      setSourceType(event.target.value as InstructionSourceType)
                    }
                    className={inputClass}
                  >
                    {sourceOptions.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="不可信正文" hint="仅保留 Hash 与规则命中">
                  <textarea
                    value={externalContent}
                    onChange={(event) => setExternalContent(event.target.value)}
                    rows={4}
                    maxLength={10000}
                    placeholder="粘贴网页、邮件或文档片段以验证 Injection 防护"
                    className={cn(
                      inputClass,
                      'min-h-24 resize-y py-2 leading-4'
                    )}
                  />
                </Field>
              </div>
            </details>

            {createTask.isError && (
              <div
                role="alert"
                className="flex gap-2 border border-danger/30 bg-danger/8 p-3 text-[11px] leading-4 text-danger"
              >
                <AlertTriangle className="mt-0.5 shrink-0" size={13} />
                {createTask.error instanceof Error
                  ? createTask.error.message
                  : '创建任务失败'}
              </div>
            )}

            <button
              type="submit"
              disabled={
                createTask.isPending ||
                !sessionId ||
                !goal.trim() ||
                !allowedDomains.trim() ||
                navigationConflict ||
                Boolean(policyConflict) ||
                !actionsValid
              }
              className="inline-flex h-9 w-full items-center justify-center gap-2 rounded-[7px] bg-accent px-4 text-[12px] font-semibold text-canvas transition-opacity disabled:cursor-not-allowed disabled:opacity-40"
            >
              {createTask.isPending ? (
                <LoaderCircle className="animate-spin" size={14} />
              ) : (
                <ShieldCheck size={14} />
              )}
              运行安全校验并生成计划
            </button>
          </form>
        </aside>
      </div>
    </div>
  );
}

function StateBindingPanel({
  state,
  isLoading,
}: {
  state?: BrowserStateView | null;
  isLoading: boolean;
}) {
  if (isLoading) {
    return (
      <div className="border border-border-subtle bg-surface-2/50 p-3 text-[10px] text-text-muted">
        正在读取权威 Browser State…
      </div>
    );
  }
  if (!state) {
    return (
      <div className="border border-warning/25 bg-warning/5 p-3 text-[10px] leading-4 text-warning">
        当前 Session 尚无可执行状态；Click / Type / Wait Target 无法绑定。
      </div>
    );
  }
  return (
    <div className="border border-accent/20 bg-accent/5 p-3">
      <div className="flex items-center justify-between gap-3">
        <span className="inline-flex items-center gap-1.5 text-[10px] font-medium text-accent">
          <CircleDot size={10} />
          Authority state bound
        </span>
        <span className="font-mono text-[9px] text-text-muted">
          v{state.stateVersion} / target r{state.targetRevision}
        </span>
      </div>
      <p className="mt-2 truncate font-mono text-[9px] text-text-secondary">
        {state.url}
      </p>
      <div className="mt-2 flex gap-3 font-mono text-[8px] uppercase tracking-[0.08em] text-text-muted">
        <span>{state.stateQuality}</span>
        <span>{state.targets.length} targets</span>
        <span>
          {state.targets.filter((target) => target.sensitive).length} sensitive
        </span>
      </div>
    </div>
  );
}

function ActionEditor({
  index,
  action,
  targets,
  targetRevision,
  options,
  onChange,
  onRemove,
}: {
  index: number;
  action: DraftAction;
  targets: InteractiveTargetView[];
  targetRevision?: number;
  options: typeof actionOptions;
  onChange: (action: DraftAction) => void;
  onRemove: () => void;
}) {
  const actionableTargets = targets.filter(
    (target) => target.visible && target.enabled && target.bounds
  );
  const icon =
    action.toolId === 'CLICK_TARGET' ? (
      <MousePointerClick size={12} />
    ) : action.toolId === 'TYPE_TEXT' ? (
      <TextCursorInput size={12} />
    ) : action.toolId === 'SCROLL' ? (
      <ScrollText size={12} />
    ) : action.toolId === 'WAIT_FOR' ? (
      <Timer size={12} />
    ) : (
      <UserRoundCheck size={12} />
    );

  function switchTool(toolId: DraftAction['toolId']) {
    const base: DraftAction = { clientId: action.clientId, toolId };
    if (toolId === 'CLICK_TARGET' || toolId === 'TYPE_TEXT') {
      base.targetRevision = targetRevision;
    }
    if (toolId === 'SCROLL') base.scrollDeltaY = 600;
    if (toolId === 'WAIT_FOR') {
      base.waitCondition = 'STATE_STABLE';
      base.timeoutMs = 5000;
    }
    onChange(base);
  }

  return (
    <div className="border border-border-subtle bg-surface-2/55 p-3">
      <div className="flex items-center gap-2">
        <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-[5px] bg-surface-3 font-mono text-[9px] text-accent">
          {String(index + 1).padStart(2, '0')}
        </span>
        <span className="text-accent">{icon}</span>
        <select
          aria-label={`动作 ${index + 1} 类型`}
          value={action.toolId}
          onChange={(event) =>
            switchTool(event.target.value as DraftAction['toolId'])
          }
          className={cn(inputClass, 'h-7 flex-1 py-0')}
        >
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </select>
        <button
          type="button"
          onClick={onRemove}
          aria-label={`删除动作 ${index + 1}`}
          className="flex h-7 w-7 items-center justify-center rounded-[5px] text-text-muted transition-colors hover:bg-danger/10 hover:text-danger"
        >
          <Trash2 size={12} />
        </button>
      </div>

      {(action.toolId === 'CLICK_TARGET' || action.toolId === 'TYPE_TEXT') && (
        <div className="mt-3 space-y-2">
          <select
            aria-label={`动作 ${index + 1} 目标`}
            value={action.targetRef ?? ''}
            onChange={(event) =>
              onChange({
                ...action,
                targetRef: event.target.value || undefined,
                targetRevision,
              })
            }
            className={cn(inputClass, 'font-mono')}
          >
            <option value="">选择当前状态目标</option>
            {actionableTargets.map((target) => {
              const typeForbidden =
                action.toolId === 'TYPE_TEXT' &&
                (target.sensitive ||
                  !['textbox', 'combobox'].includes(target.role));
              return (
                <option
                  key={target.targetRef}
                  value={target.targetRef}
                  disabled={typeForbidden}
                >
                  {target.sensitive ? '🔒 ' : ''}
                  {target.role} · {target.name || target.targetRef}
                </option>
              );
            })}
          </select>
          {action.toolId === 'TYPE_TEXT' && (
            <div className="grid grid-cols-[minmax(0,1fr)_92px] gap-2">
              <input
                value={action.value ?? ''}
                onChange={(event) =>
                  onChange({ ...action, value: event.target.value })
                }
                maxLength={2000}
                placeholder="明确授权的非凭证文本"
                autoComplete="off"
                className={inputClass}
              />
              <select
                aria-label="输入数据分类"
                value={action.dataClass ?? 'PUBLIC'}
                onChange={(event) =>
                  onChange({
                    ...action,
                    dataClass: event.target.value as 'PUBLIC' | 'PII',
                  })
                }
                className={inputClass}
              >
                <option value="PUBLIC">PUBLIC</option>
                <option value="PII">PII</option>
              </select>
            </div>
          )}
          <p className="font-mono text-[8px] text-text-muted">
            TARGET REVISION {targetRevision ?? 'UNAVAILABLE'} · sensitive
            targets fail closed
          </p>
        </div>
      )}

      {action.toolId === 'SCROLL' && (
        <div className="mt-3">
          <input
            type="number"
            min={-2000}
            max={2000}
            step={100}
            value={action.scrollDeltaY ?? 600}
            onChange={(event) =>
              onChange({
                ...action,
                scrollDeltaY: Number(event.target.value),
              })
            }
            className={inputClass}
          />
          <p className="mt-1 font-mono text-[8px] text-text-muted">
            DELTA Y · -2000…2000 · minimum magnitude 100
          </p>
        </div>
      )}

      {action.toolId === 'WAIT_FOR' && (
        <div className="mt-3 grid grid-cols-2 gap-2">
          <select
            value={action.waitCondition ?? 'STATE_CHANGED'}
            onChange={(event) =>
              onChange({
                ...action,
                waitCondition: event.target
                  .value as CreateAgentActionRequest['waitCondition'],
                targetRef:
                  event.target.value === 'TARGET_PRESENT'
                    ? action.targetRef
                    : undefined,
              })
            }
            className={inputClass}
          >
            <option value="STATE_CHANGED">状态变化</option>
            <option value="STATE_STABLE">状态稳定</option>
            <option value="TARGET_PRESENT">目标出现</option>
          </select>
          <input
            type="number"
            min={100}
            max={10000}
            step={100}
            value={action.timeoutMs ?? 5000}
            onChange={(event) =>
              onChange({ ...action, timeoutMs: Number(event.target.value) })
            }
            className={inputClass}
          />
          {action.waitCondition === 'TARGET_PRESENT' && (
            <select
              value={action.targetRef ?? ''}
              onChange={(event) =>
                onChange({
                  ...action,
                  targetRef: event.target.value || undefined,
                })
              }
              className={cn(inputClass, 'col-span-2 font-mono')}
            >
              <option value="">选择等待目标</option>
              {targets.map((target) => (
                <option key={target.targetRef} value={target.targetRef}>
                  {target.role} · {target.name || target.targetRef}
                </option>
              ))}
            </select>
          )}
        </div>
      )}

      {action.toolId === 'REQUEST_HUMAN_TAKEOVER' && (
        <p className="mt-3 border border-warning/20 bg-warning/5 px-3 py-2 text-[9px] leading-4 text-warning">
          此动作必须位于计划末尾。Agent 只创建交接请求；具体 Actor
          接受后才获得排他输入权。
        </p>
      )}
    </div>
  );
}

function isActionComplete(action: DraftAction, currentTargetRevision?: number) {
  switch (action.toolId) {
    case 'CLICK_TARGET':
      return Boolean(
        action.targetRef &&
        currentTargetRevision &&
        action.targetRevision === currentTargetRevision
      );
    case 'TYPE_TEXT':
      return Boolean(
        action.targetRef &&
        action.value?.trim() &&
        currentTargetRevision &&
        action.targetRevision === currentTargetRevision
      );
    case 'SCROLL':
      return Boolean(
        action.scrollDeltaY &&
        Math.abs(action.scrollDeltaY) >= 100 &&
        Math.abs(action.scrollDeltaY) <= 2000
      );
    case 'WAIT_FOR':
      return Boolean(
        action.waitCondition &&
        action.timeoutMs &&
        action.timeoutMs >= 100 &&
        action.timeoutMs <= 10000 &&
        (action.waitCondition !== 'TARGET_PRESENT' || action.targetRef)
      );
    case 'REQUEST_HUMAN_TAKEOVER':
      return true;
  }
}

function createDraftAction(
  toolId: DraftAction['toolId'],
  targetRevision?: number
): DraftAction {
  const action: DraftAction = {
    clientId: crypto.randomUUID(),
    toolId,
  };
  if (toolId === 'CLICK_TARGET' || toolId === 'TYPE_TEXT') {
    action.targetRevision = targetRevision;
  }
  if (toolId === 'SCROLL') action.scrollDeltaY = 600;
  if (toolId === 'WAIT_FOR') {
    action.waitCondition = 'STATE_STABLE';
    action.timeoutMs = 5000;
  }
  return action;
}

function toActionRequest(action: DraftAction): CreateAgentActionRequest {
  return {
    toolId: action.toolId,
    targetRef: action.targetRef,
    targetRevision: action.targetRevision,
    value: action.value,
    dataClass: action.dataClass,
    scrollDeltaY: action.scrollDeltaY,
    waitCondition: action.waitCondition,
    timeoutMs: action.timeoutMs,
  };
}

function Signal({
  label,
  value,
  tone,
}: {
  label: string;
  value: number;
  tone: string;
}) {
  return (
    <span className="inline-flex items-baseline gap-2 text-text-muted">
      {label}
      <strong className={cn('font-mono text-[13px]', tone)}>{value}</strong>
    </span>
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
  children: ReactNode;
}) {
  return (
    <label className="block">
      <span className="mb-1.5 flex items-center justify-between text-[10px] font-medium uppercase tracking-[0.08em] text-text-muted">
        <span>
          {label}
          {required && <span className="ml-1 text-danger">*</span>}
        </span>
        {hint && (
          <span className="normal-case tracking-normal text-text-muted">
            {hint}
          </span>
        )}
      </span>
      {children}
    </label>
  );
}

function TaskRow({
  task,
  selected,
  onSelect,
}: {
  task: AgentTaskView;
  selected: boolean;
  onSelect: () => void;
}) {
  const blocked = task.state === 'BLOCKED' || task.state === 'FAILED';
  const completed = task.state === 'COMPLETED';
  const waiting =
    task.state === 'AWAITING_CONFIRMATION' ||
    task.state === 'WAITING_FOR_HUMAN';
  const running = task.state === 'RUNNING';
  return (
    <button
      type="button"
      onClick={onSelect}
      className={cn(
        'group w-full border-b border-border-subtle px-5 py-4 text-left transition-colors',
        selected ? 'bg-surface-2' : 'hover:bg-surface-2/60'
      )}
    >
      <div className="flex items-start gap-3">
        <span
          className={cn(
            'mt-0.5 flex h-6 w-6 shrink-0 items-center justify-center rounded-full',
            blocked
              ? 'bg-danger/12 text-danger'
              : waiting
                ? 'bg-warning/12 text-warning'
                : running
                  ? 'bg-accent/12 text-accent'
                  : 'bg-success/12 text-success'
          )}
        >
          {blocked ? (
            <Ban size={12} />
          ) : waiting ? (
            <UserRoundCheck size={12} />
          ) : running ? (
            <LoaderCircle className="animate-spin" size={12} />
          ) : (
            <CheckCircle2
              size={12}
              className={completed ? 'opacity-100' : 'opacity-70'}
            />
          )}
        </span>
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span className="truncate text-[12px] font-medium text-text-primary">
              {task.goal}
            </span>
            <ArrowRight
              size={12}
              className={cn(
                'shrink-0 text-text-muted transition-transform',
                selected && 'translate-x-0.5 text-accent'
              )}
            />
          </div>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 font-mono text-[9px] uppercase tracking-[0.08em] text-text-muted">
            <span>{task.taskId.slice(-8)}</span>
            <span>{task.agentPolicy ?? 'BALANCED'}</span>
            <span>{riskLabels[task.riskClass]}</span>
            <span>{task.totalSteps} steps</span>
            <span>{task.state}</span>
          </div>
          {blocked && (
            <p className="mt-2 truncate font-mono text-[9px] text-danger">
              {task.blockedReason}
            </p>
          )}
        </div>
      </div>
    </button>
  );
}

function TaskInspector({
  task,
  onExecute,
  isExecuting,
  executionError,
  onApprove,
  onReject,
  onAcceptHandoff,
  onRejectHandoff,
  governancePending,
  governanceError,
}: {
  task: AgentTaskView;
  onExecute: () => void;
  isExecuting: boolean;
  executionError?: unknown;
  onApprove: () => void;
  onReject: () => void;
  onAcceptHandoff: () => void;
  onRejectHandoff: () => void;
  governancePending: boolean;
  governanceError?: unknown;
}) {
  const blocked = task.state === 'BLOCKED' || task.state === 'FAILED';
  const completed = task.state === 'COMPLETED';
  const awaitingConfirmation = task.state === 'AWAITING_CONFIRMATION';
  const waitingForHuman = task.state === 'WAITING_FOR_HUMAN';
  const expiry = useMemo(
    () =>
      new Date(task.plan.expiresAt).toLocaleString('zh-CN', {
        hour12: false,
      }),
    [task.plan.expiresAt]
  );

  return (
    <div className="min-w-0">
      <div className="border-b border-border-subtle px-5 py-4">
        <div className="flex flex-wrap items-center gap-2">
          <span
            className={cn(
              'inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-[10px] font-medium',
              blocked
                ? 'bg-danger/12 text-danger'
                : awaitingConfirmation || waitingForHuman
                  ? 'bg-warning/12 text-warning'
                  : completed
                    ? 'bg-success/12 text-success'
                    : 'bg-accent/12 text-accent'
            )}
          >
            {blocked ? <Ban size={10} /> : <ShieldCheck size={10} />}
            {task.state}
          </span>
          <span className="font-mono text-[9px] uppercase tracking-[0.1em] text-text-muted">
            {task.intentDecision}
          </span>
        </div>
        <h3 className="mt-3 text-[15px] font-semibold leading-6 text-text-primary">
          {task.goal}
        </h3>
        <dl className="mt-3 grid grid-cols-2 gap-x-5 gap-y-2 text-[10px]">
          <Meta label="Intent" value={task.plan.intentId} mono />
          <Meta label="Session" value={task.sessionId} mono />
          <Meta label="动作上限" value={String(task.plan.maxActions)} />
          <Meta label="Replan 预算" value={String(task.plan.replanBudget)} />
          <Meta label="Replan 已用" value={String(task.replanCount)} />
          <Meta label="过期时间" value={expiry} />
          <Meta label="授权域名" value={task.allowedDomains.join(', ')} mono />
          <Meta
            label="Step 进度"
            value={`${task.currentStep} / ${task.totalSteps}`}
            mono
          />
        </dl>
      </div>

      {task.state === 'RUNNING' && (
        <div className="border-b border-accent/20 bg-accent/5 px-5 py-4">
          <div className="flex items-start gap-3">
            <LoaderCircle
              className="mt-0.5 shrink-0 animate-spin text-accent"
              size={15}
            />
            <div className="min-w-0 flex-1">
              <div className="flex items-center justify-between gap-3">
                <p className="text-[11px] font-semibold text-accent">
                  Durable Step 正在执行
                </p>
                <span className="font-mono text-[9px] text-text-muted">
                  {task.stepExecution.pendingToolId ?? 'CHECKPOINT'}
                </span>
              </div>
              <p className="mt-1 truncate font-mono text-[9px] text-text-secondary">
                {task.stepExecution.pendingStepId ?? '同步检查点提交中'}
              </p>
              <div className="mt-3 h-1 overflow-hidden bg-surface-3">
                <div
                  className="h-full bg-accent transition-[width]"
                  style={{
                    width: `${Math.max(
                      4,
                      (task.currentStep / Math.max(task.totalSteps, 1)) * 100
                    )}%`,
                  }}
                />
              </div>
              {task.stepExecution.deadline && (
                <p className="mt-2 font-mono text-[8px] text-text-muted">
                  DEADLINE{' '}
                  {new Date(task.stepExecution.deadline).toLocaleTimeString(
                    'zh-CN',
                    { hour12: false }
                  )}
                  {task.stepExecution.replanReason
                    ? ` · REPLAN ${task.stepExecution.replanReason}`
                    : ''}
                </p>
              )}
            </div>
          </div>
        </div>
      )}

      {awaitingConfirmation && (
        <GovernanceGate
          icon={<ShieldCheck size={15} />}
          title="高风险任务等待人工确认"
          description={`确认 ID ${task.confirmation.confirmationId ?? '—'}。确认仅授权当前不可变计划，过期后自动 fail closed。`}
          expiresAt={task.confirmation.expiresAt}
          approveLabel="确认并解锁计划"
          rejectLabel="拒绝任务"
          onApprove={onApprove}
          onReject={onReject}
          pending={governancePending}
        />
      )}

      {waitingForHuman && (
        <GovernanceGate
          icon={<UserRoundCheck size={15} />}
          title="Agent 已释放执行权，等待人工接管"
          description={`交接请求 ${task.humanHandoff.requestId ?? '—'}。接受后将创建独立 HumanTakeover Operation。`}
          expiresAt={task.humanHandoff.expiresAt}
          approveLabel="接受并进入人工接管"
          rejectLabel="拒绝交接"
          onApprove={onAcceptHandoff}
          onReject={onRejectHandoff}
          pending={governancePending}
        />
      )}

      {governanceError instanceof Error && (
        <p
          role="alert"
          className="border-b border-danger/20 bg-danger/5 px-5 py-2 text-[10px] text-danger"
        >
          {governanceError.message}
        </p>
      )}

      {task.state === 'PLANNED' && (
        <div className="border-b border-border-subtle px-5 py-3">
          <button
            type="button"
            onClick={onExecute}
            disabled={isExecuting}
            className="inline-flex h-8 w-full items-center justify-center gap-2 rounded-[6px] border border-accent/35 bg-accent-soft text-[11px] font-semibold text-accent transition-colors hover:border-accent/60 disabled:cursor-not-allowed disabled:border-border-default disabled:bg-surface-2 disabled:text-text-muted"
          >
            {isExecuting ? (
              <LoaderCircle className="animate-spin" size={13} />
            ) : (
              <ShieldCheck size={13} />
            )}
            执行并验证安全计划
          </button>
          {executionError instanceof Error && (
            <p className="mt-2 text-[10px] text-danger">
              {executionError.message}
            </p>
          )}
        </div>
      )}

      {blocked ? (
        <div className="border-b border-border-subtle bg-danger/5 px-5 py-4">
          <div className="flex gap-3">
            <LockKeyhole className="mt-0.5 shrink-0 text-danger" size={15} />
            <div>
              <p className="text-[11px] font-semibold text-danger">
                Plan Validator 已拒绝
              </p>
              <p className="mt-1 break-all font-mono text-[10px] leading-4 text-text-secondary">
                {task.blockedReason || task.lastError}
              </p>
            </div>
          </div>
        </div>
      ) : (
        <div className="border-b border-border-subtle">
          <div className="flex h-9 items-center gap-2 px-5 text-[9px] font-semibold uppercase tracking-[0.12em] text-text-muted">
            <Route size={11} />
            执行前计划
          </div>
          {task.plan.steps.map((step, index) => {
            const done = index < task.currentStep;
            const active =
              index === task.currentStep && task.state === 'RUNNING';
            return (
              <div
                key={step.stepId}
                className={cn(
                  'grid grid-cols-[28px_minmax(0,1fr)] gap-3 border-t border-border-subtle px-5 py-3',
                  active && 'bg-accent/5',
                  done && 'opacity-65'
                )}
              >
                <span
                  className={cn(
                    'flex h-6 w-6 items-center justify-center rounded-full border font-mono text-[9px]',
                    done
                      ? 'border-success/30 bg-success/10 text-success'
                      : active
                        ? 'border-accent/40 bg-accent/10 text-accent'
                        : 'border-border-default text-text-secondary'
                  )}
                >
                  {done ? (
                    <CheckCircle2 size={11} />
                  ) : active ? (
                    <LoaderCircle className="animate-spin" size={11} />
                  ) : (
                    String(index + 1).padStart(2, '0')
                  )}
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                    <span className="font-mono text-[10px] font-semibold text-accent">
                      {step.toolId}
                    </span>
                    <span className="font-mono text-[8px] uppercase text-text-muted">
                      {step.strategy}
                    </span>
                    <span className="font-mono text-[8px] text-text-muted">
                      {step.capabilityTokenId}
                    </span>
                  </div>
                  <p className="mt-1 text-[10px] leading-4 text-text-secondary">
                    {step.rationale}
                  </p>
                  <p className="mt-1 truncate font-mono text-[8px] text-text-muted">
                    VERIFY / {step.verification}
                  </p>
                  {step.input && (
                    <p className="mt-1 break-all font-mono text-[8px] text-text-muted">
                      {step.input.targetRef
                        ? `TARGET ${step.input.targetRef} @ r${step.input.targetRevision}`
                        : step.input.scrollDeltaY
                          ? `SCROLL ${step.input.scrollDeltaY}`
                          : step.input.waitCondition
                            ? `WAIT ${step.input.waitCondition} / ${step.input.timeoutMs}ms`
                            : ''}
                      {step.input.payloadHash
                        ? ` · SEALED ${step.input.payloadLength} chars · ${step.input.dataClass}`
                        : ''}
                    </p>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      {task.executionResults.length > 0 && (
        <div className="border-b border-border-subtle px-5 py-4">
          <div className="mb-3 text-[9px] font-semibold uppercase tracking-[0.12em] text-text-muted">
            Verified Tool Results
          </div>
          <div className="space-y-2">
            {task.executionResults.map((result) => (
              <div
                key={result.stepId}
                className="border border-success/20 bg-success/5 px-3 py-2"
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="font-mono text-[9px] font-semibold text-success">
                    {result.toolId}
                  </span>
                  <span className="font-mono text-[8px] text-success">
                    {result.status}
                  </span>
                </div>
                <pre className="mt-2 overflow-x-auto whitespace-pre-wrap break-all font-mono text-[8px] leading-4 text-text-secondary">
                  {JSON.stringify(result.output, null, 2)}
                </pre>
                <p className="mt-1 truncate font-mono text-[8px] text-text-muted">
                  SHA256 {result.resultHash}
                </p>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="px-5 py-4">
        <div className="mb-3 flex items-center gap-2 text-[9px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          <FileWarning size={11} />
          Prompt Security Events
        </div>
        {task.securityEvents.length === 0 ? (
          <p className="text-[10px] text-text-muted">
            未检测到安全规则命中。原始外部正文未持久化。
          </p>
        ) : (
          <div className="space-y-2">
            {task.securityEvents.map((event) => (
              <div
                key={event.eventId}
                className="border border-warning/20 bg-warning/5 px-3 py-2"
              >
                <div className="flex items-center justify-between gap-3">
                  <span className="font-mono text-[9px] font-semibold text-warning">
                    {event.eventType}
                  </span>
                  <span className="font-mono text-[8px] text-text-muted">
                    {event.decision}
                  </span>
                </div>
                <p className="mt-1 break-all font-mono text-[8px] text-text-secondary">
                  {event.ruleCode}
                </p>
                <p className="mt-1 truncate font-mono text-[8px] text-text-muted">
                  SHA256 {event.contentHash}
                </p>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function Meta({
  label,
  value,
  mono,
}: {
  label: string;
  value: string;
  mono?: boolean;
}) {
  return (
    <div className="min-w-0">
      <dt className="text-[9px] uppercase tracking-[0.08em] text-text-muted">
        {label}
      </dt>
      <dd
        className={cn(
          'mt-0.5 truncate text-[10px] text-text-secondary',
          mono && 'font-mono'
        )}
        title={value}
      >
        {value || '—'}
      </dd>
    </div>
  );
}

function GovernanceGate({
  icon,
  title,
  description,
  expiresAt,
  approveLabel,
  rejectLabel,
  onApprove,
  onReject,
  pending,
}: {
  icon: ReactNode;
  title: string;
  description: string;
  expiresAt?: string;
  approveLabel: string;
  rejectLabel: string;
  onApprove: () => void;
  onReject: () => void;
  pending: boolean;
}) {
  return (
    <div className="border-b border-warning/25 bg-warning/5 px-5 py-4">
      <div className="flex gap-3">
        <span className="mt-0.5 text-warning">{icon}</span>
        <div className="min-w-0 flex-1">
          <p className="text-[11px] font-semibold text-warning">{title}</p>
          <p className="mt-1 text-[10px] leading-4 text-text-secondary">
            {description}
          </p>
          {expiresAt && (
            <p className="mt-2 font-mono text-[8px] uppercase tracking-[0.08em] text-text-muted">
              Expires {new Date(expiresAt).toLocaleString('zh-CN')}
            </p>
          )}
          <div className="mt-3 grid grid-cols-2 gap-2">
            <button
              type="button"
              onClick={onReject}
              disabled={pending}
              className="h-8 rounded-[6px] border border-danger/30 text-[10px] font-medium text-danger transition-colors hover:bg-danger/8 disabled:opacity-40"
            >
              {rejectLabel}
            </button>
            <button
              type="button"
              onClick={onApprove}
              disabled={pending}
              className="inline-flex h-8 items-center justify-center gap-1.5 rounded-[6px] bg-warning px-3 text-[10px] font-semibold text-canvas disabled:opacity-40"
            >
              {pending ? (
                <LoaderCircle className="animate-spin" size={11} />
              ) : (
                <UserRoundCheck size={11} />
              )}
              {approveLabel}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
