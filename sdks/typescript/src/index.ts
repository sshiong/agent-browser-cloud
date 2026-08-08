import {
  BrowserCloudGeneratedClient,
  type CreateSessionRequest,
  type OperationResponse,
} from './generated/index.js';

export * from './generated/index.js';

export interface BrowserCloudClientOptions {
  baseUrl: string;
  tenantId: string;
  accessToken?: string;
  actorId?: string;
  fetch?: typeof globalThis.fetch;
}

export type CreateSessionInput = Omit<CreateSessionRequest, 'tenantId'> & {
  region: string;
  idempotencyKey?: string;
};

export class BrowserCloudError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly requestId?: string
  ) {
    super(message);
    this.name = 'BrowserCloudError';
  }
}

export class BrowserCloudClient {
  readonly api: BrowserCloudGeneratedClient;
  private readonly baseUrl: string;
  private readonly tenantId: string;
  private readonly accessToken?: string;
  private readonly actorId?: string;
  private readonly fetchImpl: typeof globalThis.fetch;

  constructor(options: BrowserCloudClientOptions) {
    if (!/^https?:\/\//.test(options.baseUrl)) {
      throw new TypeError('baseUrl must be an absolute HTTP(S) URL');
    }
    if (!options.tenantId) throw new TypeError('tenantId is required');
    this.baseUrl = `${options.baseUrl.replace(/\/$/, '')}/api/v1`;
    this.tenantId = options.tenantId;
    this.accessToken = options.accessToken;
    this.actorId = options.actorId;
    this.fetchImpl = options.fetch ?? globalThis.fetch;
    this.api = new BrowserCloudGeneratedClient({
      BASE: options.baseUrl.replace(/\/$/, ''),
      TOKEN: options.accessToken,
      FETCH: this.fetchImpl,
    });
  }

  listSessions(limit = 50, offset = 0) {
    return this.request<Record<string, unknown>>(
      `/sessions?limit=${limit}&offset=${offset}`
    );
  }

  createSession(input: CreateSessionInput) {
    return this.request<{ sessionId: string; context: unknown }>('/sessions', {
      method: 'POST',
      headers: {
        'Idempotency-Key': input.idempotencyKey ?? crypto.randomUUID(),
      },
      body: {
        tenantId: this.tenantId,
        profileId: input.profileId,
        region: input.region,
        runtimeBuildId: input.runtimeBuildId,
        applicationId: input.applicationId,
        groupId: input.groupId,
        tagIds: input.tagIds,
        proxyBindingProfileId: input.proxyBindingProfileId,
        resourcePolicy: input.resourcePolicy ?? { mode: 'AUTO' },
        requestedTabs: input.requestedTabs ?? 1,
        agentActionsPerMinute: input.agentActionsPerMinute ?? 0,
        extensionIds: input.extensionIds ?? [],
        remoteDesktop: input.remoteDesktop ?? false,
        humanTakeoverEnabled: input.humanTakeoverEnabled,
        agentPolicy: input.agentPolicy,
        web3Workload: input.web3Workload ?? false,
        mediaWorkload: input.mediaWorkload ?? false,
        requestedMediaStreams: input.requestedMediaStreams ?? 0,
        mediaBitrateKbps: input.mediaBitrateKbps ?? 0,
        videoRecording: input.videoRecording ?? false,
        metadata: input.metadata ?? {},
      },
    });
  }

  startSession(sessionId: string) {
    return this.request<OperationResponse>(`/sessions/${sessionId}:start`, {
      method: 'POST',
    });
  }

  terminateSession(sessionId: string) {
    return this.request<OperationResponse>(`/sessions/${sessionId}:terminate`, {
      method: 'POST',
    });
  }

  createAgentTask(
    sessionId: string,
    input: {
      goal: string;
      allowedDomains: string[];
      maxReplans?: number;
    }
  ) {
    return this.request<Record<string, unknown>>(
      `/sessions/${sessionId}/agent-tasks`,
      {
        method: 'POST',
        body: {
          goal: input.goal,
          allowedDomains: input.allowedDomains,
          maxReplans: input.maxReplans ?? 2,
        },
      }
    );
  }

  getEnterpriseOverview() {
    return this.request<Record<string, unknown>>('/enterprise/overview');
  }

  explainSessionCost(sessionId: string) {
    return this.request<Record<string, unknown>>(
      `/enterprise/sessions/${sessionId}/cost-explanation`
    );
  }

  private async request<T>(
    path: string,
    options: {
      method?: string;
      headers?: Record<string, string>;
      body?: unknown;
    } = {}
  ): Promise<T> {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      ...(options.headers ?? {}),
    };
    if (this.accessToken) {
      headers.Authorization = `Bearer ${this.accessToken}`;
    } else {
      headers['X-Tenant-Id'] = this.tenantId;
      if (this.actorId) headers['X-Actor-Id'] = this.actorId;
    }
    const response = await this.fetchImpl(this.baseUrl + path, {
      method: options.method ?? 'GET',
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    });
    const payload = (await response.json().catch(() => ({}))) as Record<
      string,
      unknown
    >;
    if (!response.ok) {
      throw new BrowserCloudError(
        response.status,
        typeof payload.code === 'string' ? payload.code : 'UNKNOWN_ERROR',
        typeof payload.message === 'string'
          ? payload.message
          : `HTTP ${response.status}`,
        typeof payload.requestId === 'string' ? payload.requestId : undefined
      );
    }
    return payload as T;
  }
}
