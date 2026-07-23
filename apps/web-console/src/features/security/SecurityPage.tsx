import { TopContextBar } from '@/components/layout/TopContextBar';
import { Shield, AlertTriangle, Key, Lock, Eye, FileCheck } from 'lucide-react';
import { cn } from '@/shared/lib/utils';
import { FixtureBoundary } from '@/components/feedback/FixtureNotice';

const securityModules = [
  {
    icon: AlertTriangle,
    label: '威胁事件',
    desc: '查看安全威胁与攻击检测',
    count: 3,
    color: 'text-danger',
  },
  {
    icon: Shield,
    label: 'Prompt Injection',
    desc: '查看 Prompt 注入拦截记录',
    count: 7,
    color: 'text-warning',
  },
  {
    icon: Lock,
    label: 'Runtime 签名',
    desc: '验证 Runtime 构建签名',
    count: 0,
    color: 'text-success',
  },
  {
    icon: Eye,
    label: '扩展风险',
    desc: '监控扩展权限与行为',
    count: 2,
    color: 'text-warning',
  },
  {
    icon: Key,
    label: '密钥轮换',
    desc: '管理加密密钥生命周期',
    count: 1,
    color: 'text-accent-secondary',
  },
  {
    icon: FileCheck,
    label: '审计',
    desc: '查看操作审计日志',
    count: 0,
    color: 'text-accent',
  },
];

export function SecurityPage() {
  return (
    <div>
      <TopContextBar title="安全中心" subtitle="威胁检测、权限管理与安全审计" />
      <FixtureBoundary>
        <div className="p-6">
          <div className="grid grid-cols-3 gap-4">
            {securityModules.map((mod) => (
              <button
                key={mod.label}
                className="flex items-start gap-4 rounded-[10px] border border-border-subtle bg-surface-1 p-5 text-left transition-colors hover:border-border-default"
              >
                <div className={cn('rounded-lg bg-surface-2 p-2.5', mod.color)}>
                  <mod.icon size={20} />
                </div>
                <div className="flex-1">
                  <h4 className="text-[14px] font-medium text-text-primary">
                    {mod.label}
                  </h4>
                  <p className="mt-1 text-[12px] text-text-muted">{mod.desc}</p>
                  {mod.count > 0 && (
                    <span
                      className={cn(
                        'mt-2 inline-block rounded-full px-2 py-0.5 text-[11px] font-medium',
                        mod.color,
                        mod.color.replace('text-', 'bg-') + '/15'
                      )}
                    >
                      {mod.count} 条待处理
                    </span>
                  )}
                </div>
              </button>
            ))}
          </div>

          {/* Recent Prompt Injection Events */}
          <div className="mt-6 rounded-[10px] border border-border-subtle bg-surface-1 p-5">
            <h3 className="mb-4 text-[14px] font-medium text-text-primary">
              最近 Prompt Injection 拦截
            </h3>
            <div className="space-y-3">
              {[
                {
                  time: '07:12:30',
                  source: 'WEB_CONTENT',
                  rule: 'HIGH_RISK_ACTION_FROM_UNTRUSTED',
                  action: 'upload_file',
                  session: 'Finance Review',
                },
                {
                  time: '06:45:18',
                  source: 'EMAIL',
                  rule: 'SECRET_READ_FROM_UNTRUSTED',
                  action: 'read_cookie',
                  session: 'Support Workspace',
                },
                {
                  time: '05:33:02',
                  source: 'WEB_CONTENT',
                  rule: 'HIGH_RISK_ACTION_FROM_UNTRUSTED',
                  action: 'send_message',
                  session: 'CRM Singapore',
                },
              ].map((evt, i) => (
                <div
                  key={i}
                  className="flex items-center gap-4 rounded-lg border border-border-subtle bg-surface-2 p-3"
                >
                  <span className="font-mono text-[11px] text-text-muted">
                    {evt.time}
                  </span>
                  <span className="rounded bg-warning/15 px-1.5 py-0.5 text-[10px] font-medium text-warning">
                    {evt.source}
                  </span>
                  <span className="text-[12px] text-text-primary">
                    {evt.rule}
                  </span>
                  <span className="font-mono text-[11px] text-danger">
                    {evt.action}
                  </span>
                  <span className="text-[11px] text-text-muted">
                    {evt.session}
                  </span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </FixtureBoundary>
    </div>
  );
}
