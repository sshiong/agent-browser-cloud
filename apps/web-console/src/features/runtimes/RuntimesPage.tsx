import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';
import { runtimeBuilds } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const tierColors: Record<string, string> = {
  'Tier 0': 'text-success bg-success/15',
  'Tier 1': 'text-accent-secondary bg-accent-secondary/15',
  'Tier 2': 'text-warning bg-warning/15',
};

const validationColors: Record<string, string> = {
  passed: 'text-success',
  failed: 'text-danger',
  pending: 'text-warning',
  unknown: 'text-text-muted',
};

export function RuntimesPage() {
  return (
    <div>
      <TopContextBar
        title="Runtime 与内核"
        subtitle="管理 Chromium Runtime 构建版本与验证状态"
      />
      <FixtureBoundary>
        <div className="p-6">
          {/* Status Bar */}
          <div className="mb-6 flex items-center gap-6 rounded-[10px] border border-border-subtle bg-surface-1 p-4">
            <div>
              <span className="text-[11px] text-text-muted">已安装</span>
              <p className="text-[18px] font-semibold text-text-primary">
                {runtimeBuilds.filter((r) => r.installed).length}
              </p>
            </div>
            <div className="h-8 w-px bg-border-subtle" />
            <div>
              <span className="text-[11px] text-text-muted">活跃构建</span>
              <p className="text-[18px] font-semibold text-accent">
                Platform Stable
              </p>
            </div>
            <div className="h-8 w-px bg-border-subtle" />
            <div>
              <span className="text-[11px] text-text-muted">Chromium 版本</span>
              <p className="font-mono text-[14px] text-text-primary">
                126.0.6478.126
              </p>
            </div>
            <div className="h-8 w-px bg-border-subtle" />
            <div>
              <span className="text-[11px] text-text-muted">安全等级</span>
              <p className="text-[14px] text-success">Tier 0 — First-party</p>
            </div>
          </div>

          {/* Build List */}
          <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border-subtle bg-surface-2">
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Build
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Chromium
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Build ID
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    平台
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    安全等级
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    验证
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    状态
                  </th>
                </tr>
              </thead>
              <tbody>
                {runtimeBuilds.map((build) => (
                  <tr
                    key={build.id}
                    className="border-b border-border-subtle transition-colors hover:bg-surface-2"
                  >
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="text-[13px] font-medium text-text-primary">
                          {build.name}
                        </span>
                        {build.isDefault && (
                          <span className="rounded-full bg-accent/15 px-2 py-0.5 text-[10px] font-medium text-accent">
                            默认
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-mono text-[12px] text-text-secondary">
                        {build.chromiumVersion}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-mono text-[11px] text-text-muted">
                        {build.buildId}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        {build.platform}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          tierColors[build.securityTier]
                        )}
                      >
                        {build.securityTier}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'text-[12px] font-medium',
                          validationColors[build.validationStatus]
                        )}
                      >
                        {build.validationStatus === 'passed' && '✓ 通过'}
                        {build.validationStatus === 'failed' && '✗ 失败'}
                        {build.validationStatus === 'pending' && '⏳ 待验证'}
                        {build.validationStatus === 'unknown' && '— 未知'}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          build.installed
                            ? 'bg-success/15 text-success'
                            : 'bg-surface-3 text-text-muted'
                        )}
                      >
                        {build.installed ? '已安装' : '未安装'}
                      </span>
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
