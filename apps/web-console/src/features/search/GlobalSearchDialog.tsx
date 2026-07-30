import * as Dialog from '@radix-ui/react-dialog';
import {
  Boxes,
  FolderKanban,
  LoaderCircle,
  Search,
  Server,
  Tag,
  UserRoundSearch,
  X,
} from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import { isSessionApiError } from '@/api/session';
import { useAuth } from '@/auth/AuthProvider';
import { cn } from '@/shared/lib/utils';
import type { GlobalSearchResult, SearchResourceType } from '@/types/search';
import { useGlobalSearch } from './searchQueries';

const TYPE_OPTIONS: Array<{
  type: SearchResourceType;
  label: string;
}> = [
  { type: 'SESSION', label: '环境' },
  { type: 'PROFILE', label: 'Profile' },
  { type: 'GROUP', label: '分组' },
  { type: 'TAG', label: '标签' },
  { type: 'RUNTIME', label: 'Runtime' },
  { type: 'NODE', label: 'Node' },
];

const TYPE_META: Record<
  SearchResourceType,
  { label: string; eyebrow: string }
> = {
  SESSION: { label: '浏览器环境', eyebrow: 'SESSION' },
  PROFILE: { label: 'Profile', eyebrow: 'PROFILE' },
  GROUP: { label: '工作区分组', eyebrow: 'GROUP' },
  TAG: { label: '工作区标签', eyebrow: 'TAG' },
  RUNTIME: { label: 'Runtime Build', eyebrow: 'RUNTIME' },
  NODE: { label: 'Browser Node', eyebrow: 'NODE' },
};

export function GlobalSearchDialog() {
  const navigate = useNavigate();
  const auth = useAuth();
  const inputRef = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState('');
  const [debouncedQuery, setDebouncedQuery] = useState('');
  const [types, setTypes] = useState<SearchResourceType[]>([]);
  const canReadNodes = auth.hasAnyRole([
    'TENANT_ADMIN',
    'SECURITY_ADMIN',
    'PLATFORM_ADMIN',
  ]);
  const visibleOptions = TYPE_OPTIONS.filter(
    (option) => option.type !== 'NODE' || canReadNodes
  );

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault();
        setOpen((current) => !current);
      }
    };
    window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, []);

  useEffect(() => {
    const timer = window.setTimeout(() => setDebouncedQuery(query), 220);
    return () => window.clearTimeout(timer);
  }, [query]);

  useEffect(() => {
    if (!open) return;
    const frame = window.requestAnimationFrame(() => inputRef.current?.focus());
    return () => window.cancelAnimationFrame(frame);
  }, [open]);

  const search = useGlobalSearch(debouncedQuery, types, open);
  const grouped = useMemo(() => {
    const groups = new Map<SearchResourceType, GlobalSearchResult[]>();
    for (const item of search.data?.items ?? []) {
      groups.set(item.resourceType, [
        ...(groups.get(item.resourceType) ?? []),
        item,
      ]);
    }
    return [...groups.entries()];
  }, [search.data?.items]);

  const selectResult = (result: GlobalSearchResult) => {
    const path =
      result.resourceType === 'SESSION'
        ? `/environments/${encodeURIComponent(result.resourceId)}`
        : result.resourceType === 'PROFILE'
          ? '/profiles'
          : result.resourceType === 'GROUP' || result.resourceType === 'TAG'
            ? '/groups'
            : result.resourceType === 'RUNTIME'
              ? '/runtimes'
              : '/nodes';
    setOpen(false);
    navigate(path);
  };

  const toggleType = (type: SearchResourceType) => {
    setTypes((current) =>
      current.includes(type)
        ? current.filter((candidate) => candidate !== type)
        : [...current, type]
    );
  };

  return (
    <Dialog.Root open={open} onOpenChange={setOpen}>
      <Dialog.Trigger asChild>
        <button
          type="button"
          aria-label="全局搜索"
          title="全局搜索 · ⌘K / Ctrl+K"
          className="hidden h-8 w-8 items-center justify-center rounded-md text-text-muted transition-colors hover:bg-surface-2 hover:text-text-primary md:flex"
        >
          <Search size={16} />
        </button>
      </Dialog.Trigger>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-[#05080d]/78 data-[state=closed]:opacity-0 data-[state=open]:animate-[search-overlay_140ms_ease-out]" />
        <Dialog.Content
          onOpenAutoFocus={(event) => event.preventDefault()}
          className="fixed left-1/2 top-[11vh] z-50 flex max-h-[78vh] w-[calc(100vw-24px)] max-w-[760px] -translate-x-1/2 flex-col overflow-hidden border border-border-default bg-surface-1 shadow-[0_28px_80px_rgba(0,0,0,0.48)] outline-none data-[state=closed]:opacity-0 data-[state=open]:animate-[search-enter_170ms_cubic-bezier(0.16,1,0.3,1)]"
        >
          <Dialog.Title className="sr-only">全局搜索</Dialog.Title>
          <Dialog.Description className="sr-only">
            搜索当前身份有权读取的环境、Profile、分组、标签、Runtime 和 Browser
            Node。
          </Dialog.Description>

          <div className="flex items-center gap-3 border-b border-border-subtle px-4">
            <Search size={17} className="shrink-0 text-accent" />
            <input
              ref={inputRef}
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              maxLength={128}
              placeholder="搜索名称、ID、区域或状态…"
              aria-label="全局搜索关键词"
              className="h-14 min-w-0 flex-1 bg-transparent text-[14px] text-text-primary outline-none placeholder:text-text-muted"
            />
            {search.isFetching && (
              <LoaderCircle
                size={14}
                className="shrink-0 animate-spin text-accent"
                aria-label="正在搜索"
              />
            )}
            <kbd className="hidden border border-border-default bg-surface-2 px-1.5 py-0.5 font-mono text-[9px] text-text-muted sm:block">
              ESC
            </kbd>
            <Dialog.Close asChild>
              <button
                type="button"
                aria-label="关闭全局搜索"
                className="flex h-8 w-8 items-center justify-center text-text-muted hover:text-text-primary"
              >
                <X size={15} />
              </button>
            </Dialog.Close>
          </div>

          <div className="flex min-h-11 items-center gap-1.5 overflow-x-auto border-b border-border-subtle px-4">
            <button
              type="button"
              aria-pressed={types.length === 0}
              onClick={() => setTypes([])}
              className={filterClass(types.length === 0)}
            >
              全部
            </button>
            {visibleOptions.map((option) => (
              <button
                key={option.type}
                type="button"
                aria-pressed={types.includes(option.type)}
                onClick={() => toggleType(option.type)}
                className={filterClass(types.includes(option.type))}
              >
                {option.label}
              </button>
            ))}
            <span className="ml-auto hidden shrink-0 font-mono text-[9px] uppercase tracking-[0.12em] text-text-muted sm:block">
              TENANT SCOPED
            </span>
          </div>

          <div className="min-h-[280px] flex-1 overflow-y-auto bg-canvas/35">
            {query.trim().length < 2 ? (
              <SearchPrompt />
            ) : search.isError ? (
              <SearchError error={search.error} />
            ) : search.isLoading ? (
              <div className="flex min-h-[280px] items-center justify-center gap-2 text-[11px] text-text-muted">
                <LoaderCircle size={14} className="animate-spin text-accent" />
                正在查询权威资源索引
              </div>
            ) : grouped.length === 0 ? (
              <div className="flex min-h-[280px] flex-col items-center justify-center px-6 text-center">
                <UserRoundSearch size={22} className="text-text-muted" />
                <p className="mt-3 text-[12px] font-medium text-text-secondary">
                  没有可访问的匹配资源
                </p>
                <p className="mt-1 max-w-sm text-[10px] leading-5 text-text-muted">
                  尝试完整 Session ID、Profile
                  名称或区域；权限外资源不会出现在结果中。
                </p>
              </div>
            ) : (
              <div className="py-2">
                {grouped.map(([type, items]) => (
                  <section key={type}>
                    <div className="flex items-center gap-2 px-4 pb-1 pt-3">
                      <span className="font-mono text-[8px] uppercase tracking-[0.16em] text-accent">
                        {TYPE_META[type].eyebrow}
                      </span>
                      <span className="text-[10px] text-text-muted">
                        {TYPE_META[type].label}
                      </span>
                      <span className="font-mono text-[9px] text-text-muted">
                        {items.length}
                      </span>
                    </div>
                    {items.map((item) => (
                      <SearchResultRow
                        key={`${item.resourceType}:${item.resourceId}`}
                        result={item}
                        onSelect={() => selectResult(item)}
                      />
                    ))}
                  </section>
                ))}
              </div>
            )}
          </div>

          <div className="flex min-h-10 items-center justify-between gap-4 border-t border-border-subtle px-4 text-[9px] text-text-muted">
            <span>
              {search.data?.truncated
                ? `显示前 ${search.data.limit} 项，请缩小关键词范围`
                : '结果来自正式 Control Plane API'}
            </span>
            <span className="hidden font-mono uppercase tracking-[0.1em] sm:block">
              ↑↓ TAB · ENTER OPEN
            </span>
          </div>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}

function SearchResultRow({
  result,
  onSelect,
}: {
  result: GlobalSearchResult;
  onSelect: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onSelect}
      className="group grid w-full grid-cols-[28px_minmax(0,1fr)_auto] items-center gap-3 border-l-2 border-transparent px-4 py-2.5 text-left hover:border-accent hover:bg-surface-2 focus:border-accent focus:bg-surface-2 focus:outline-none"
    >
      <span className="flex h-7 w-7 items-center justify-center text-text-muted group-hover:text-accent group-focus:text-accent">
        <ResultIcon type={result.resourceType} />
      </span>
      <span className="min-w-0">
        <span className="flex min-w-0 items-baseline gap-2">
          <span className="truncate text-[11px] font-medium text-text-primary">
            {result.title}
          </span>
          <span className="truncate font-mono text-[8px] text-text-muted">
            {result.resourceId}
          </span>
        </span>
        <span className="mt-0.5 block truncate text-[9px] text-text-muted">
          {[result.description, result.region].filter(Boolean).join(' · ') ||
            '无附加描述'}
        </span>
      </span>
      <span className="flex items-center gap-2 pl-3">
        {result.status && (
          <span className="border border-border-subtle px-1.5 py-0.5 font-mono text-[8px] text-text-secondary">
            {result.status}
          </span>
        )}
        <span className="font-mono text-[10px] text-text-muted opacity-0 transition-opacity group-hover:opacity-100 group-focus:opacity-100">
          ↵
        </span>
      </span>
    </button>
  );
}

function ResultIcon({ type }: { type: SearchResourceType }) {
  if (type === 'SESSION') return <Boxes size={14} />;
  if (type === 'PROFILE') return <FolderKanban size={14} />;
  if (type === 'GROUP') return <UserRoundSearch size={14} />;
  if (type === 'TAG') return <Tag size={14} />;
  if (type === 'RUNTIME') return <Server size={14} />;
  return <Server size={14} />;
}

function SearchPrompt() {
  return (
    <div className="grid min-h-[280px] grid-cols-2 content-center gap-px bg-border-subtle sm:grid-cols-3">
      {[
        ['SESSION', '环境名称与 ID'],
        ['PROFILE', 'Profile 与描述'],
        ['GROUP / TAG', '工作区分类'],
        ['RUNTIME', '版本与平台'],
        ['NODE', '区域与运行状态'],
        ['SECURITY', '只返回有权资源'],
      ].map(([code, label]) => (
        <div key={code} className="bg-canvas/95 px-4 py-5">
          <p className="font-mono text-[8px] tracking-[0.14em] text-accent">
            {code}
          </p>
          <p className="mt-1 text-[10px] text-text-muted">{label}</p>
        </div>
      ))}
    </div>
  );
}

function SearchError({ error }: { error: unknown }) {
  const message = isSessionApiError(error)
    ? `${error.body.message} · ${error.body.requestId || 'no request id'}`
    : '全局搜索暂时不可用';
  return (
    <div className="flex min-h-[280px] flex-col items-center justify-center px-6 text-center">
      <Search size={22} className="text-danger" />
      <p className="mt-3 text-[12px] font-medium text-text-secondary">
        无法读取搜索结果
      </p>
      <p className="mt-1 max-w-md font-mono text-[9px] leading-5 text-danger">
        {message}
      </p>
    </div>
  );
}

function filterClass(active: boolean) {
  return cn(
    'shrink-0 border px-2 py-1 text-[9px] transition-colors',
    active
      ? 'border-accent/35 bg-accent-soft text-accent'
      : 'border-transparent text-text-muted hover:border-border-default hover:text-text-secondary'
  );
}
