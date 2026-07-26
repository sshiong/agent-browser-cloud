export interface BrowserNodeView {
  nodeId: string;
  region: string;
  grpcTarget: string;
  lifecycleState: string;
  admissionState: string;
  certifiedCpuMillis: number;
  certifiedMemoryMib: number;
  certifiedPidCount: number;
  certifiedGpuSlots: number;
  certifiedMediaSlots: number;
  safetyMarginPercent: number;
  reservedCpuMillis: number;
  reservedMemoryMib: number;
  reservedPidCount: number;
  reservedGpuSlots: number;
  reservedMediaSlots: number;
  activeSessions: number;
  maxSessions: number;
  memoryPsiSomeAvg10: number;
  memoryPsiFullAvg10: number;
  cpuPsiSomeAvg10: number;
  ioPsiFullAvg10: number;
  pressureState: 'NORMAL' | 'DEGRADED' | 'CRITICAL';
  pressureReason?: string;
  supportsDesktop: boolean;
  supportsGpu: boolean;
  supportsMedia: boolean;
  supportsNativeOs: boolean;
  isolationCapable: boolean;
  labels: Record<string, string>;
  lastHeartbeatAt: string;
  updatedAt: string;
}

export interface BrowserNodeListResponse {
  items: BrowserNodeView[];
  total: number;
}

export interface ExtensionProfileView {
  extensionId: string;
  displayName: string;
  staticCpuWeight: number;
  staticMemoryWeight: number;
  startupWeight: number;
  pageInjectionWeight: number;
  serviceWorkerWeight: number;
  cryptoWeight: number;
  networkWeight: number;
  observedMultiplier: number;
  confidence: number;
  profileState: 'PROBATION' | 'OBSERVED' | 'CERTIFIED' | 'DISABLED';
  web3: boolean;
  serviceWorker: boolean;
  crypto: boolean;
  privileged: boolean;
  samples: number;
  p95CpuMillis?: number;
  p95MemoryMib?: number;
  lastProfiledAt?: string;
  samplingTier: 'LOW' | 'MEDIUM' | 'HIGH' | 'DEEP';
  samplingCpuBudgetMillis: number;
  nextSampleAt?: string;
  updatedAt: string;
}

export interface ExtensionProfileListResponse {
  items: ExtensionProfileView[];
  total: number;
}
