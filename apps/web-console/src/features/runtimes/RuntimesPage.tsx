import {
  CheckCircle2,
  FileJson2,
  PackageCheck,
  ShieldCheck,
} from 'lucide-react';
import { TopContextBar } from '@/components/layout/TopContextBar';
import {
  EmptyState,
  ErrorState,
  LoadingRows,
} from '@/components/feedback/AsyncStates';
import { useRuntimeBuilds } from '@/features/security/platformQueries';
import { cn } from '@/shared/lib/utils';

export function RuntimesPage() {
  const query = useRuntimeBuilds();
  const builds = query.data?.items ?? [];
  const stable = builds.filter(
    (build) => build.releaseChannel === 'STABLE'
  ).length;
  const signed = builds.filter((build) => build.signatureVerified).length;
  const withSbom = builds.filter((build) => build.sbomUrl).length;

  return (
    <div>
      <TopContextBar
        title="Runtime 验证"
        subtitle="Build Registry、签名、SBOM 与发布准入的权威状态"
      />
      <main className="p-4 sm:p-6">
        <section className="grid border border-border-subtle bg-border-subtle sm:grid-cols-2 xl:grid-cols-4">
          <Metric
            icon={PackageCheck}
            label="登记构建"
            value={String(query.data?.total ?? 0)}
          />
          <Metric
            icon={CheckCircle2}
            label="Stable"
            value={String(stable)}
            tone="success"
          />
          <Metric icon={ShieldCheck} label="具备签名" value={String(signed)} />
          <Metric icon={FileJson2} label="具备 SBOM" value={String(withSbom)} />
        </section>

        <section className="mt-4 overflow-hidden border border-border-subtle bg-surface-1">
          {query.isLoading ? (
            <LoadingRows rows={5} />
          ) : query.isError ? (
            <ErrorState
              error={query.error}
              onRetry={() => query.refetch()}
              title="无法加载 Runtime Registry"
            />
          ) : builds.length === 0 ? (
            <EmptyState
              title="Runtime Registry 为空"
              description="未登记且未验证的 Runtime 不会进入 Browser Node 调度。"
            />
          ) : (
            <>
              <div className="hidden overflow-x-auto md:block">
                <table className="w-full min-w-[900px]">
                  <thead>
                    <tr className="border-b border-border-subtle bg-surface-2">
                      {[
                        'Build',
                        'Engine / Version',
                        'Platform',
                        'Security',
                        'Validation',
                        'Supply chain',
                        'Released',
                      ].map((label) => (
                        <th
                          key={label}
                          className="px-4 py-2.5 text-left text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted"
                        >
                          {label}
                        </th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {builds.map((build) => (
                      <tr
                        key={build.buildId}
                        className="border-b border-border-subtle last:border-0 hover:bg-surface-2/60"
                      >
                        <td className="px-4 py-3.5">
                          <p className="font-mono text-[11px] text-text-primary">
                            {build.buildId}
                          </p>
                        </td>
                        <td className="px-4 py-3.5">
                          <p className="text-[12px] text-text-primary">
                            {build.engine}
                          </p>
                          <p className="font-mono text-[10px] text-text-muted">
                            {build.version}
                          </p>
                        </td>
                        <td className="px-4 py-3.5 text-[11px] text-text-secondary">
                          {build.platform}
                        </td>
                        <td className="px-4 py-3.5">
                          <span className="bg-accent-soft px-2 py-0.5 text-[10px] font-semibold text-accent">
                            {build.securityTier}
                          </span>
                        </td>
                        <td className="px-4 py-3.5">
                          <span
                            className={cn(
                              'text-[11px] font-semibold',
                              build.releaseChannel === 'STABLE'
                                ? 'text-success'
                                : 'text-warning'
                            )}
                          >
                            {build.regressionStatus} / {build.releaseChannel}
                          </span>
                        </td>
                        <td className="px-4 py-3.5">
                          <p
                            className={cn(
                              'text-[10px]',
                              build.signatureVerified
                                ? 'text-success'
                                : 'text-danger'
                            )}
                          >
                            {build.signatureVerified
                              ? 'SIGNATURE PRESENT'
                              : 'UNSIGNED'}
                          </p>
                          <p className="mt-1 max-w-[170px] truncate font-mono text-[9px] text-text-muted">
                            {build.sbomUrl ?? 'SBOM MISSING'}
                          </p>
                        </td>
                        <td className="px-4 py-3.5 text-[10px] text-text-muted">
                          {build.releasedAt
                            ? new Date(build.releasedAt).toLocaleString()
                            : '未发布'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="divide-y divide-border-subtle md:hidden">
                {builds.map((build) => (
                  <article key={build.buildId} className="p-4">
                    <div className="flex items-start justify-between gap-3">
                      <code className="text-[11px] text-text-primary">
                        {build.buildId}
                      </code>
                      <span className="text-[10px] font-semibold text-success">
                        {build.releaseChannel}
                      </span>
                    </div>
                    <p className="mt-2 text-[11px] text-text-secondary">
                      {build.engine} {build.version} · {build.platform}
                    </p>
                    <p className="mt-1 text-[10px] text-text-muted">
                      {build.signatureVerified ? '已登记签名' : '缺少签名'} ·{' '}
                      {build.sbomUrl ? 'SBOM 已登记' : '缺少 SBOM'}
                    </p>
                  </article>
                ))}
              </div>
            </>
          )}
        </section>
      </main>
    </div>
  );
}

function Metric({
  icon: Icon,
  label,
  value,
  tone = 'accent',
}: {
  icon: React.ComponentType<{ size?: number; className?: string }>;
  label: string;
  value: string;
  tone?: 'accent' | 'success';
}) {
  return (
    <div className="flex min-h-24 items-center gap-3 bg-surface-1 px-4 py-4">
      <Icon
        size={17}
        className={tone === 'success' ? 'text-success' : 'text-accent'}
      />
      <div>
        <p className="text-[10px] font-semibold uppercase tracking-[0.12em] text-text-muted">
          {label}
        </p>
        <p className="mt-1 font-mono text-[18px] font-semibold text-text-primary">
          {value}
        </p>
      </div>
    </div>
  );
}
