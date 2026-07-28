import { lazy, Suspense } from 'react';
import { Link, Route, Routes } from 'react-router';
import { AppShell } from '@/components/layout/AppShell';
import { LoadingPanel } from '@/components/feedback/AsyncStates';
import { AuthGate, RequireRoles, UnauthorizedPage } from '@/auth/AuthGate';

const OverviewPage = lazy(() =>
  import('@/features/overview/OverviewPage').then((module) => ({
    default: module.OverviewPage,
  }))
);
const EnvironmentsPage = lazy(() =>
  import('@/features/environments/EnvironmentsPage').then((module) => ({
    default: module.EnvironmentsPage,
  }))
);
const SessionDetailPage = lazy(() =>
  import('@/features/sessions/SessionDetailPage').then((module) => ({
    default: module.SessionDetailPage,
  }))
);
const GroupsPage = lazy(() =>
  import('@/features/groups/GroupsPage').then((module) => ({
    default: module.GroupsPage,
  }))
);
const NodesPage = lazy(() =>
  import('@/features/nodes/NodesPage').then((module) => ({
    default: module.NodesPage,
  }))
);
const ProxiesPage = lazy(() =>
  import('@/features/proxies/ProxiesPage').then((module) => ({
    default: module.ProxiesPage,
  }))
);
const RuntimesPage = lazy(() =>
  import('@/features/runtimes/RuntimesPage').then((module) => ({
    default: module.RuntimesPage,
  }))
);
const ProfilesPage = lazy(() =>
  import('@/features/profiles/ProfilesPage').then((module) => ({
    default: module.ProfilesPage,
  }))
);
const ExtensionsPage = lazy(() =>
  import('@/features/extensions/ExtensionsPage').then((module) => ({
    default: module.ExtensionsPage,
  }))
);
const AutomationPage = lazy(() =>
  import('@/features/automation/AutomationPage').then((module) => ({
    default: module.AutomationPage,
  }))
);
const RemoteDesktopPage = lazy(() =>
  import('@/features/remote-desktop/RemoteDesktopPage').then((module) => ({
    default: module.RemoteDesktopPage,
  }))
);
const LogsPage = lazy(() =>
  import('@/features/logs/LogsPage').then((module) => ({
    default: module.LogsPage,
  }))
);
const SecurityPage = lazy(() =>
  import('@/features/security/SecurityPage').then((module) => ({
    default: module.SecurityPage,
  }))
);
const EnterpriseOperationsPage = lazy(() =>
  import('@/features/enterprise/EnterpriseOperationsPage').then((module) => ({
    default: module.EnterpriseOperationsPage,
  }))
);
const SettingsPage = lazy(() =>
  import('@/features/settings/SettingsPage').then((module) => ({
    default: module.SettingsPage,
  }))
);

export function App() {
  return (
    <AuthGate>
      <Suspense fallback={<LoadingPanel label="正在加载页面模块" />}>
        <Routes>
          <Route element={<AppShell />}>
            <Route path="/" element={<OverviewPage />} />
            <Route path="/overview" element={<OverviewPage />} />
            <Route path="/environments" element={<EnvironmentsPage />} />
            <Route path="/environments/:id" element={<SessionDetailPage />} />
            <Route path="/groups" element={<GroupsPage />} />
            <Route
              path="/nodes"
              element={
                <RequireRoles
                  roles={['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN']}
                >
                  <NodesPage />
                </RequireRoles>
              }
            />
            <Route path="/proxies" element={<ProxiesPage />} />
            <Route path="/runtimes" element={<RuntimesPage />} />
            <Route path="/profiles" element={<ProfilesPage />} />
            <Route
              path="/extensions"
              element={
                <RequireRoles
                  roles={['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN']}
                >
                  <ExtensionsPage />
                </RequireRoles>
              }
            />
            <Route
              path="/automation/tasks"
              element={
                <RequireRoles
                  roles={[
                    'TENANT_OPERATOR',
                    'TENANT_ADMIN',
                    'SECURITY_ADMIN',
                    'PLATFORM_ADMIN',
                  ]}
                >
                  <AutomationPage />
                </RequireRoles>
              }
            />
            <Route
              path="/remote-desktop"
              element={
                <RequireRoles
                  roles={[
                    'TENANT_OPERATOR',
                    'TENANT_ADMIN',
                    'SECURITY_ADMIN',
                    'PLATFORM_ADMIN',
                  ]}
                >
                  <RemoteDesktopPage />
                </RequireRoles>
              }
            />
            <Route path="/logs" element={<LogsPage />} />
            <Route
              path="/security"
              element={
                <RequireRoles roles={['SECURITY_ADMIN', 'PLATFORM_ADMIN']}>
                  <SecurityPage />
                </RequireRoles>
              }
            />
            <Route
              path="/enterprise"
              element={
                <RequireRoles
                  roles={['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN']}
                >
                  <EnterpriseOperationsPage />
                </RequireRoles>
              }
            />
            <Route
              path="/settings"
              element={
                <RequireRoles
                  roles={['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN']}
                >
                  <SettingsPage />
                </RequireRoles>
              }
            />
            <Route path="/unauthorized" element={<UnauthorizedPage />} />
            <Route path="*" element={<NotFoundPage />} />
          </Route>
        </Routes>
      </Suspense>
    </AuthGate>
  );
}

function NotFoundPage() {
  return (
    <div className="flex min-h-full items-center justify-center p-8">
      <div className="text-center">
        <p className="font-mono text-[11px] text-accent">
          404 / ROUTE_NOT_FOUND
        </p>
        <h1 className="mt-2 text-[18px] font-semibold text-text-primary">
          页面不存在
        </h1>
        <p className="mt-1 text-[12px] text-text-muted">
          该路由尚未注册或已经移动。
        </p>
        <Link
          to="/"
          className="mt-4 inline-flex h-8 items-center rounded-[7px] bg-accent px-3 text-[11px] font-medium text-canvas"
        >
          返回总览
        </Link>
      </div>
    </div>
  );
}
