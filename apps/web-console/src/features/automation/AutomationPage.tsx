import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';
import { agentTasks } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const stateLabels: Record<string, { label: string; color: string }> = {
  pending: { label: '等待中', color: 'text-text-muted bg-surface-3' },
  running: { label: '运行中', color: 'text-accent bg-accent/15' },
  waiting_human: { label: '等待人工', color: 'text-purple bg-purple/15' },
  completed: { label: '已完成', color: 'text-success bg-success/15' },
  failed: { label: '失败', color: 'text-danger bg-danger/15' },
};

const riskColors: Record<string, string> = {
  low: 'text-success',
  medium: 'text-warning',
  high: 'text-danger',
};

export function AutomationPage() {
  return (
    <div>
      <TopContextBar
        title="Agent 任务"
        subtitle="管理 Agent 自动化任务、执行状态与成本"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border-subtle bg-surface-2">
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    任务
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    环境
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    目标
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    步骤
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    风险
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    成本
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    状态
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    结果
                  </th>
                </tr>
              </thead>
              <tbody>
                {agentTasks.map((task) => {
                  const st = stateLabels[task.state] ?? {
                    label: task.state,
                    color: 'text-text-muted bg-surface-3',
                  };
                  return (
                    <tr
                      key={task.id}
                      className="border-b border-border-subtle transition-colors hover:bg-surface-2"
                    >
                      <td className="px-4 py-3">
                        <span className="text-[13px] font-medium text-text-primary">
                          {task.name}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-[12px] text-text-secondary">
                          {task.sessionName}
                        </span>
                      </td>
                      <td className="max-w-[200px] px-4 py-3">
                        <span className="truncate text-[12px] text-text-muted">
                          {task.goal}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-2">
                          <div className="h-1.5 w-20 overflow-hidden rounded-full bg-surface-3">
                            <div
                              className="h-full rounded-full bg-accent transition-all"
                              style={{
                                width: `${(task.currentStep / task.totalSteps) * 100}%`,
                              }}
                            />
                          </div>
                          <span className="text-[11px] text-text-muted">
                            {task.currentStep}/{task.totalSteps}
                          </span>
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'text-[12px] font-medium capitalize',
                            riskColors[task.risk]
                          )}
                        >
                          {task.risk === 'low' && '低'}
                          {task.risk === 'medium' && '中'}
                          {task.risk === 'high' && '高'}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="font-mono text-[12px] text-text-secondary">
                          ${task.cost.toFixed(2)}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={cn(
                            'rounded-full px-2 py-0.5 text-[11px] font-medium',
                            st.color
                          )}
                        >
                          {st.label}
                        </span>
                      </td>
                      <td className="px-4 py-3">
                        <span className="text-[12px] text-text-muted">
                          {task.result || '—'}
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
