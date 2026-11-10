import { DEFAULT_TENANT_ID, identityHeaders, SessionApiError } from './session';

export type AgentTool =
  | 'quota'
  | 'list'
  | 'get'
  | 'create'
  | 'start'
  | 'stop'
  | 'delete'
  | 'operation';
export interface AgentConnection {
  connectionId: string;
  name: string;
  subject: string;
  maximumEnvironments: number;
  maximumRunning: number;
  expiresAt: string;
  enabled: boolean;
  allowedTools: AgentTool[];
}
const base = (import.meta.env.VITE_API_BASE_URL?.trim() || '/api/v1').replace(
  /\/$/,
  ''
);
async function request<T>(
  path: string,
  method = 'GET',
  body?: unknown
): Promise<T> {
  const response = await fetch(`${base}/agent-connections${path}`, {
    method,
    headers: {
      ...identityHeaders(DEFAULT_TENANT_ID),
      'Content-Type': 'application/json',
    },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const error = await response
      .json()
      .catch(() => ({ code: 'AGENT_CONNECTION_REQUEST_FAILED' }));
    throw new SessionApiError(response.status, error);
  }
  return response.json() as Promise<T>;
}
export const agentConnectionsApi = {
  list: () => request<AgentConnection[]>(''),
  create: (body: {
    name: string;
    maximumEnvironments: number;
    maximumRunning: number;
    expiresAt: string;
    allowedTools: AgentTool[];
  }) =>
    request<{ connection: AgentConnection; accessToken: string }>(
      '',
      'POST',
      body
    ),
  configure: (
    id: string,
    maximumEnvironments: number,
    maximumRunning: number
  ) =>
    request(`/${encodeURIComponent(id)}`, 'PATCH', {
      maximumEnvironments,
      maximumRunning,
    }),
  revoke: (id: string) => request(`/${encodeURIComponent(id)}:revoke`, 'POST'),
};
