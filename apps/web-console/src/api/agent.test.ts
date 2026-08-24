import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  acceptAgentHandoff,
  approveAgentTask,
  createAgentTask,
  executeAgentTask,
  getAgentTask,
  listAgentTaskSummaries,
  listAgentTasks,
  uploadAgentBrowserFile,
  listAgentBrowserDownloads,
  waitForAgentBrowserDownload,
} from './agent';

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

  it('pages lightweight task summaries with an opaque cursor', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(
        JSON.stringify({
          items: [],
          metrics: { planned: 0, completed: 0, blocked: 0 },
          total: 0,
          limit: 20,
          nextCursor: null,
          hasMore: false,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } }
      )
    );

    await listAgentTaskSummaries(20, 'opaque+cursor', 'tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-task-summaries?limit=20&cursor=opaque%2Bcursor',
      expect.objectContaining({
        headers: expect.objectContaining({ 'X-Tenant-Id': 'tenant-test' }),
      })
    );
  });

  it('loads full task details only for the selected task', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ taskId: 'agt_1234567890abcdef' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await getAgentTask('agt_1234567890abcdef', 'tenant-test');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-tasks/agt_1234567890abcdef',
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

  it('executes a task with a separate idempotency key', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ state: 'COMPLETED' }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await executeAgentTask(
      'agt_1234567890abcdef',
      'idem-execute-1',
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/agent-tasks/agt_1234567890abcdef:execute',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'Idempotency-Key': 'idem-execute-1',
        }),
      })
    );
  });

  it('binds human governance decisions to the configured actor', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async () =>
        new Response(JSON.stringify({ taskId: 'agt_1234567890abcdef' }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
    );

    await approveAgentTask('agt_1234567890abcdef');
    await acceptAgentHandoff('agt_1234567890abcdef');

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/agent-tasks/agt_1234567890abcdef:approve',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Actor-Id': 'user-local' }),
      })
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/agent-tasks/agt_1234567890abcdef:accept-handoff',
      expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({ 'X-Actor-Id': 'user-local' }),
      })
    );
  });

  it('streams Agent Browser files as multipart without overriding the boundary', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
      new Response(JSON.stringify({ uploadId: 'afu_1234567890abcdefghij' }), {
        status: 202,
        headers: { 'Content-Type': 'application/json' },
      })
    );

    await uploadAgentBrowserFile(
      'ses_1234567890abcdef',
      {
        targetRef: 'target:file',
        targetRevision: 4,
        baseStateVersion: 9,
        baseContentHash: 'a'.repeat(64),
        filename: 'evidence.txt',
        mimeType: 'text/plain',
        contentSha256: 'b'.repeat(64),
        file: new Blob(['bounded'], { type: 'text/plain' }),
      },
      'idem-file-1',
      'tenant-test',
      'agent-worker'
    );

    const call = fetchMock.mock.calls[0];
    expect(call).toBeDefined();
    const [, init] = call!;
    expect(init).toEqual(
      expect.objectContaining({
        method: 'POST',
        body: expect.any(FormData),
        headers: expect.objectContaining({
          'X-Tenant-Id': 'tenant-test',
          'X-Actor-Id': 'agent-worker',
          'Idempotency-Key': 'idem-file-1',
        }),
      })
    );
    expect(
      (init?.headers as Record<string, string>)['Content-Type']
    ).toBeUndefined();
    expect((init?.body as FormData).get('targetRef')).toBe('target:file');
    expect((init?.body as FormData).get('filename')).toBe('evidence.txt');
  });

  it('reads and waits on the shared authoritative download projection', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async () =>
        new Response(JSON.stringify({ downloads: [] }), {
          status: 200,
          headers: { 'Content-Type': 'application/json' },
        })
    );

    await listAgentBrowserDownloads('ses_1234567890abcdef', 'tenant-test');
    await waitForAgentBrowserDownload(
      'ses_1234567890abcdef',
      'dld_1234567890abcdefabcd',
      5_000,
      'tenant-test'
    );

    expect(fetchMock).toHaveBeenNthCalledWith(
      1,
      '/api/v1/sessions/ses_1234567890abcdef/agent-browser/files/downloads',
      expect.any(Object)
    );
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      '/api/v1/sessions/ses_1234567890abcdef/agent-browser/files/downloads/dld_1234567890abcdefabcd:wait?timeoutMs=5000',
      expect.any(Object)
    );
  });
});
