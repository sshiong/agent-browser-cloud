import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';
import { profiles } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const restoreColors: Record<string, string> = {
  ready: 'text-success bg-success/15',
  restoring: 'text-accent-secondary bg-accent-secondary/15',
  failed: 'text-danger bg-danger/15',
  unknown: 'text-text-muted bg-surface-3',
};

export function ProfilesPage() {
  return (
    <div>
      <TopContextBar
        title="Profile 存储"
        subtitle="管理浏览器 Profile、检查点与恢复状态"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1">
            <table className="w-full">
              <thead>
                <tr className="border-b border-border-subtle bg-surface-2">
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Profile
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Core 大小
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    Cache 大小
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    最近检查点
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    加密版本
                  </th>
                  <th className="px-4 py-3 text-left text-[11px] font-medium uppercase tracking-wider text-text-muted">
                    恢复状态
                  </th>
                </tr>
              </thead>
              <tbody>
                {profiles.map((profile) => (
                  <tr
                    key={profile.id}
                    className="border-b border-border-subtle transition-colors hover:bg-surface-2"
                  >
                    <td className="px-4 py-3">
                      <div>
                        <span className="text-[13px] font-medium text-text-primary">
                          {profile.name}
                        </span>
                        <p className="font-mono text-[11px] text-text-muted">
                          {profile.id}
                        </p>
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        {profile.coreSize}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-secondary">
                        {profile.cacheSize}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="text-[12px] text-text-muted">
                        {profile.lastCheckpoint}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span className="font-mono text-[12px] text-text-secondary">
                        v{profile.encryptionKeyVersion}
                      </span>
                    </td>
                    <td className="px-4 py-3">
                      <span
                        className={cn(
                          'rounded-full px-2 py-0.5 text-[11px] font-medium',
                          restoreColors[profile.restoreStatus]
                        )}
                      >
                        {profile.restoreStatus === 'ready' && '就绪'}
                        {profile.restoreStatus === 'restoring' && '恢复中'}
                        {profile.restoreStatus === 'failed' && '失败'}
                        {profile.restoreStatus === 'unknown' && '未知'}
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
