import { NavLink, useLocation, useNavigate } from 'react-router';
import {
  LayoutDashboard,
  Monitor,
  FolderTree,
  Network,
  Cpu,
  HardDrive,
  Puzzle,
  Bot,
  Webcam,
  ScrollText,
  Shield,
  Settings,
  ChevronLeft,
  Plus,
  Boxes,
  Globe2,
} from 'lucide-react';
import { useUIStore } from '@/stores/ui';
import { cn } from '@/shared/lib/utils';
import { useAuth } from '@/auth/AuthProvider';
import type { PlatformRole } from '@/auth/runtimeIdentity';
import { fixturesEnabled } from '@/shared/runtimeConfig';

interface NavItem {
  label: string;
  icon: React.ReactNode;
  path: string;
  requiredRoles?: PlatformRole[];
  fixture?: boolean;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

const navGroups: NavGroup[] = [
  {
    title: '工作区',
    items: [
      { label: '总览', icon: <LayoutDashboard size={18} />, path: '/' },
      { label: '环境管理', icon: <Monitor size={18} />, path: '/environments' },
      {
        label: '分组与标签',
        icon: <FolderTree size={18} />,
        path: '/groups',
        fixture: true,
      },
      {
        label: 'Browser Node',
        icon: <Boxes size={18} />,
        path: '/nodes',
        requiredRoles: ['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN'],
      },
    ],
  },
  {
    title: '基础设施',
    items: [
      { label: '代理与出口', icon: <Network size={18} />, path: '/proxies' },
      { label: 'Runtime 与内核', icon: <Cpu size={18} />, path: '/runtimes' },
      {
        label: 'Profile 存储',
        icon: <HardDrive size={18} />,
        path: '/profiles',
      },
      {
        label: '扩展与应用',
        icon: <Puzzle size={18} />,
        path: '/extensions',
        requiredRoles: ['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN'],
      },
    ],
  },
  {
    title: '自动化',
    items: [
      {
        label: 'Agent 任务',
        icon: <Bot size={18} />,
        path: '/automation/tasks',
        requiredRoles: [
          'TENANT_OPERATOR',
          'TENANT_ADMIN',
          'SECURITY_ADMIN',
          'PLATFORM_ADMIN',
        ],
      },
    ],
  },
  {
    title: '运维与安全',
    items: [
      {
        label: '远程桌面',
        icon: <Webcam size={18} />,
        path: '/remote-desktop',
        requiredRoles: [
          'TENANT_OPERATOR',
          'TENANT_ADMIN',
          'SECURITY_ADMIN',
          'PLATFORM_ADMIN',
        ],
      },
      { label: '运行日志', icon: <ScrollText size={18} />, path: '/logs' },
      {
        label: '安全中心',
        icon: <Shield size={18} />,
        path: '/security',
        requiredRoles: ['SECURITY_ADMIN', 'PLATFORM_ADMIN'],
      },
      {
        label: '企业运营',
        icon: <Globe2 size={18} />,
        path: '/enterprise',
        requiredRoles: ['TENANT_ADMIN', 'SECURITY_ADMIN', 'PLATFORM_ADMIN'],
      },
    ],
  },
  {
    title: '系统',
    items: [
      {
        label: '设置',
        icon: <Settings size={18} />,
        path: '/settings',
        requiredRoles: ['PLATFORM_ADMIN'],
      },
    ],
  },
];

export function Sidebar() {
  const collapsed = useUIStore((s) => s.sidebarCollapsed);
  const toggle = useUIStore((s) => s.toggleSidebar);
  const location = useLocation();
  const navigate = useNavigate();
  const auth = useAuth();

  return (
    <aside
      aria-label="主导航"
      className={cn(
        'flex w-[68px] shrink-0 flex-col border-r border-border-subtle bg-sidebar transition-[width] duration-200',
        !collapsed && 'min-[1281px]:w-[236px]'
      )}
    >
      {/* Logo */}
      <div className="flex h-[56px] items-center gap-3 border-b border-border-subtle px-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/15 text-accent">
          <Boxes size={18} />
        </div>
        {!collapsed && (
          <div className="hidden flex-col min-[1281px]:flex">
            <span className="text-sm font-semibold text-text-primary">
              Agent Browser
            </span>
            <span className="text-[11px] text-text-muted">Runtime Console</span>
          </div>
        )}
      </div>

      {/* New Session Button */}
      {auth.canOperate && (
        <div className="px-3 py-3">
          <button
            type="button"
            onClick={() => navigate('/environments?create=1')}
            aria-label="新建浏览器环境"
            className={cn(
              'flex h-9 w-full items-center justify-center gap-2 rounded-[7px] border border-accent/35 bg-accent-soft px-3 text-[13px] font-medium text-accent transition-colors hover:border-accent/55 hover:bg-accent/20',
              collapsed && 'px-0',
              !collapsed && 'min-[1281px]:justify-start'
            )}
          >
            <Plus size={16} />
            {!collapsed && (
              <span className="hidden min-[1281px]:inline">新建环境</span>
            )}
          </button>
        </div>
      )}

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 pb-4">
        {navGroups.map((group) => (
          <div key={group.title} className="mb-4">
            {!collapsed && (
              <div className="mb-1 hidden px-2 text-[10px] font-medium uppercase tracking-[0.14em] text-text-muted min-[1281px]:block">
                {group.title}
              </div>
            )}
            {group.items.map((item) => {
              if (item.fixture && !fixturesEnabled) return null;
              if (item.requiredRoles && !auth.hasAnyRole(item.requiredRoles)) {
                return null;
              }
              const isActive =
                item.path === '/'
                  ? location.pathname === '/'
                  : location.pathname.startsWith(item.path);
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  aria-label={item.label}
                  title={item.label}
                  className={cn(
                    'group relative mb-0.5 flex h-9 items-center gap-3 rounded-md px-2 text-[13px] transition-colors',
                    isActive
                      ? 'bg-accent-soft text-accent'
                      : 'text-text-secondary hover:bg-surface-2 hover:text-text-primary'
                  )}
                >
                  {isActive && (
                    <div className="absolute left-0 top-1/2 h-4 w-[2px] -translate-y-1/2 rounded-r bg-accent" />
                  )}
                  <span className={cn('shrink-0', isActive && 'text-accent')}>
                    {item.icon}
                  </span>
                  {!collapsed && (
                    <span className="hidden min-[1281px]:inline">
                      {item.label}
                    </span>
                  )}
                </NavLink>
              );
            })}
          </div>
        ))}
      </nav>

      {/* Collapse Toggle */}
      <div className="hidden border-t border-border-subtle p-3 min-[1281px]:block">
        <button
          type="button"
          onClick={toggle}
          aria-label={collapsed ? '展开侧边栏' : '收起侧边栏'}
          className="flex w-full items-center justify-center rounded-md p-2 text-text-muted transition-colors hover:bg-surface-2 hover:text-text-secondary"
        >
          <ChevronLeft
            size={16}
            className={cn('transition-transform', collapsed && 'rotate-180')}
          />
        </button>
      </div>
    </aside>
  );
}
