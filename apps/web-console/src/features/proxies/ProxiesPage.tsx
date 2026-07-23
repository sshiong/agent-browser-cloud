import { TopContextBar } from '@/components/layout/TopContextBar';
import { HealthChip } from '@/components/ui/StatusChip';
import { cn } from '@/shared/lib/utils';
import { proxyProviders } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

export function ProxiesPage() {
  return (
    <div>
      <TopContextBar
        title="代理与出口"
        subtitle="管理 Proxy Provider、分配策略与网络出口健康"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border-subtle bg-surface-2">
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Provider
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    类型
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    地区
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    成功率
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    平均延迟
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    成本/GB
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    健康
                  </th>
                </tr>
              </thead>
              <tbody>
                {proxyProviders.map((provider) => (
                  <tr
                    key={provider.id}
                    className="border-b border-border-subtle transition-colors hover:bg-surface-2"
                  >
                    <td className="px-4 py-3">
                      <span className="text-[13px] font-medium text-text-primary">
                        {provider.name}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        {provider.type}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex gap-1">
                        {provider.regions.map((r) => (
                          <span
                            key={r}
                            className="rounded-md bg-surface-3 px-1.5 py-0.5 text-[11px] text-text-muted"
                          >
                            {r}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'text-[12px] font-medium',
                          provider.successRate >= 98
                            ? 'text-success'
                            : provider.successRate >= 95
                              ? 'text-warning'
                              : 'text-danger'
                        )}
                      >
                        {provider.successRate}%
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        {provider.avgLatency}ms
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        ${provider.costPerGb}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <HealthChip status={provider.status} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
