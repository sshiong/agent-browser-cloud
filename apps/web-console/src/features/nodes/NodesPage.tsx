import { TopContextBar } from '@/components/layout/TopContextBar';
import { HealthChip } from '@/components/ui/StatusChip';
import { cn } from '@/shared/lib/utils';
import { browserNodes } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

export function NodesPage() {
  return (
    <div>
      <TopContextBar
        title="Browser Node"
        subtitle="管理浏览器运行节点、资源与健康状态"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="grid grid-cols-3 gap-4">
            {browserNodes.map((node) => (
              <div
                key={node.id}
                className="rounded-[10px] border border-border-subtle bg-surface-1 p-5"
              >
                <div className="mb-4 flex items-center justify-between">
                  <div>
                    <h4 className="text-[14px] font-medium text-text-primary">
                      {node.name}
                    </h4>
                    <p className="font-mono text-[11px] text-text-muted">
                      {node.id}
                    </p>
                  </div>
                  <HealthChip status={node.status} />
                </div>

                <div className="mb-3 text-[12px] text-text-muted">
                  Region: {node.region}
                </div>

                <div className="space-y-3">
                  <div>
                    <div className="mb-1 flex items-center justify-between text-[11px]">
                      <span className="text-text-muted">CPU</span>
                      <span className="text-text-secondary">{node.cpu}%</span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-surface-3">
                      <div
                        className={cn(
                          'h-full rounded-full transition-all',
                          node.cpu > 70
                            ? 'bg-danger'
                            : node.cpu > 50
                              ? 'bg-warning'
                              : 'bg-accent'
                        )}
                        style={{ width: `${node.cpu}%` }}
                      />
                    </div>
                  </div>
                  <div>
                    <div className="mb-1 flex items-center justify-between text-[11px]">
                      <span className="text-text-muted">内存</span>
                      <span className="text-text-secondary">
                        {node.memory}%
                      </span>
                    </div>
                    <div className="h-2 overflow-hidden rounded-full bg-surface-3">
                      <div
                        className={cn(
                          'h-full rounded-full transition-all',
                          node.memory > 80
                            ? 'bg-danger'
                            : node.memory > 60
                              ? 'bg-warning'
                              : 'bg-accent'
                        )}
                        style={{ width: `${node.memory}%` }}
                      />
                    </div>
                  </div>
                </div>

                <div className="mt-4 flex items-center justify-between border-t border-border-subtle pt-3">
                  <span className="text-[12px] text-text-muted">
                    Sessions: {node.sessions}/{node.maxSessions}
                  </span>
                  <div className="h-1.5 w-20 overflow-hidden rounded-full bg-surface-3">
                    <div
                      className="h-full rounded-full bg-accent-secondary"
                      style={{
                        width: `${(node.sessions / node.maxSessions) * 100}%`,
                      }}
                    />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
