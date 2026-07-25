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
  Plus,
  RefreshCw,
  Route,
  ShieldCheck,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingPanel,
} from '@/components/feedback/AsyncStates';
import { useSessions } from '@/features/sessions/api/sessionQueries';
import {
  useAgentTasks,
  useCreateAgentTask,
  useExecuteAgentTask,
} from '@/features/automation/agentQueries';
import { cn } from '@/shared/lib/utils';
import type {
  AgentRiskClass,
  AgentTaskView,
  InstructionSourceType,
} from '@/types/agent';

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

export function AutomationPage() {
  const tasksQuery = useAgentTasks();
  const sessionsQuery = useSessions({ state: 'RUNNING', limit: 100 });
  const createTask = useCreateAgentTask();
  const executeTask = useExecuteAgentTask();
  const [selectedTaskId, setSelectedTaskId] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [goal, setGoal] = useState('');
  const [startUrl, setStartUrl] = useState('');
  const [allowedDomains, setAllowedDomains] = useState('');
  const [externalContent, setExternalContent] = useState('');
  const [sourceType, setSourceType] =
    useState<InstructionSourceType>('WEB_CONTENT');

  const tasks = tasksQuery.data?.items ?? [];
  const selectedTask =
    tasks.find((task) => task.taskId === selectedTaskId) ?? tasks[0];

  useEffect(() => {
    if (!sessionId && sessionsQuery.data?.items[0]) {
      setSessionId(sessionsQuery.data.items[0].sessionId);
    }
  }, [sessionId, sessionsQuery.data]);

  const plannedCount = tasks.filter((task) => task.state === 'PLANNED').length;
  const completedCount = tasks.filter(
    (task) => task.state === 'COMPLETED'
  ).length;
  const blockedCount = tasks.filter((task) => task.state === 'BLOCKED').length;
  const securityEventCount = tasks.reduce(
    (total, task) => total + task.securityEvents.length,
    0
  );

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
        maxActions: 8,
        replanBudget: 1,
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
      },
    });
    setSelectedTaskId(task.taskId);
    setGoal('');
    setExternalContent('');
  }

  return (
    <div>
      <TopContextBar
        title="Agent 任务"
        subtitle="受限 Planner、安全决策与执行前审查"
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
            Read tools live · Navigate pending
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
                  当前仅支持导航与状态读取；变更类目标会被明确拦截。
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
                    {session.displayName} · {session.sessionId.slice(-6)}
                  </option>
                ))}
              </select>
            </Field>

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
                placeholder="https://example.com/dashboard"
                className={cn(inputClass, 'font-mono')}
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
                !allowedDomains.trim()
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
            blocked ? 'bg-danger/12 text-danger' : 'bg-success/12 text-success'
          )}
        >
          {blocked ? (
            <Ban size={12} />
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
            <span>{riskLabels[task.riskClass]}</span>
            <span>{task.totalSteps} steps</span>
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
}: {
  task: AgentTaskView;
  onExecute: () => void;
  isExecuting: boolean;
  executionError?: unknown;
}) {
  const blocked = task.state === 'BLOCKED' || task.state === 'FAILED';
  const completed = task.state === 'COMPLETED';
  const hasUnsupportedNavigate = task.plan.steps.some(
    (step) => step.toolId === 'NAVIGATE'
  );
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
          <Meta label="过期时间" value={expiry} />
          <Meta label="授权域名" value={task.allowedDomains.join(', ')} mono />
        </dl>
      </div>

      {task.state === 'PLANNED' && (
        <div className="border-b border-border-subtle px-5 py-3">
          <button
            type="button"
            onClick={onExecute}
            disabled={isExecuting || hasUnsupportedNavigate}
            className="inline-flex h-8 w-full items-center justify-center gap-2 rounded-[6px] border border-accent/35 bg-accent-soft text-[11px] font-semibold text-accent transition-colors hover:border-accent/60 disabled:cursor-not-allowed disabled:border-border-default disabled:bg-surface-2 disabled:text-text-muted"
          >
            {isExecuting ? (
              <LoaderCircle className="animate-spin" size={13} />
            ) : (
              <ShieldCheck size={13} />
            )}
            {hasUnsupportedNavigate
              ? '等待 Navigate Executor'
              : '执行并验证只读计划'}
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
                {task.blockedReason}
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
          {task.plan.steps.map((step, index) => (
            <div
              key={step.stepId}
              className="grid grid-cols-[28px_minmax(0,1fr)] gap-3 border-t border-border-subtle px-5 py-3"
            >
              <span className="flex h-6 w-6 items-center justify-center rounded-full border border-border-default font-mono text-[9px] text-text-secondary">
                {String(index + 1).padStart(2, '0')}
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
              </div>
            </div>
          ))}
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
