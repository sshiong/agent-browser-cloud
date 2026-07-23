import { NavLink, useLocation, useNavigate } from 'react-router-dom';
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
} from 'lucide-react';
import { useUIStore } from '@/stores/ui';
import { cn } from '@/shared/lib/utils';

interface NavItem {
  label: string;
  icon: React.ReactNode;
  path: string;
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
      { label: '分组与标签', icon: <FolderTree size={18} />, path: '/groups' },
      { label: 'Browser Node', icon: <Boxes size={18} />, path: '/nodes' },
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
      { label: '扩展与应用', icon: <Puzzle size={18} />, path: '/extensions' },
    ],
  },
  {
    title: '自动化',
    items: [
      {
        label: 'Agent 任务',
        icon: <Bot size={18} />,
        path: '/automation/tasks',
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
      },
      { label: '运行日志', icon: <ScrollText size={18} />, path: '/logs' },
      { label: '安全中心', icon: <Shield size={18} />, path: '/security' },
    ],
  },
  {
    title: '系统',
    items: [{ label: '设置', icon: <Settings size={18} />, path: '/settings' }],
  },
];

export function Sidebar() {
  const collapsed = useUIStore((s) => s.sidebarCollapsed);
  const toggle = useUIStore((s) => s.toggleSidebar);
  const location = useLocation();
  const navigate = useNavigate();

  return (
    <aside
      className={cn(
        'flex flex-col border-r border-border-subtle bg-sidebar transition-all duration-200',
        collapsed ? 'w-[72px]' : 'w-[240px]'
      )}
    >
      {/* Logo */}
      <div className="flex h-[56px] items-center gap-3 border-b border-border-subtle px-4">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-accent/15 text-accent">
          <Boxes size={18} />
        </div>
        {!collapsed && (
          <div className="flex flex-col">
            <span className="text-sm font-semibold text-text-primary">
              Agent Browser
            </span>
            <span className="text-[11px] text-text-muted">Runtime Console</span>
          </div>
        )}
      </div>

      {/* New Session Button */}
      <div className="px-3 py-3">
        <button
          type="button"
          onClick={() => navigate('/environments?create=1')}
          aria-label="新建浏览器环境"
          className={cn(
            'flex w-full items-center justify-center gap-2 rounded-[7px] bg-accent px-3 py-2 text-sm font-medium text-canvas transition-colors hover:bg-accent/90',
            collapsed && 'px-0'
          )}
        >
          <Plus size={16} />
          {!collapsed && '新建浏览器环境'}
        </button>
      </div>

      {/* Navigation */}
      <nav className="flex-1 overflow-y-auto px-3 pb-4">
        {navGroups.map((group) => (
          <div key={group.title} className="mb-4">
            {!collapsed && (
              <div className="mb-1 px-2 text-[11px] font-medium uppercase tracking-wider text-text-muted">
                {group.title}
              </div>
            )}
            {group.items.map((item) => {
              const isActive =
                item.path === '/'
                  ? location.pathname === '/'
                  : location.pathname.startsWith(item.path);
              return (
                <NavLink
                  key={item.path}
                  to={item.path}
                  className={cn(
                    'group relative mb-0.5 flex items-center gap-3 rounded-md px-2.5 py-2 text-[13px] transition-colors',
                    isActive
                      ? 'bg-accent-soft text-accent'
                      : 'text-text-secondary hover:bg-surface-2 hover:text-text-primary'
                  )}
                >
                  {isActive && (
                    <div className="absolute left-0 top-1/2 h-4 w-[3px] -translate-y-1/2 rounded-r bg-accent" />
                  )}
                  <span className={cn('shrink-0', isActive && 'text-accent')}>
                    {item.icon}
                  </span>
                  {!collapsed && <span>{item.label}</span>}
                </NavLink>
              );
            })}
          </div>
        ))}
      </nav>

      {/* Collapse Toggle */}
      <div className="border-t border-border-subtle p-3">
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
