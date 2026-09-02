import { beforeEach, describe, expect, it, vi } from 'vitest';
import { renderToStaticMarkup } from 'react-dom/server';
import { SessionLifecycleActions } from './SessionLifecycleActions';
import type { SessionView } from '@/types/session';

const mocks = vi.hoisted(() => ({ canOperate: true, pending: false }));
vi.mock('@/auth/AuthProvider', () => ({
  useAuth: () => ({ canOperate: mocks.canOperate }),
}));
vi.mock('../api/sessionQueries', () => ({
  useStartSession: () => ({
    isPending: mocks.pending,
    reset: vi.fn(),
    mutate: vi.fn(),
  }),
  useTerminateSession: () => ({
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
  it('does not pretend a terminated session can restart', () => {
    expect(render({ state: 'TERMINATED' })).toContain(
      '已终止的会话不能再次启动'
    );
    expect(render({ state: 'TERMINATED' })).toContain('disabled=""');
  });
  it('hides writes from viewers', () => {
    mocks.canOperate = false;
    expect(render()).toBe('');
  });
});
