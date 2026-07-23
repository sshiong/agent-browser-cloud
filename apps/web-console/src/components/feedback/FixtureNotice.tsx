import { FlaskConical, Wrench } from 'lucide-react';

const fixturesEnabled =
  import.meta.env.DEV || import.meta.env.VITE_ENABLE_FIXTURES === 'true';

export function FixtureBoundary({ children }: { children: React.ReactNode }) {
  if (!fixturesEnabled) {
    return (
      <div className="m-6 flex min-h-72 flex-col items-center justify-center rounded-[10px] border border-dashed border-border-default bg-surface-1 px-6 text-center">
        <Wrench size={20} className="text-text-muted" />
        <h2 className="mt-3 text-[14px] font-semibold text-text-primary">
          后端接口尚未接入
        </h2>
        <p className="mt-1 max-w-md text-[11px] text-text-muted">
          生产构建已关闭开发 Fixture。完成正式
          API、权限和错误契约后再开放该模块。
        </p>
      </div>
    );
  }

  return (
    <>
      <div className="mx-6 mt-5 flex items-center gap-2 rounded-[8px] border border-warning/25 bg-warning/8 px-3 py-2 text-[11px] text-warning">
        <FlaskConical size={13} />
        <span>
          当前模块展示开发
          Fixture；后端正式接口尚未实现，页面不会把这些数据当作真实状态。
        </span>
      </div>
      {children}
    </>
  );
}
