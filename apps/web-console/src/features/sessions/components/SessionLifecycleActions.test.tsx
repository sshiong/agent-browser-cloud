import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SessionLifecycleActions } from './SessionLifecycleActions';
import type { SessionView } from '@/types/session';
import { SessionApiError } from '@/api/session';
import { ApiSessionStateChip } from './ApiSessionStateChip';

const mocks = vi.hoisted(() => ({
  canOperate: true,
  pending: false,
  success: false,
  error: null as Error | null,
}));
vi.mock('@/auth/AuthProvider', () => ({
  useAuth: () => ({ canOperate: mocks.canOperate }),
}));
vi.mock('../api/sessionQueries', () => ({
  useStartSession: () => ({
    error: mocks.error,
    isPending: mocks.pending,
    isSuccess: mocks.success,
    reset: vi.fn(),
    mutate: vi.fn(),
  }),
  useStopSession: () => ({
    isPending: false,
    reset: vi.fn(),
    mutate: vi.fn(),
  }),
}));

const fixture: SessionView = {
  sessionId: 'ses-ui-test',
  displayName: 'UI Test',
  state: 'CREATED',
  tenantId: 'tenant-ui-test',
  profileId: 'profile-ui-test',
  region: 'local',
  resourceTemplate: 'standard-v1',
  contextEpoch: 0,
  browserGeneration: 0,
  createdAt: '2026-09-02T00:00:00Z',
  updatedAt: '2026-09-02T00:00:00Z',
};
const render = (changes: Partial<SessionView> = {}) =>
  renderToStaticMarkup(
    <SessionLifecycleActions session={{ ...fixture, ...changes }} />
  );

describe('shared session lifecycle controls', () => {
  beforeEach(() => {
    mocks.canOperate = true;
    mocks.pending = false;
    mocks.success = false;
    mocks.error = null;
  });
  it.each(['CREATED', 'HIBERNATED'] as const)(
    'offers start for %s',
    (state) => {
      const html = render({ state });
      expect(html).toContain('启动 ses-ui-test');
      expect(html).not.toContain('disabled=""');
    }
  );
  it('offers the red square stop action for running sessions', () => {
    const html = render({ state: 'RUNNING' });
    expect(html).toContain('停止 ses-ui-test');
    expect(html).toContain('text-danger');
    expect(html).toContain('lucide-square');
    expect(html).not.toContain('是否停止运行');
  });
  it('allows a confirmed stop to interrupt an active operation', () => {
    expect(
      render({
        state: 'RUNNING',
        currentOperation: {
          operationId: 'op-active',
        } as SessionView['currentOperation'],
      })
    ).not.toContain('disabled=""');
  });
  it('prevents duplicate submission while a request is pending', () => {
    mocks.pending = true;
    expect(render()).toContain('disabled=""');
  });
  it('allows retained legacy stopped environments to restart', () => {
    expect(render({ state: 'TERMINATED' })).toContain('启动 ses-ui-test');
    expect(render({ state: 'TERMINATED' })).not.toContain('disabled=""');
  });
  it('hides writes from viewers', () => {
    mocks.canOperate = false;
    expect(render()).toBe('');
  });
  it('announces accepted requests without resizing the action column', () => {
    mocks.success = true;
    const html = render({ state: 'RUNNING' });
    expect(html).toContain('role="status" class="sr-only"');
    expect(html).toContain('w-8 shrink-0');
  });
  it('keeps long errors out of table sizing and exposes the actual reason', () => {
    mocks.error = new SessionApiError(503, {
      code: 'COORDINATOR_COMMAND_UNAVAILABLE',
      message: 'The Session command could not be committed',
      details: { reason: 'RESOURCE_DEMAND_MISSING' },
      requestId: 'legacy-request-id',
    });
    const html = render();
    expect(html).toContain('<details');
    expect(html).toContain('absolute right-0');
    expect(html).toContain('环境初始化数据缺失');
    expect(html).toContain('legacy-request-id');
    expect(
      renderToStaticMarkup(<ApiSessionStateChip state="TERMINATED" />)
    ).toContain('whitespace-nowrap');
  });
});
