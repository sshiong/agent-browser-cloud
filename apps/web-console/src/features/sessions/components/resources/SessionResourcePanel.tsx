import * as Dialog from '@radix-ui/react-dialog';
import {
  Activity,
  CircleAlert,
  Clock3,
  Cpu,
  Database,
  Gauge,
  LoaderCircle,
  MoveRight,
  Settings2,
  ShieldCheck,
  X,
} from 'lucide-react';
import { useEffect, useState } from 'react';
import {
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts';
import { cn } from '@/shared/lib/utils';
import { isSessionApiError } from '@/api/session';
import type {
  MaximumReachedPolicy,
  ResourceEventView,
  ResourcePolicyRequest,
  ResourcePolicyStatus,
  ResourceStreamConnectionState,
  SessionSafePointView,
  SessionMigrationView,
  SessionResourceView,
} from '@/types/session';

const statusLabels: Record<ResourcePolicyStatus, string> = {
  STABLE: '稳定',
  OBSERVING: '观察中',
  SCALING_UP: '扩容中',
  SCALING_DOWN: '缩容中',
  AT_MAXIMUM: '已达上限',
  WAITING_SAFE_POINT: '等待安全点',
  MIGRATING: '迁移中',
  AGENT_PAUSED: 'Agent 已暂停',
  HIBERNATING: '休眠中',
  CRITICAL: '严重',
};

export function SessionResourcePanel({
  resource,
  events,
  safePoint,
  safePointError,
  migration,
  streamState,
  loading,
  error,
  canAdminister,
  platformAdmin,
  humanTakeover,
  updating,
  updateError,
  onRetry,
  onUpdate,
}: {
  resource?: SessionResourceView;
  events: ResourceEventView[];
  safePoint?: SessionSafePointView;
  safePointError: unknown;
  migration?: SessionMigrationView;
  streamState: ResourceStreamConnectionState;
  loading: boolean;
  error: unknown;
  canAdminister: boolean;
  platformAdmin: boolean;
  humanTakeover: boolean;
  updating: boolean;
  updateError: unknown;
  onRetry: () => unknown;
  onUpdate: (policy: ResourcePolicyRequest) => Promise<unknown>;
}) {
  if (loading) {
    return (
      <section className="rounded-[10px] border border-border-subtle bg-surface-1 p-5">
        <div className="flex items-center gap-2 text-[11px] text-text-muted">
          <LoaderCircle size={14} className="animate-spin" />
          正在读取资源策略与真实用量
        </div>
      </section>
    );
  }

  if (error || !resource) {
    return (
      <section className="rounded-[10px] border border-danger/25 bg-surface-1 p-5">
        <p className="text-[12px] text-danger">资源状态读取失败。</p>
        <button
          type="button"
          onClick={onRetry}
          className="mt-3 border border-border-default px-3 py-1.5 text-[11px] text-text-secondary"
        >
          重试
        </button>
      </section>
    );
  }

  const pausedExtensionIds = resource.allocation?.pausedExtensionIds ?? [];

  return (
    <section
      className="overflow-hidden rounded-[10px] border border-border-subtle bg-surface-1"
      aria-labelledby="session-resource-title"
    >
      <ResourceStreamHealth state={streamState} />
      <ResourcePolicyCard
        resource={resource}
        canAdminister={canAdminister}
        platformAdmin={platformAdmin}
        humanTakeover={humanTakeover}
        updating={updating}
        updateError={updateError}
        onUpdate={onUpdate}
      />

      <div className="grid border-t border-border-subtle xl:grid-cols-[minmax(0,1.25fr)_minmax(280px,0.75fr)]">
        <div className="space-y-5 p-5">
          <div className="grid gap-px overflow-hidden border border-border-subtle bg-border-subtle sm:grid-cols-3">
            <Metric
              icon={Cpu}
              label="当前分配"
              value={
                resource.allocation
                  ? `${formatCpu(resource.allocation.cpuMillis)} / ${formatMemory(resource.allocation.memoryLimitMib)}`
                  : '尚未分配'
              }
              detail={
                resource.allocation
                  ? `${resource.allocation.template} · State ${resource.allocation.stateCollectorBudgetPercent ?? '—'}% · Desktop ${
                      resource.allocation.remoteDesktopBitrateKbps
                        ? `${resource.allocation.remoteDesktopBitrateKbps} Kbps`
                        : '未启用'
                    } · Extension weight ${resource.allocation.extensionCpuWeight ?? '—'} · Media ${resource.allocation.mediaEncoderSlots ?? 0}/${resource.allocation.mediaEncoderSlotLimit ?? 0} slots`
                  : '等待 Placement'
              }
            />
            <Metric
              icon={Gauge}
              label="当前使用"
              value={
                resource.usage
                  ? `CPU ${formatPercent(resource.usage.cpuPercent)} / MEM ${formatPercent(resource.usage.memoryPercentOfLimit)}`
                  : '等待 Node 遥测'
              }
              detail={
                resource.usage?.observedAt
                  ? formatDate(resource.usage.observedAt)
                  : '不生成模拟指标'
              }
            />
            <Metric
              icon={Database}
              label="允许上限"
              value={`${formatCpu(resource.policy.maximumCpuMillis)} / ${formatMemory(resource.policy.maximumMemoryMib)}`}
              detail="Tenant / Workspace / Node 共同裁决"
            />
          </div>

          {resource.allocation ? (
            <div
              className="grid gap-px overflow-hidden border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-5"
              aria-label="非核心资源保护状态"
            >
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  后台标签
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  {resource.allocation.backgroundTabsFrozen
                    ? '已由 Node 冻结'
                    : '正常运行'}
                </p>
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  新建标签
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  {resource.allocation.newTabsBlocked
                    ? '已由 Node 阻断'
                    : `允许，预算 ${resource.allocation.tabBudget ?? '—'}`}
                </p>
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  扩展后台任务
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  {pausedExtensionIds.length > 0
                    ? `Node 已暂停 ${pausedExtensionIds.length} 个非特权扩展`
                    : '正常运行'}
                </p>
                {pausedExtensionIds.length > 0 && (
                  <p
                    className="mt-1 truncate font-mono text-[9px] text-text-muted"
                    title={pausedExtensionIds.join(', ')}
                  >
                    {pausedExtensionIds.join(', ')}
                  </p>
                )}
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  成功 Trace
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  Node 采样{' '}
                  {resource.allocation.successTraceSamplePercent ?? 100}%
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  失败与强制证据始终保留
                </p>
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  成功截图
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  Node 采样{' '}
                  {resource.allocation.successScreenshotSamplePercent ?? 100}%
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  失败动作与导航截图始终尝试留证
                </p>
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  Observer 帧率
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  {resource.allocation.observerFrameRateFps
                    ? `Node 限制 ${resource.allocation.observerFrameRateFps} FPS`
                    : '未启用桌面数据面'}
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  Human Input 不经过此节流器
                </p>
              </div>
              <div className="bg-surface-2 px-3 py-2.5">
                <p className="text-[10px] font-semibold uppercase tracking-[0.14em] text-text-muted">
                  像素录制
                </p>
                <p className="mt-1 text-xs font-medium text-text-primary">
                  {resource.allocation.videoRecordingEnabled
                    ? 'Node 正在录制'
                    : resource.allocation.videoRecordingRequested
                      ? '已由资源策略停止'
                      : '创建时未请求'}
                </p>
                <p className="mt-1 text-[9px] text-text-muted">
                  独立 CDP 数据面 · Storage Helper 提交
                </p>
              </div>
            </div>
          ) : null}

          <ResourceUsageChart resource={resource} />

          <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-5">
            <ResourceLimitProgress
              label="CPU 压力"
              value={resource.usage?.cpuPercent}
              detail={`${formatCpu(resource.allocation?.cpuMillis)} allocated`}
            />
            <ResourceLimitProgress
              label="内存上限"
              value={resource.usage?.memoryPercentOfLimit}
              detail={`${formatMemory(resource.usage?.memoryRssMib)} RSS`}
            />
            <ResourceLimitProgress
              label="Extension 压力"
              value={resource.usage?.extensionCpuPercent}
              detail={`${formatMemory(resource.usage?.extensionMemoryMib)} Extension RSS`}
            />
            <ResourceLimitProgress
              label="Media Encoder"
              value={resource.usage?.mediaEncoderPercent}
              detail={
                resource.allocation?.mediaEncoderSlotLimit
                  ? `${resource.allocation.mediaEncoderSlots ?? 0}/${resource.allocation.mediaEncoderSlotLimit} slots`
                  : '当前 Session 未预留编码 Slot'
              }
            />
            <ResourceLimitProgress
              label="Profile I/O"
              value={
                resource.usage?.profileIoBytesPerSecond == null
                  ? undefined
                  : (resource.usage.profileIoBytesPerSecond /
                      (50 * 1024 * 1024)) *
                    100
              }
              detail={
                resource.usage?.profileIoBytesPerSecond == null
                  ? '等待 Linux Browser Cgroup I/O 遥测'
                  : `${formatRate(resource.usage.profileIoBytesPerSecond)} · 50 MiB/s 压力线`
              }
            />
          </div>

          <CapacityWarning resource={resource} />
        </div>

        <div className="border-t border-border-subtle p-5 xl:border-l xl:border-t-0">
          <MigrationStatusCard
            resource={resource}
            safePoint={safePoint}
            safePointError={safePointError}
            migration={migration}
          />
          <ResourceAdjustmentTimeline events={events} />
        </div>
      </div>
    </section>
  );
}

function ResourceStreamHealth({
  state,
}: {
  state: ResourceStreamConnectionState;
}) {
  const live = state === 'LIVE';
  const offline = state === 'OFFLINE';
  return (
    <div
      role="status"
      aria-live="polite"
      className={cn(
        'flex items-center gap-2 border-b px-5 py-2 text-[10px]',
        live
          ? 'border-accent/15 bg-accent/[0.04] text-text-muted'
          : 'border-warning/25 bg-warning/[0.06] text-warning'
      )}
    >
      <span
        className={cn(
          'h-1.5 w-1.5 rounded-full',
          live
            ? 'bg-accent'
            : offline
              ? 'bg-danger'
              : 'animate-pulse bg-warning'
        )}
      />
      {live
        ? '实时事件已连接 · PostgreSQL 序列支持断点恢复'
        : offline
          ? '网络已断开，资源数据可能过期；恢复网络后将自动补齐事件'
          : state === 'RECONNECTING'
            ? '实时事件正在重连，当前资源数据可能过期'
            : '正在建立资源事件流，当前数据来自最近一次权威读取'}
    </div>
  );
}

export function ResourcePolicyCard({
  resource,
  canAdminister,
  platformAdmin,
  humanTakeover,
  updating,
  updateError,
  onUpdate,
}: {
  resource: SessionResourceView;
  canAdminister: boolean;
  platformAdmin: boolean;
  humanTakeover: boolean;
  updating: boolean;
  updateError: unknown;
  onUpdate: (policy: ResourcePolicyRequest) => Promise<unknown>;
}) {
  return (
    <header className="flex flex-wrap items-start justify-between gap-4 p-5">
      <div>
        <div className="flex flex-wrap items-center gap-2">
          <h2
            id="session-resource-title"
            className="text-[13px] font-semibold text-text-primary"
          >
            自动资源策略
          </h2>
          <ResourcePressureBadge
            status={resource.status}
            freshness={resource.dataFreshness}
          />
        </div>
        <p className="mt-1 text-[11px] text-text-muted">
          当前模板{' '}
          <span className="font-mono text-text-secondary">
            {resource.policy.resolvedTemplate}
          </span>{' '}
          · 运行环境 {resource.policy.executionEnvironment}
        </p>
        <p className="mt-1 text-[10px] text-text-muted">
          {humanTakeover
            ? 'HumanTakeover 正在进行，自动迁移保持禁止。'
            : translateReason(resource.statusReason)}
        </p>
        {resource.cost && (
          <p className="mt-1 text-[10px] text-text-muted">
            当前成本{' '}
            <span className="font-mono text-text-secondary">
              ${resource.cost.currentHourlyCost?.toFixed(6) ?? '—'}/h
            </span>
            {resource.cost.maximumHourlyCost !== undefined &&
              ` · 上限 $${resource.cost.maximumHourlyCost.toFixed(6)}/h`}
            {resource.cost.pricingVersion &&
              ` · ${resource.cost.pricingVersion}`}
          </p>
        )}
      </div>
      {canAdminister && (
        <ResourcePolicyDrawer
          resource={resource}
          platformAdmin={platformAdmin}
          humanTakeover={humanTakeover}
          updating={updating}
          updateError={updateError}
          onUpdate={onUpdate}
        />
      )}
    </header>
  );
}

export function ResourcePressureBadge({
  status,
  freshness,
}: {
  status: ResourcePolicyStatus;
  freshness: SessionResourceView['dataFreshness'];
}) {
  const critical = ['CRITICAL', 'AT_MAXIMUM', 'AGENT_PAUSED'].includes(status);
  const active = [
    'SCALING_UP',
    'SCALING_DOWN',
    'MIGRATING',
    'WAITING_SAFE_POINT',
  ].includes(status);
  return (
    <span
      className={cn(
        'inline-flex items-center gap-1 border px-1.5 py-0.5 font-mono text-[9px] uppercase tracking-[0.1em]',
        critical
          ? 'border-danger/35 bg-danger/10 text-danger'
          : active
            ? 'border-warning/35 bg-warning/10 text-warning'
            : 'border-accent/30 bg-accent-soft text-accent'
      )}
    >
      <span className="h-1.5 w-1.5 rounded-full bg-current" />
      {statusLabels[status]} ·{' '}
      {freshness === 'LIVE'
        ? '实时'
        : freshness === 'STALE'
          ? '数据过期'
          : '等待遥测'}
    </span>
  );
}

export function ResourceUsageChart({
  resource,
}: {
  resource: SessionResourceView;
}) {
  if (resource.usageSamples.length < 2) {
    return (
      <div className="flex min-h-36 items-center justify-center border border-dashed border-border-default bg-surface-2 p-5 text-center">
        <div>
          <Activity size={18} className="mx-auto text-text-muted" />
          <p className="mt-2 text-[11px] text-text-secondary">
            尚无足够的 Session 级遥测样本
          </p>
          <p className="mt-1 text-[10px] text-text-muted">
            Browser Node 每 5 秒上报后才绘制真实曲线。
          </p>
        </div>
      </div>
    );
  }
  const data = resource.usageSamples.map((point) => ({
    ...point,
    time: new Date(point.observedAt).toLocaleTimeString('zh-CN', {
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
    }),
  }));
  return (
    <div>
      <div className="mb-2 flex items-center justify-between">
        <p className="text-[11px] font-medium text-text-secondary">
          真实资源用量
        </p>
        <p className="font-mono text-[9px] uppercase tracking-[0.1em] text-text-muted">
          CPU / Memory limit %
        </p>
      </div>
      <div className="h-44 border border-border-subtle bg-surface-2 p-2">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={data}>
            <CartesianGrid stroke="rgba(126, 151, 163, .10)" vertical={false} />
            <XAxis
              dataKey="time"
              tick={{ fill: '#78909c', fontSize: 9 }}
              axisLine={false}
              tickLine={false}
            />
            <YAxis
              domain={[0, 100]}
              tick={{ fill: '#78909c', fontSize: 9 }}
              axisLine={false}
              tickLine={false}
            />
            <Tooltip
              contentStyle={{
                background: '#101a22',
                border: '1px solid rgba(126,151,163,.25)',
                fontSize: 11,
              }}
            />
            <Line
              type="monotone"
              dataKey="cpuPercent"
              name="CPU %"
              stroke="#54d6c3"
              dot={false}
              isAnimationActive={false}
            />
            <Line
              type="monotone"
              dataKey="memoryPercentOfLimit"
              name="Memory %"
              stroke="#77a8ff"
              dot={false}
              isAnimationActive={false}
            />
          </LineChart>
        </ResponsiveContainer>
      </div>
    </div>
  );
}

export function ResourceLimitProgress({
  label,
  value,
  detail,
}: {
  label: string;
  value?: number;
  detail: string;
}) {
  const safe = Math.min(100, Math.max(0, value ?? 0));
  return (
    <div className="border border-border-subtle bg-surface-2 p-3">
      <div className="flex items-center justify-between">
        <span className="text-[10px] text-text-muted">{label}</span>
        <span className="font-mono text-[10px] text-text-secondary">
          {value == null ? '—' : `${Math.round(value)}%`}
        </span>
      </div>
      <div className="mt-2 h-1 bg-surface-3">
        <div
          className={cn(
            'h-full',
            safe >= 90 ? 'bg-danger' : safe >= 75 ? 'bg-warning' : 'bg-accent'
          )}
          style={{ width: `${safe}%` }}
        />
      </div>
      <p className="mt-2 text-[9px] text-text-muted">{detail}</p>
    </div>
  );
}

export function MigrationStatusCard({
  resource,
  safePoint,
  safePointError,
  migration,
}: {
  resource: SessionResourceView;
  safePoint?: SessionSafePointView;
  safePointError: unknown;
  migration?: SessionMigrationView;
}) {
  const safePointText = safePointError
    ? '安全点判定暂不可用，迁移保持禁止'
    : !safePoint
      ? '正在读取安全点判定'
      : safePoint.safe
        ? '安全点已满足'
        : safePoint.state === 'UNKNOWN'
          ? '安全信号缺失或过期，迁移保持禁止'
          : `存在 ${safePoint.blockers.length} 个迁移阻塞项`;
  return (
    <div className="border-b border-border-subtle pb-5">
      <p className="text-[10px] uppercase tracking-[0.12em] text-text-muted">
        Migration State
      </p>
      <div className="mt-3 flex items-center gap-2 text-[11px] text-text-secondary">
        <ShieldCheck size={14} className="text-accent" />
        {resource.status === 'WAITING_SAFE_POINT'
          ? '等待安全点，Browser 保持运行'
          : resource.status === 'MIGRATING'
            ? 'Checkpoint 与恢复链路执行中'
            : '未进行迁移'}
      </div>
      <p className="mt-2 text-[10px] leading-4 text-text-muted">
        允许迁移：{resource.policy.allowMigration ? '是' : '否'} · HumanTakeover
        屏障：
        {resource.policy.blockMigrationDuringHumanTakeover ? '开启' : '关闭'}
      </p>
      {migration && (
        <div className="mt-3 grid grid-cols-2 gap-px overflow-hidden border border-border-subtle bg-border-subtle text-[9px]">
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">阶段</span>
            <p className="mt-1 font-mono text-text-secondary">
              {migration.phase}
            </p>
          </div>
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">节点</span>
            <p className="mt-1 truncate font-mono text-text-secondary">
              {migration.sourceNodeId} → {migration.targetNodeId ?? '待分配'}
            </p>
          </div>
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">Checkpoint</span>
            <p className="mt-1 truncate font-mono text-text-secondary">
              {migration.checkpointId ?? '提交中'}
            </p>
          </div>
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">恢复验证</span>
            <p className="mt-1 truncate font-mono text-text-secondary">
              {migration.recoveryResult ??
                migration.failureReason ??
                '等待验证'}
            </p>
          </div>
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">目标尝试</span>
            <p className="mt-1 font-mono text-text-secondary">
              {migration.targetAttempt}/{migration.maximumTargetAttempts}
            </p>
          </div>
          <div className="bg-surface-2 p-2">
            <span className="text-text-muted">失败目标</span>
            <p
              className="mt-1 truncate font-mono text-text-secondary"
              title={migration.failedTargetNodeIds.join(', ')}
            >
              {migration.failedTargetNodeIds.length > 0
                ? migration.failedTargetNodeIds.join(', ')
                : '无'}
            </p>
          </div>
          {(migration.targetCleanupOperationId ||
            migration.lastTargetFailureReason) && (
            <div className="col-span-2 bg-surface-2 p-2">
              <span className="text-text-muted">目标清理 / 最近失败</span>
              <p className="mt-1 break-all font-mono text-text-secondary">
                {migration.targetCleanupOperationId ?? '未创建清理 Operation'}
                {' · '}
                {migration.lastTargetFailureReason ?? '无失败原因'}
              </p>
            </div>
          )}
        </div>
      )}
      <div className="mt-3 border border-border-subtle bg-surface-2 p-3">
        <div className="flex items-center justify-between gap-3">
          <span className="text-[10px] text-text-secondary">
            {safePointText}
          </span>
          {safePoint && (
            <span className="font-mono text-[9px] text-text-muted">
              {safePoint.dataFreshness}
            </span>
          )}
        </div>
        {safePoint && safePoint.blockers.length > 0 && (
          <ul className="mt-2 space-y-1">
            {safePoint.blockers.slice(0, 4).map((blocker, index) => (
              <li
                key={`${blocker.code}-${blocker.source}-${index}`}
                className="flex items-start gap-1.5 text-[9px] leading-4 text-text-muted"
              >
                <CircleAlert
                  size={11}
                  className="mt-0.5 shrink-0 text-warning"
                />
                <span>
                  {translateSafePointBlocker(blocker.code)} · {blocker.source}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  );
}

function translateSafePointBlocker(code: string) {
  const labels: Record<string, string> = {
    ACTIVE_INPUT: '仍有按键或鼠标输入',
    ACTIVE_DRAG: '拖拽操作尚未结束',
    HUMAN_TAKEOVER_ACTIVE: '人工接管正在进行',
    HUMAN_HANDOFF_PENDING: '人工接管交接待处理',
    AGENT_TASK_ACTIVE: 'Agent 步骤仍在执行',
    EXCLUSIVE_OPERATION_ACTIVE: '排他操作仍在执行',
    SNAPSHOT_IN_PROGRESS: 'Snapshot 正在提交',
    PROFILE_FLUSH_IN_PROGRESS: 'Profile 正在写回',
    DURABLE_WORKFLOW_ACTIVE: '持久工作流尚未完成',
    FILE_UPLOAD_ACTIVE: '文件上传尚未完成',
    FILE_DOWNLOAD_ACTIVE: '文件下载尚未完成',
    FORM_SUBMISSION_ACTIVE: '页面表单提交尚未完成',
    FILE_TRANSFER: '文件传输尚未完成',
    FORM_SUBMISSION: '表单正在提交',
    PAYMENT_OR_SECURITY: '支付或账号安全操作进行中',
    CRITICAL_TRANSACTION: '关键业务事务尚未完成',
    BUSINESS_RECOVERY_UNKNOWN: '业务恢复状态未知',
    NODE_SAFETY_SIGNAL_MISSING: '节点安全信号尚未上报',
    NODE_SAFETY_SIGNAL_STALE: '节点安全信号已过期',
  };
  return labels[code] ?? code;
}

export function ResourceAdjustmentTimeline({
  events,
}: {
  events: ResourceEventView[];
}) {
  return (
    <div className="pt-5">
      <div className="flex items-center justify-between">
        <h3 className="text-[11px] font-medium text-text-secondary">
          调整时间线
        </h3>
        <span className="font-mono text-[9px] text-text-muted">
          {events.length} events
        </span>
      </div>
      {events.length ? (
        <ol className="mt-4 space-y-4 border-l border-border-default pl-4">
          {events.slice(0, 8).map((event) => (
            <li key={event.eventId} className="relative">
              <span className="absolute -left-[19px] top-1 h-2 w-2 rounded-full border border-accent bg-surface-1" />
              <p className="text-[10px] text-text-secondary">
                {translateReason(event.reason)}
              </p>
              <p className="mt-1 font-mono text-[9px] text-text-muted">
                {formatDate(event.occurredAt)} · {event.decisionSource}
              </p>
              {(event.operationId || event.requestId) && (
                <p className="mt-1 truncate font-mono text-[9px] text-text-muted">
                  {event.operationId ?? event.requestId}
                </p>
              )}
            </li>
          ))}
        </ol>
      ) : (
        <div className="mt-4 border border-dashed border-border-default p-4 text-center">
          <Clock3 size={14} className="mx-auto text-text-muted" />
          <p className="mt-2 text-[10px] text-text-muted">暂无资源事件</p>
        </div>
      )}
    </div>
  );
}

export function CapacityWarning({
  resource,
}: {
  resource: SessionResourceView;
}) {
  if (resource.status === 'STABLE' && resource.dataFreshness === 'LIVE') {
    return null;
  }
  return (
    <div
      className={cn(
        'flex items-start gap-3 border p-3',
        resource.status === 'CRITICAL'
          ? 'border-danger/30 bg-danger/8'
          : 'border-warning/25 bg-warning/8'
      )}
    >
      <CircleAlert
        size={15}
        className={
          resource.status === 'CRITICAL' ? 'text-danger' : 'text-warning'
        }
      />
      <div>
        <p className="text-[11px] text-text-secondary">
          {resource.dataFreshness === 'STALE'
            ? '连接中断，资源数据可能已过期'
            : resource.dataFreshness === 'AWAITING_TELEMETRY'
              ? '等待 Browser Node 上报 Session 级指标'
              : translateReason(resource.statusReason)}
        </p>
        <p className="mt-1 text-[9px] text-text-muted">
          策略写操作由后端 Operation 提交，前端不会直接修改 cgroup。
        </p>
      </div>
    </div>
  );
}

export function ResourcePolicyDrawer({
  resource,
  platformAdmin,
  humanTakeover,
  updating,
  updateError,
  onUpdate,
}: {
  resource: SessionResourceView;
  platformAdmin: boolean;
  humanTakeover: boolean;
  updating: boolean;
  updateError: unknown;
  onUpdate: (policy: ResourcePolicyRequest) => Promise<unknown>;
}) {
  const [open, setOpen] = useState(false);
  const [maximum, setMaximum] = useState<MaximumReachedPolicy>(
    resource.policy.onMaximumReached
  );
  const [allowMigration, setAllowMigration] = useState(
    resource.policy.allowMigration
  );
  const [allowHibernate, setAllowHibernate] = useState(
    resource.policy.allowHibernate
  );
  const [maximumCost, setMaximumCost] = useState(
    resource.policy.maximumCostPerHour?.toString() ?? ''
  );
  const [strictConfirmed, setStrictConfirmed] = useState(false);

  useEffect(() => {
    setMaximum(resource.policy.onMaximumReached);
    setAllowMigration(resource.policy.allowMigration);
    setAllowHibernate(resource.policy.allowHibernate);
    setMaximumCost(resource.policy.maximumCostPerHour?.toString() ?? '');
    setStrictConfirmed(false);
  }, [resource.policy]);

  const submit = async () => {
    await onUpdate({
      mode: 'AUTO',
      onMaximumReached: maximum,
      allowMigration,
      allowHibernate,
      blockMigrationDuringHumanTakeover:
        resource.policy.blockMigrationDuringHumanTakeover,
      executionEnvironment: resource.policy.executionEnvironment,
      minimumTemplate: resource.policy.minimumTemplate,
      maximumCpuMillis: resource.policy.maximumCpuMillis,
      maximumMemoryMib: resource.policy.maximumMemoryMib,
      ...(maximumCost.trim()
        ? { maximumCostPerHour: Number(maximumCost) }
        : {}),
      scaleUpWindowSeconds: resource.policy.scaleUpWindowSeconds,
      scaleDownWindowSeconds: resource.policy.scaleDownWindowSeconds,
      adjustmentCooldownSeconds: resource.policy.adjustmentCooldownSeconds,
    });
    setOpen(false);
  };

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>
        <button
          type="button"
          className="inline-flex h-8 items-center gap-1.5 border border-border-default px-3 text-[10px] text-text-secondary hover:bg-surface-2"
        >
          <Settings2 size={12} />
          策略设置
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-40 bg-canvas/75" />
        <Dialog.Content className="fixed inset-y-0 right-0 z-50 w-full max-w-md overflow-y-auto border-l border-border-default bg-surface-1 p-6 shadow-2xl">
          <div className="flex items-start justify-between">
            <div>
              <Dialog.Title className="text-[15px] font-semibold text-text-primary">
                自动资源策略
              </Dialog.Title>
              <Dialog.Description className="mt-1 text-[10px] text-text-muted">
                提交后等待真实后端 Operation；不会直接修改 Node cgroup。
              </Dialog.Description>
            </div>
            <Dialog.Close className="text-text-muted" aria-label="关闭">
              <X size={17} />
            </Dialog.Close>
          </div>

          <fieldset className="mt-6">
            <legend className="text-[11px] font-medium text-text-secondary">
              达到上限时
            </legend>
            <div className="mt-3 space-y-2">
              {[
                ['PAUSE_AGENT', '暂停 Agent，保留浏览器'],
                ['WAIT_SAFE_POINT_MIGRATE', '等待安全点并迁移'],
                ['HIBERNATE', '自动休眠'],
                ...(platformAdmin
                  ? [['TERMINATE_STRICT', '严格预算，终止环境']]
                  : []),
              ].map(([value, label]) => (
                <label
                  key={value}
                  className="flex items-center gap-2 border border-border-subtle p-3 text-[11px] text-text-secondary has-[:checked]:border-accent/50"
                >
                  <input
                    type="radio"
                    checked={maximum === value}
                    onChange={() => {
                      setMaximum(value as MaximumReachedPolicy);
                      setStrictConfirmed(false);
                    }}
                  />
                  {label}
                </label>
              ))}
            </div>
          </fieldset>

          <label className="mt-5 block border-t border-border-subtle pt-4">
            <span className="text-[11px] font-medium text-text-secondary">
              每小时成本上限 (USD)
            </span>
            <input
              type="number"
              min="0.000001"
              max="10000"
              step="0.000001"
              value={maximumCost}
              onChange={(event) => setMaximumCost(event.target.value)}
              placeholder="由 Workspace 策略决定"
              className="field-input mt-2 font-mono"
            />
            <span className="mt-1 block text-[9px] text-text-muted">
              每 5 分钟按真实 Placement 与版本化费率重新计算。
            </span>
          </label>

          <label className="mt-5 flex items-start justify-between gap-4 border-t border-border-subtle pt-4">
            <span>
              <span className="block text-[11px] text-text-secondary">
                允许自动迁移
              </span>
              <span className="mt-1 block text-[9px] text-text-muted">
                {humanTakeover
                  ? 'HumanTakeover 期间强制禁用'
                  : '迁移仍需等待安全点'}
              </span>
            </span>
            <input
              type="checkbox"
              checked={allowMigration && !humanTakeover}
              disabled={humanTakeover}
              onChange={(event) => setAllowMigration(event.target.checked)}
            />
          </label>
          <label className="mt-4 flex items-center justify-between border-t border-border-subtle pt-4 text-[11px] text-text-secondary">
            允许自动休眠
            <input
              type="checkbox"
              checked={allowHibernate}
              onChange={(event) => setAllowHibernate(event.target.checked)}
            />
          </label>

          {maximum === 'TERMINATE_STRICT' && (
            <label className="mt-5 flex items-start gap-3 border border-danger/30 bg-danger/8 p-3 text-[10px] leading-4 text-danger">
              <input
                type="checkbox"
                checked={strictConfirmed}
                onChange={(event) => setStrictConfirmed(event.target.checked)}
                className="mt-0.5"
              />
              我确认：严格预算可能终止 Browser Session 并中断登录状态。
            </label>
          )}

          {Boolean(updateError) && (
            <p className="mt-4 text-[10px] text-danger">
              {updateError instanceof Error
                ? updateError.message
                : '策略更新失败'}
              {isSessionApiError(updateError) && updateError.body.requestId && (
                <span className="mt-1 block font-mono">
                  Request ID: {updateError.body.requestId}
                </span>
              )}
            </p>
          )}

          <button
            type="button"
            onClick={() => void submit()}
            disabled={
              updating || (maximum === 'TERMINATE_STRICT' && !strictConfirmed)
            }
            className="mt-6 inline-flex h-9 w-full items-center justify-center gap-2 bg-accent text-[11px] font-semibold text-canvas disabled:opacity-50"
          >
            {updating ? (
              <LoaderCircle size={13} className="animate-spin" />
            ) : (
              <MoveRight size={13} />
            )}
            提交 Resource Operation
          </button>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function Metric({
  icon: Icon,
  label,
  value,
  detail,
}: {
  icon: typeof Cpu;
  label: string;
  value: string;
  detail: string;
}) {
  return (
    <div className="bg-surface-2 p-3">
      <div className="flex items-center gap-1.5 text-[9px] uppercase tracking-[0.1em] text-text-muted">
        <Icon size={11} />
        {label}
      </div>
      <p className="mt-2 font-mono text-[11px] text-text-primary">{value}</p>
      <p className="mt-1 truncate text-[9px] text-text-muted">{detail}</p>
    </div>
  );
}

function formatCpu(value?: number) {
  if (value == null) return '—';
  return `${Number((value / 1000).toFixed(2))} vCPU`;
}

function formatMemory(value?: number) {
  if (value == null) return '—';
  return value >= 1024
    ? `${Number((value / 1024).toFixed(2))} GB`
    : `${value} MiB`;
}

function formatRate(value?: number) {
  if (value == null) return '—';
  if (value >= 1024 * 1024) {
    return `${(value / (1024 * 1024)).toFixed(1)} MiB/s`;
  }
  if (value >= 1024) {
    return `${(value / 1024).toFixed(1)} KiB/s`;
  }
  return `${Math.round(value)} B/s`;
}

function formatPercent(value?: number) {
  return value == null ? '—' : `${Math.round(value)}%`;
}

function formatDate(value: string) {
  return new Date(value).toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  });
}

function translateReason(reason?: string) {
  const labels: Record<string, string> = {
    AWAITING_RUNTIME_TELEMETRY: '等待 Runtime 遥测',
    PLACEMENT_RESOLVED_AWAITING_TELEMETRY: 'Placement 已解析，等待遥测',
    WINDOW_WITHIN_POLICY: '滑动窗口处于策略范围内',
    MAXIMUM_REACHED: '资源已达到允许上限',
    MAXIMUM_REACHED_AGENT_PAUSED: '达到上限，Agent 已暂停',
    SUSTAINED_PRESSURE_AWAITING_ACTUATOR: '检测到持续压力，等待 Node 执行器',
    AUTO_POLICY_ACCEPTED: '自动资源策略已接受',
    RESOURCE_POLICY_CHANGED: '资源策略已更新',
    PAUSE_AGENT_PRESERVE_BROWSER: '暂停 Agent，保留浏览器',
  };
  return reason ? (labels[reason] ?? reason.replaceAll('_', ' ')) : '无异常';
}
