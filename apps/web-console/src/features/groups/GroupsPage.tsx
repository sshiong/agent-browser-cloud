import { TopContextBar } from '@/components/layout/TopContextBar';
import { sessions } from '@/mocks/data';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const groups = [...new Set(sessions.map((s) => s.group))];

export function GroupsPage() {
  return (
    <div>
      <TopContextBar
        title="分组与标签"
        subtitle="管理环境分组、默认策略与批量操作"
      />
      <FixtureBoundary>
        <div className="p-6">
          <div className="grid grid-cols-2 gap-4">
            {groups.map((group) => {
              const groupSessions = sessions.filter((s) => s.group === group);
              return (
                <div
                  key={group}
                  className="rounded-[10px] border border-border-subtle bg-surface-1 p-5"
                >
                  <div className="mb-3 flex items-center justify-between">
                    <h4 className="text-[14px] font-medium text-text-primary">
                      {group}
                    </h4>
                    <span className="rounded-full bg-surface-3 px-2 py-0.5 text-[11px] text-text-muted">
                      {groupSessions.length} 个环境
                    </span>
                  </div>
                  <div className="space-y-2">
                    {groupSessions.map((s) => (
                      <div
                        key={s.id}
                        className="flex items-center justify-between rounded-md bg-surface-2 px-3 py-2"
                      >
                        <span className="text-[12px] text-text-primary">
                          {s.name}
                        </span>
                        <span className="font-mono text-[11px] text-text-muted">
                          {s.id.slice(0, 12)}...
                        </span>
                      </div>
                    ))}
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
