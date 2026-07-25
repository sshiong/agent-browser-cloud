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
