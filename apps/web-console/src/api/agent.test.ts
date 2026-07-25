import { afterEach, describe, expect, it, vi } from 'vitest';
import { createAgentTask, listAgentTasks } from './agent';

afterEach(() => vi.restoreAllMocks());

describe('agent API', () => {
  it('lists tenant-scoped tasks', async () => {
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockResolvedValue(
        new Response(
          JSON.stringify({ items: [], total: 0, limit: 100, offset: 0 }),
          { status: 200, headers: { 'Content-Type': 'application/json' } }
        )
      );

    await listAgentTasks('tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-tasks?limit=100&offset=0',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
  });

  it('creates an idempotent session-bound safety plan', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ taskId: 'agt_1234567890abcdef' }), {
        status: 201,
        headers: { 'Content-Type': 'application/json' },
      })
    );
    const request = {
      goal: 'Summarize the page',
      startUrl: 'https://example.com',
      allowedDomains: ['example.com'],
    };

    await createAgentTask(
      'ses_1234567890abcdef',
      request,
      'idem-agent-1',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/sessions/ses_1234567890abcdef/agent-tasks',
      expect.objectContaining({
        method: 'POST',
        body: JSON.stringify(request),
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'idem-agent-1',
        }),
      })
    );
  });
});
