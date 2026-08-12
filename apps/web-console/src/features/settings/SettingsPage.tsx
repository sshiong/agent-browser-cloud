import { useEffect, useState } from 'react';
import {
  AlertTriangle,
  CheckCircle2,
  Download,
  LoaderCircle,
  MonitorCog,
  RefreshCw,
  Save,
  ShieldCheck,
} from 'lucide-react';
import { isSessionApiError } from '@/api/session';
import { TopContextBar } from '@/components/layout/TopContextBar';
import { ErrorState, LoadingRows } from '@/components/feedback/AsyncStates';
import { useRuntimeBuilds } from '@/features/security/platformQueries';
import { usePlatform } from '@/platform/PlatformProvider';
import type { DesktopUpdateStatus, LocalRuntimeStatus } from '@/platform/types';
import { cn } from '@/shared/lib/utils';
import {
  useUpdateWorkspaceSettings,
  useWorkspaceSettings,
} from './settingsQueries';
import type { WorkspaceSettingsRequest } from '@/types/settings';

export function SettingsPage() {
  const query = useWorkspaceSettings();
  const runtimes = useRuntimeBuilds();
  const update = useUpdateWorkspaceSettings();
  const [draft, setDraft] = useState<WorkspaceSettingsRequest>();
  const approvedRuntimes = (runtimes.data?.items ?? []).filter(
    (runtime) =>
      runtime.releaseChannel === 'STABLE' &&
      runtime.regressionStatus === 'STABLE' &&
      runtime.signatureVerified
  );

  useEffect(() => {
    if (!query.data) return;
    setDraft({
      workspaceName: query.data.workspaceName,
      defaultRuntimeBuildId: query.data.defaultRuntimeBuildId,
      defaultRegion: query.data.defaultRegion,
      defaultHumanTakeoverEnabled: query.data.defaultHumanTakeoverEnabled,
      remoteDesktopControlBitrateLimitKbps:
        query.data.remoteDesktopControlBitrateLimitKbps,
      remoteDesktopControlFrameRateLimitFps:
        query.data.remoteDesktopControlFrameRateLimitFps,
      remoteDesktopViewerBitrateLimitKbps:
        query.data.remoteDesktopViewerBitrateLimitKbps,
      remoteDesktopViewerFrameRateLimitFps:
        query.data.remoteDesktopViewerFrameRateLimitFps,
    });
  }, [query.data]);

  const dirty =
    draft != null &&
    query.data != null &&
    (draft.workspaceName !== query.data.workspaceName ||
      draft.defaultRuntimeBuildId !== query.data.defaultRuntimeBuildId ||
      draft.defaultRegion !== query.data.defaultRegion ||
      draft.defaultHumanTakeoverEnabled !==
        query.data.defaultHumanTakeoverEnabled ||
      draft.remoteDesktopControlBitrateLimitKbps !==
        query.data.remoteDesktopControlBitrateLimitKbps ||
      draft.remoteDesktopControlFrameRateLimitFps !==
        query.data.remoteDesktopControlFrameRateLimitFps ||
      draft.remoteDesktopViewerBitrateLimitKbps !==
        query.data.remoteDesktopViewerBitrateLimitKbps ||
      draft.remoteDesktopViewerFrameRateLimitFps !==
        query.data.remoteDesktopViewerFrameRateLimitFps);
  const requestId = isSessionApiError(update.error)
    ? update.error.body.requestId
    : undefined;

  return (
    <div>
      <TopContextBar
        title="设置"
        subtitle="Workspace 权威默认值与桌面客户端状态"
      />

      <div className="flex flex-col gap-4 p-4 sm:p-6 lg:flex-row">
        <div className="shrink-0 lg:w-[200px]">
          <nav
            aria-label="设置分区"
            className="flex gap-1 overflow-x-auto lg:block lg:space-y-0.5"
          >
            <a
              href="#workspace-settings"
              className="block shrink-0 bg-accent-soft px-3 py-2 text-[12px] font-medium text-accent lg:w-full"
            >
              工作区默认值
            </a>
            <a
              href="#desktop-settings"
              className="block shrink-0 px-3 py-2 text-[12px] text-text-secondary hover:bg-surface-2 lg:w-full"
            >
              桌面客户端
            </a>
          </nav>
        </div>

        <div className="min-w-0 flex-1 space-y-4">
          <section
            id="workspace-settings"
            aria-labelledby="workspace-settings-heading"
            className="border border-border-subtle bg-surface-1 p-4 sm:p-6"
          >
            <div className="mb-6 flex flex-wrap items-start justify-between gap-3">
              <div>
                <h2
                  id="workspace-settings-heading"
                  className="text-[15px] font-semibold text-text-primary"
                >
                  工作区默认值
                </h2>
                <p className="mt-1 text-[11px] leading-5 text-text-muted">
                  只影响后续新建环境；创建时会固化 Runtime、区域和
                  HumanTakeover，不会重写存量 Session。
                </p>
              </div>
              {query.data && (
                <span className="border border-border-subtle bg-surface-2 px-2 py-1 font-mono text-[9px] text-text-muted">
                  {query.data.source === 'WORKSPACE_OVERRIDE'
                    ? `WORKSPACE · v${query.data.version}`
                    : 'SYSTEM DEFAULT'}
                </span>
              )}
            </div>

            {query.isLoading ? (
              <LoadingRows rows={4} />
            ) : query.isError || !draft ? (
              <ErrorState
                title="无法读取 Workspace Settings"
                error={query.error}
                onRetry={() => query.refetch()}
              />
            ) : (
              <form
                className="space-y-6"
                onSubmit={(event) => {
                  event.preventDefault();
                  update.mutate(draft);
                }}
              >
                <SettingGroup
                  label="工作区名称"
                  description="显示在侧边栏品牌区域；1—96 个字符。"
                  htmlFor="workspace-name"
                >
                  <input
                    id="workspace-name"
                    value={draft.workspaceName}
                    maxLength={96}
                    required
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        workspaceName: event.target.value,
                      })
                    }
                    className="field-input w-full max-w-[520px]"
                  />
                </SettingGroup>

                <SettingGroup
                  label="默认 Runtime"
                  description="只允许选择已发布、验证稳定且签名通过的 Runtime Build。"
                  htmlFor="default-runtime"
                >
                  {runtimes.isLoading ? (
                    <p className="text-[11px] text-text-muted">
                      正在读取 Runtime Registry…
                    </p>
                  ) : runtimes.isError ? (
                    <p role="alert" className="text-[11px] text-danger">
                      无法读取 Runtime Registry，暂不能保存设置。
                    </p>
                  ) : (
                    <select
                      id="default-runtime"
                      value={draft.defaultRuntimeBuildId}
                      required
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          defaultRuntimeBuildId: event.target.value,
                        })
                      }
                      className="field-input w-full max-w-[520px]"
                    >
                      {!approvedRuntimes.some(
                        (runtime) =>
                          runtime.buildId === draft.defaultRuntimeBuildId
                      ) && (
                        <option value={draft.defaultRuntimeBuildId} disabled>
                          {draft.defaultRuntimeBuildId} · 当前不可用于新建
                        </option>
                      )}
                      {approvedRuntimes.map((runtime) => (
                        <option key={runtime.buildId} value={runtime.buildId}>
                          {runtime.engine} {runtime.version} · {runtime.buildId}
                        </option>
                      ))}
                    </select>
                  )}
                </SettingGroup>

                <SettingGroup
                  label="默认区域"
                  description="客户端未显式选择区域时使用；服务端仍执行 Residency 与容量准入。"
                  htmlFor="default-region"
                >
                  <input
                    id="default-region"
                    value={draft.defaultRegion}
                    required
                    maxLength={32}
                    pattern="[a-z0-9-]{1,32}"
                    onChange={(event) =>
                      setDraft({
                        ...draft,
                        defaultRegion: event.target.value.toLowerCase(),
                      })
                    }
                    className="field-input w-full max-w-[520px] font-mono"
                  />
                </SettingGroup>

                <SettingGroup
                  label="HumanTakeover 默认值"
                  description="关闭后，新建 Session 默认拒绝人工接管；创建向导仍可显式覆盖。"
                  htmlFor="default-human-takeover"
                >
                  <label className="inline-flex min-h-11 items-center gap-3 border border-border-subtle bg-surface-2 px-3">
                    <input
                      id="default-human-takeover"
                      type="checkbox"
                      checked={draft.defaultHumanTakeoverEnabled}
                      onChange={(event) =>
                        setDraft({
                          ...draft,
                          defaultHumanTakeoverEnabled: event.target.checked,
                        })
                      }
                      className="h-4 w-4 accent-accent"
                    />
                    <span className="text-[12px] text-text-secondary">
                      新建环境默认允许 HumanTakeover
                    </span>
                  </label>
                </SettingGroup>

                <SettingGroup
                  label="默认资源策略"
                  description="普通用户不选择资源等级；内部模板由 Control Plane 解析。"
                >
                  <div className="w-full max-w-[520px] border border-accent/30 bg-accent-soft px-3 py-2.5">
                    <p className="font-mono text-[11px] text-accent">
                      AUTO · PAUSE_AGENT
                    </p>
                    <p className="mt-1 text-[10px] text-text-muted">
                      达到上限时暂停 Agent、保留 Browser；Group
                      或创建时显式策略优先。
                    </p>
                  </div>
                </SettingGroup>

                <SettingGroup
                  label="远程桌面 Actor 配额"
                  description="每个 Actor 的所有 VNC 窗口共享此画面输出上限；只限制画面，不会切断 Agent 或延迟真人输入。"
                >
                  <div className="grid w-full max-w-[720px] gap-3 border border-border-subtle bg-surface-2 p-3 sm:grid-cols-2">
                    <QuotaInput
                      id="desktop-control-bitrate"
                      label="协作控制带宽"
                      unit="Kbps"
                      min={250}
                      max={100000}
                      value={draft.remoteDesktopControlBitrateLimitKbps ?? 8000}
                      onChange={(value) =>
                        setDraft({
                          ...draft,
                          remoteDesktopControlBitrateLimitKbps: value,
                        })
                      }
                    />
                    <QuotaInput
                      id="desktop-control-fps"
                      label="协作控制帧率"
                      unit="FPS"
                      min={1}
                      max={60}
                      value={draft.remoteDesktopControlFrameRateLimitFps ?? 30}
                      onChange={(value) =>
                        setDraft({
                          ...draft,
                          remoteDesktopControlFrameRateLimitFps: value,
                        })
                      }
                    />
                    <QuotaInput
                      id="desktop-viewer-bitrate"
                      label="只读观察带宽"
                      unit="Kbps"
                      min={250}
                      max={100000}
                      value={draft.remoteDesktopViewerBitrateLimitKbps ?? 4000}
                      onChange={(value) =>
                        setDraft({
                          ...draft,
                          remoteDesktopViewerBitrateLimitKbps: value,
                        })
                      }
                    />
                    <QuotaInput
                      id="desktop-viewer-fps"
                      label="只读观察帧率"
                      unit="FPS"
                      min={1}
                      max={60}
                      value={draft.remoteDesktopViewerFrameRateLimitFps ?? 15}
                      onChange={(value) =>
                        setDraft({
                          ...draft,
                          remoteDesktopViewerFrameRateLimitFps: value,
                        })
                      }
                    />
                  </div>
                </SettingGroup>

                {update.error && (
                  <p role="alert" className="text-[11px] text-danger">
                    {update.error.message}
                    {requestId ? ` · Request ${requestId}` : ''}
                  </p>
                )}

                <div className="flex flex-wrap items-center justify-between gap-3">
                  <p aria-live="polite" className="text-[10px] text-text-muted">
                    {update.isSuccess && !dirty
                      ? '设置已写入 PostgreSQL，并记录权限审计。'
                      : query.data?.updatedAt
                        ? `最近更新：${new Date(query.data.updatedAt).toLocaleString()} · ${query.data.updatedBy}`
                        : '尚未创建工作区覆盖；当前来自服务端系统默认值。'}
                  </p>
                  <button
                    type="submit"
                    disabled={
                      !dirty ||
                      update.isPending ||
                      runtimes.isError ||
                      approvedRuntimes.length === 0
                    }
                    className="inline-flex h-10 items-center gap-2 bg-accent px-4 text-[12px] font-semibold text-canvas disabled:cursor-not-allowed disabled:opacity-40"
                  >
                    {update.isPending ? (
                      <LoaderCircle
                        aria-hidden="true"
                        className="h-4 w-4 animate-spin"
                      />
                    ) : (
                      <Save aria-hidden="true" className="h-4 w-4" />
                    )}
                    保存工作区设置
                  </button>
                </div>
              </form>
            )}
          </section>

          <section
            id="desktop-settings"
            aria-labelledby="desktop-settings-heading"
            className="border border-border-subtle bg-surface-1 p-4 sm:p-6"
          >
            <h2
              id="desktop-settings-heading"
              className="mb-6 text-[15px] font-semibold text-text-primary"
            >
              桌面客户端
            </h2>
            <DesktopStatusSection />
          </section>
        </div>
      </div>
    </div>
  );
}

function QuotaInput({
  id,
  label,
  unit,
  min,
  max,
  value,
  onChange,
}: {
  id: string;
  label: string;
  unit: string;
  min: number;
  max: number;
  value: number;
  onChange: (value: number) => void;
}) {
  return (
    <label htmlFor={id} className="block">
      <span className="mb-1 block text-[10px] text-text-muted">{label}</span>
      <span className="flex items-center border border-border-default bg-canvas focus-within:border-accent/60">
        <input
          id={id}
          type="number"
          required
          min={min}
          max={max}
          value={value}
          onChange={(event) => onChange(event.currentTarget.valueAsNumber)}
          className="h-9 min-w-0 flex-1 bg-transparent px-2 font-mono text-[11px] text-text-primary outline-none"
        />
        <span className="border-l border-border-subtle px-2 font-mono text-[9px] text-text-muted">
          {unit}
        </span>
      </span>
    </label>
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
  htmlFor,
  children,
}: {
  label: string;
  description: string;
  htmlFor?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="border-b border-border-subtle pb-6">
      {htmlFor ? (
        <label
          htmlFor={htmlFor}
          className="text-[13px] font-medium text-text-primary"
        >
          {label}
        </label>
      ) : (
        <div className="text-[13px] font-medium text-text-primary">{label}</div>
      )}
      <p className="mb-2 text-[12px] text-text-muted">{description}</p>
      {children}
    </div>
  );
}
