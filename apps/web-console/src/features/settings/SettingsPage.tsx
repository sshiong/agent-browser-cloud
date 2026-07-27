import { useEffect, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  LoaderCircle,
  MonitorCog,
  RefreshCw,
  ShieldCheck,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { usePlatform } from '@/platform/PlatformProvider';
import type { DesktopUpdateStatus, LocalRuntimeStatus } from '@/platform/types';
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
        <div className="min-w-0 flex-1 rounded-[10px] border border-border-subtle bg-surface-1 p-6">
          <h3 className="mb-6 text-[16px] font-medium text-text-primary">
            通用设置
          </h3>

          <div className="space-y-6">
            <DesktopStatusSection />

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

function DesktopStatusSection() {
  const platform = usePlatform();
  const [version, setVersion] = useState<string>();
  const [runtime, setRuntime] = useState<LocalRuntimeStatus>();
  const [update, setUpdate] = useState<DesktopUpdateStatus>();
  const [loading, setLoading] = useState(false);
  const [installing, setInstalling] = useState(false);
  const [error, setError] = useState<string>();

  useEffect(() => {
    let active = true;
    void Promise.all([platform.getAppVersion(), platform.checkLocalRuntime()])
      .then(([nextVersion, nextRuntime]) => {
        if (!active) return;
        setVersion(nextVersion);
        setRuntime(nextRuntime);
      })
      .catch((cause) => {
        if (active) setError(errorMessage(cause, '读取客户端状态失败'));
      });
    return () => {
      active = false;
    };
  }, [platform]);

  if (!platform.desktop) {
    return (
      <SettingGroup
        label="客户端运行环境"
        description="当前为 Web 管理端；本机 Runtime、系统凭据库和桌面更新能力不会暴露给浏览器。"
      >
        <div className="flex max-w-[720px] items-start gap-3 border border-border-subtle bg-surface-2 px-3 py-3">
          <MonitorCog
            aria-hidden="true"
            className="mt-0.5 h-4 w-4 shrink-0 text-text-muted"
          />
          <div>
            <p className="text-[12px] font-medium text-text-secondary">
              WEB CONSOLE
            </p>
            <p className="mt-0.5 text-[11px] leading-5 text-text-muted">
              桌面特性只通过 Tauri 平台适配器调用，业务组件与 API Client
              仍保持复用。
            </p>
          </div>
        </div>
      </SettingGroup>
    );
  }

  const checkUpdate = async () => {
    setLoading(true);
    setError(undefined);
    try {
      setUpdate(await platform.checkForUpdates());
    } catch (cause) {
      setError(errorMessage(cause, '更新检查失败'));
    } finally {
      setLoading(false);
    }
  };

  const installUpdate = async () => {
    setInstalling(true);
    setError(undefined);
    try {
      await platform.installAvailableUpdate();
    } catch (cause) {
      setError(errorMessage(cause, '更新安装失败'));
      setInstalling(false);
    }
  };

  return (
    <SettingGroup
      label="桌面客户端"
      description="来自本机 Tauri 容器的真实状态；更新检查不会使用前端模拟数据。"
    >
      <div className="max-w-[720px] overflow-hidden border border-border-subtle bg-surface-2">
        <div className="grid gap-px bg-border-subtle md:grid-cols-3">
          <DesktopDatum
            icon={<MonitorCog aria-hidden="true" className="h-4 w-4" />}
            label="平台"
            value={platform.platform.toUpperCase()}
            detail={version ? `Desktop ${version}` : '正在读取版本'}
          />
          <DesktopDatum
            icon={
              runtime?.available ? (
                <CheckCircle2 aria-hidden="true" className="h-4 w-4" />
              ) : (
                <AlertTriangle aria-hidden="true" className="h-4 w-4" />
              )
            }
            label="本机 Runtime"
            value={
              runtime
                ? runtime.available
                  ? 'AVAILABLE'
                  : 'NOT PACKAGED'
                : 'CHECKING'
            }
            detail={runtime?.reason ?? '正在检查发行包'}
            tone={runtime?.available ? 'positive' : 'neutral'}
          />
          <DesktopDatum
            icon={<ShieldCheck aria-hidden="true" className="h-4 w-4" />}
            label="身份存储"
            value="OS VAULT"
            detail="OIDC PKCE 与 Token 不写入 localStorage"
            tone="positive"
          />
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-border-subtle px-3 py-3">
          <div className="min-w-0">
            <p className="text-[11px] font-medium text-text-secondary">
              {update?.available
                ? `可更新至 ${update.version}`
                : update
                  ? '当前已是最新版本'
                  : '尚未连接更新服务'}
            </p>
            <p className="mt-0.5 truncate text-[10px] text-text-muted">
              {update?.available
                ? '安装包由签名更新清单校验；完成后客户端将重启。'
                : '只有用户主动检查时才访问配置的 HTTPS 更新端点。'}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              disabled={loading || installing}
              onClick={() => void checkUpdate()}
              className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[11px] font-medium text-text-secondary transition-colors hover:border-accent/50 hover:text-text-primary disabled:cursor-not-allowed disabled:opacity-50"
            >
              {loading ? (
                <LoaderCircle
                  aria-hidden="true"
                  className="h-3.5 w-3.5 animate-spin"
                />
              ) : (
                <RefreshCw aria-hidden="true" className="h-3.5 w-3.5" />
              )}
              检查更新
            </button>
            {update?.available ? (
              <button
                type="button"
                disabled={installing}
                onClick={() => void installUpdate()}
                className="inline-flex h-8 items-center gap-1.5 bg-accent px-3 text-[11px] font-semibold text-surface-0 transition-opacity hover:opacity-90 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {installing ? (
                  <LoaderCircle
                    aria-hidden="true"
                    className="h-3.5 w-3.5 animate-spin"
                  />
                ) : (
                  <Download aria-hidden="true" className="h-3.5 w-3.5" />
                )}
                安装并重启
              </button>
            ) : null}
          </div>
        </div>
        {error ? (
          <div
            role="alert"
            className="border-t border-danger/20 bg-danger/5 px-3 py-2 text-[11px] text-danger"
          >
            {error}
          </div>
        ) : null}
      </div>
    </SettingGroup>
  );
}

function DesktopDatum({
  icon,
  label,
  value,
  detail,
  tone = 'neutral',
}: {
  icon: React.ReactNode;
  label: string;
  value: string;
  detail: string;
  tone?: 'neutral' | 'positive';
}) {
  return (
    <div className="min-h-[100px] bg-surface-2 p-3">
      <div
        className={cn(
          'mb-3 flex items-center gap-2',
          tone === 'positive' ? 'text-accent' : 'text-text-muted'
        )}
      >
        {icon}
        <span className="text-[10px] uppercase tracking-[0.12em]">{label}</span>
      </div>
      <p className="font-mono text-[12px] font-semibold text-text-primary">
        {value}
      </p>
      <p className="mt-1 line-clamp-2 text-[10px] leading-4 text-text-muted">
        {detail}
      </p>
    </div>
  );
}

function errorMessage(cause: unknown, fallback: string) {
  return cause instanceof Error && cause.message ? cause.message : fallback;
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
