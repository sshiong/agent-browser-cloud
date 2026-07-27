import { TopContextBar } from '@/components/layout/TopContextBar';
import { cn } from '@/shared/lib/utils';

const sections = [
  { label: '通用', active: true },
  { label: '外观', active: false },
  { label: 'Runtime 路径', active: false },
  { label: '存储', active: false },
  { label: '代理', active: false },
  { label: 'API', active: false },
  { label: '更新', active: false },
  { label: '诊断', active: false },
];

export function SettingsPage() {
  return (
    <div>
      <TopContextBar
        title="设置"
        subtitle="配置工作区、Runtime、存储与系统偏好"
      />

      <div className="flex p-6">
        {/* Settings Nav */}
        <div className="w-[200px] shrink-0">
          <nav className="space-y-0.5">
            {sections.map((s) => (
              <button
                key={s.label}
                className={cn(
                  'w-full rounded-md px-3 py-2 text-left text-[13px] transition-colors',
                  s.active
                    ? 'bg-accent-soft text-accent'
                    : 'text-text-secondary hover:bg-surface-2'
                )}
              >
                {s.label}
              </button>
            ))}
          </nav>
        </div>

        {/* Settings Content */}
        <div className="flex-1 rounded-[10px] border border-border-subtle bg-surface-1 p-6">
          <h3 className="mb-6 text-[16px] font-medium text-text-primary">
            通用设置
          </h3>

          <div className="space-y-6">
            <SettingGroup
              label="工作区名称"
              description="显示在侧边栏顶部的工作区名称"
            >
              <input
                type="text"
                defaultValue="Default Workspace"
                className="h-9 w-full max-w-[400px] rounded-md border border-border-subtle bg-surface-2 px-3 text-[13px] text-text-primary focus:border-accent focus:outline-none"
              />
            </SettingGroup>

            <SettingGroup
              label="默认 Runtime"
              description="新建环境时默认使用的 Runtime 构建"
            >
              <select className="h-9 w-full max-w-[400px] rounded-md border border-border-subtle bg-surface-2 px-3 text-[13px] text-text-primary focus:border-accent focus:outline-none">
                <option>Platform Stable (v126.0.6478.126)</option>
                <option>Certified Runtime (v127.0.6533.88)</option>
              </select>
            </SettingGroup>

            <SettingGroup
              label="默认资源策略"
              description="新建环境统一使用自动资源分配；内部模板由 Control Plane 解析"
            >
              <div className="w-full max-w-[400px] border border-accent/30 bg-accent-soft px-3 py-2.5">
                <p className="font-mono text-[11px] text-accent">
                  AUTO · 自动分配
                </p>
                <p className="mt-1 text-[10px] text-text-muted">
                  达到上限时默认暂停 Agent，保留 Browser。
                </p>
              </div>
            </SettingGroup>

            <SettingGroup
              label="HumanTakeover"
              description="是否默认启用人工接管能力"
            >
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  defaultChecked
                  className="h-4 w-4 rounded border-border-default accent-accent"
                />
                <span className="text-[13px] text-text-secondary">
                  默认启用
                </span>
              </label>
            </SettingGroup>
          </div>
        </div>
      </div>
    </div>
  );
}

function SettingGroup({
  label,
  description,
  children,
}: {
  label: string;
  description: string;
  children: React.ReactNode;
}) {
  return (
    <div className="border-b border-border-subtle pb-6">
      <label className="text-[13px] font-medium text-text-primary">
        {label}
      </label>
      <p className="mb-2 text-[12px] text-text-muted">{description}</p>
      {children}
    </div>
  );
}
