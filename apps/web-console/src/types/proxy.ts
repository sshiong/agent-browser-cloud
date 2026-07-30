export interface ProxyProviderView {
  providerId: string;
  type: string;
  endpoint: string;
  expectedExitIp: string;
  directFallbackAllowed: boolean;
  state: 'CONFIGURED' | 'UNCONFIGURED';
}

export interface ProxyAllocationView {
  allocationId: string;
  sessionId: string;
  providerId: string;
  protocol: string;
  state: 'ALLOCATED' | 'BOUND' | 'RELEASED' | 'FAILED';
  exitIp: string | null;
  country: string | null;
  asn: string | null;
  allocatedAt: string;
  verifiedAt: string | null;
  releasedAt: string | null;
  updatedAt: string;
}

export interface ProxyOverviewResponse {
  provider: ProxyProviderView;
  allocations: ProxyAllocationView[];
  total: number;
}

export type ProxyBindingHealth =
  'UNVERIFIED' | 'HEALTHY' | 'UNHEALTHY' | 'DISABLED';

export interface ProxyBindingView {
  bindingProfileId: string;
  name: string;
  description: string | null;
  providerId: string;
  region: string | null;
  expectedExitIp: string;
  credentialConfigured: boolean;
  enabled: boolean;
  healthState: ProxyBindingHealth;
  lastVerifiedExitIp: string | null;
  lastHealthCheckedAt: string | null;
  lastFailureReason: string | null;
  version: number;
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface ProxyBindingListResponse {
  items: ProxyBindingView[];
  total: number;
}

export interface ProxyBindingRequest {
  name: string;
  description?: string;
  providerId: string;
  region?: string;
  expectedExitIp: string;
  credentialRef?: string;
  enabled: boolean;
  expectedVersion?: number;
}
