import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import {
  createSession,
  captureSessionEvidence,
  createSessionEvidenceAccessGrant,
  getBrowserState,
  getSessionResourceEvents,
  getSessionEvidence,
  getSessionRecordings,
  getSessionEvidenceCapture,
  getSessionResources,
  getSessionSafePoint,
  getSessionMigration,
  getSessionProxyRebind,
  getSession,
  listSessions,
  releaseHumanTakeover,
  redeemSessionEvidenceAccessGrant,
  requestHumanTakeover,
  resyncBrowserState,
  startSession,
  terminateSession,
  updateSessionResourcePolicy,
  streamSessionChanges,
  listRecoveryContracts,
  upsertRecoveryContract,
  requestRecoveryContractApproval,
  decideRecoveryContractApproval,
  listRecoveryContractRevisions,
  getRecoveryContractDiff,
  restoreRecoveryContractRevision,
  getBusinessRecovery,
  getBusinessRecoveryProviderEvidence,
  validateBusinessRecovery,
  getSessionApplicationBinding,
  rebindSessionApplication,
  rebindSessionProxy,
  getSessionChallenges,
  getChallengePreview,
  authorizeHumanAssist,
  getRemoteDesktopParticipants,
  getRemoteDesktopParticipantHistory,
  revokeRemoteDesktopParticipant,
  getChallengeAutomationPolicy,
  updateChallengeAutomationPolicy,
  getCurrentChallengeAutomationRun,
  createAgentInputSecret,
  submitChallengeInputResponse,
} from '@/api/session';
import type {
  CreateSessionRequest,
  ResourceStreamConnectionState,
  SessionState,
  StateResyncRequest,
  ResourcePolicyRequest,
  UpsertRecoveryContractRequest,
  RequestRecoveryContractApprovalRequest,
  RebindSessionApplicationRequest,
  RestoreRecoveryContractRevisionRequest,
  EvidencePurpose,
  AuthorizeHumanAssistRequest,
  UpdateChallengeAutomationPolicyRequest,
} from '@/types/session';
import type { ProxyRebindRequest } from '@/types/proxy';

export const sessionKeys = {
  all: ['sessions'] as const,
  list: (params: {
    state?: SessionState;
    query?: string;
    groupId?: string;
    tagIds?: string[];
    tagMatch?: 'ANY' | 'ALL';
    limit: number;
    offset: number;
  }) => [...sessionKeys.all, 'list', params] as const,
  detail: (sessionId: string) =>
    [...sessionKeys.all, 'detail', sessionId] as const,
  browserState: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'browser-state'] as const,
  resources: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'resources'] as const,
  resourceEvents: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'resource-events'] as const,
  evidence: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'evidence'] as const,
  recordings: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'recordings'] as const,
  evidenceCaptures: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'evidence-captures'] as const,
  evidenceCapture: (sessionId: string, captureId: string) =>
    [...sessionKeys.evidenceCaptures(sessionId), captureId] as const,
  safePoint: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'safe-point'] as const,
  migration: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'migration'] as const,
  proxyRebind: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'proxy-rebind'] as const,
  businessRecovery: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'business-recovery'] as const,
  providerEvidence: (sessionId: string) =>
    [...sessionKeys.businessRecovery(sessionId), 'provider-evidence'] as const,
  applicationBinding: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'application-binding'] as const,
  challenges: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'challenges'] as const,
  challengeAutomation: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'challenge-automation'] as const,
  desktopParticipants: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'desktop-participants'] as const,
  desktopParticipantHistory: (sessionId: string) =>
    [...sessionKeys.detail(sessionId), 'desktop-participant-history'] as const,
  challengePreview: (sessionId: string, eventId: string) =>
    [...sessionKeys.challenges(sessionId), eventId, 'preview'] as const,
  recoveryContracts: ['application-recovery-contracts'] as const,
  recoveryContractRevisions: (applicationId: string) =>
    [...sessionKeys.recoveryContracts, applicationId, 'revisions'] as const,
  recoveryContractDiff: (
    applicationId: string,
    fromVersion: number,
    toVersion: number
  ) =>
    [
      ...sessionKeys.recoveryContractRevisions(applicationId),
      'diff',
      fromVersion,
      toVersion,
    ] as const,
};

export function useSessions(params: {
  state?: SessionState;
  query?: string;
  groupId?: string;
  tagIds?: string[];
  tagMatch?: 'ANY' | 'ALL';
  limit?: number;
  offset?: number;
}) {
  const limit = params.limit ?? 20;
  const offset = params.offset ?? 0;
  return useQuery({
    queryKey: sessionKeys.list({
      state: params.state,
      query: params.query,
      groupId: params.groupId,
      tagIds: params.tagIds,
      tagMatch: params.tagMatch,
      limit,
      offset,
    }),
    queryFn: ({ signal }) =>
      listSessions({
        state: params.state,
        query: params.query,
        groupId: params.groupId,
        tagIds: params.tagIds,
        tagMatch: params.tagMatch,
        limit,
        offset,
        signal,
      }),
  });
}

export function useSession(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.detail(sessionId),
    queryFn: ({ signal }) => getSession(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useBrowserState(sessionId: string, enabled: boolean) {
  return useQuery({
    queryKey: sessionKeys.browserState(sessionId),
    queryFn: ({ signal }) => getBrowserState(sessionId, undefined, signal),
    enabled: Boolean(sessionId) && enabled,
  });
}

export function useSessionChallenges(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.challenges(sessionId),
    queryFn: ({ signal }) => getSessionChallenges(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useChallengeAutomation(sessionId: string) {
  const policy = useQuery({
    queryKey: [...sessionKeys.challengeAutomation(sessionId), 'policy'],
    queryFn: ({ signal }) =>
      getChallengeAutomationPolicy(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
  const run = useQuery({
    queryKey: [...sessionKeys.challengeAutomation(sessionId), 'current'],
    queryFn: ({ signal }) =>
      getCurrentChallengeAutomationRun(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
  return { policy, run };
}

export function useUpdateChallengeAutomationPolicy(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: UpdateChallengeAutomationPolicyRequest) =>
      updateChallengeAutomationPolicy(sessionId, request),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.challengeAutomation(sessionId),
      });
    },
  });
}

export function useChallengePreview(
  sessionId: string,
  eventId: string | undefined
) {
  return useQuery({
    queryKey: sessionKeys.challengePreview(sessionId, eventId ?? ''),
    queryFn: ({ signal }) =>
      getChallengePreview(eventId ?? '', undefined, undefined, signal),
    enabled: Boolean(sessionId && eventId),
    staleTime: 0,
  });
}

export function useAuthorizeHumanAssist(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      eventId,
      request,
    }: {
      eventId: string;
      request: AuthorizeHumanAssistRequest;
    }) =>
      authorizeHumanAssist(
        eventId,
        request,
        `human-assist-${crypto.randomUUID()}`
      ),
    onSuccess: async (_, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challenges(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challengePreview(sessionId, variables.eventId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
      ]);
    },
  });
}

export function useSubmitChallengeOtp(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({
      eventId,
      value,
    }: {
      eventId: string;
      value: string;
    }) => {
      const requestId = crypto.randomUUID();
      const secret = await createAgentInputSecret(
        sessionId,
        { purpose: 'OTP', value },
        `challenge-otp-secret-${requestId}`
      );
      return submitChallengeInputResponse(
        eventId,
        { secretId: secret.secretId },
        `challenge-otp-response-${requestId}`
      );
    },
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challenges(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
      ]);
    },
  });
}

export function useSessionResources(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.resources(sessionId),
    queryFn: ({ signal }) => getSessionResources(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionResourceEvents(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.resourceEvents(sessionId),
    queryFn: ({ signal }) =>
      getSessionResourceEvents(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionEvidence(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.evidence(sessionId),
    queryFn: ({ signal }) => getSessionEvidence(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionRecordings(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.recordings(sessionId),
    queryFn: ({ signal }) => getSessionRecordings(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useEvidenceCapture(sessionId: string, captureId?: string) {
  return useQuery({
    queryKey: sessionKeys.evidenceCapture(sessionId, captureId ?? ''),
    queryFn: ({ signal }) =>
      getSessionEvidenceCapture(sessionId, captureId ?? '', undefined, signal),
    enabled: Boolean(sessionId && captureId),
    refetchInterval: (query) =>
      query.state.data?.state === 'EXECUTING' ? 2_000 : false,
  });
}

export function useCaptureSessionEvidence(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (purpose: EvidencePurpose) =>
      captureSessionEvidence(
        sessionId,
        purpose,
        `observer-capture-${crypto.randomUUID()}`
      ),
    onSuccess: async (capture) => {
      queryClient.setQueryData(
        sessionKeys.evidenceCapture(sessionId, capture.captureId),
        capture
      );
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.evidence(sessionId),
      });
    },
  });
}

export function useCreateEvidenceAccessGrant(sessionId: string) {
  return useMutation({
    mutationFn: ({
      evidenceId,
      purpose,
    }: {
      evidenceId: string;
      purpose: EvidencePurpose;
    }) =>
      createSessionEvidenceAccessGrant(
        sessionId,
        evidenceId,
        purpose,
        `evidence-access-${crypto.randomUUID()}`
      ),
  });
}

export function useRedeemEvidenceAccessGrant(sessionId: string) {
  return useMutation({
    mutationFn: (grantId: string) =>
      redeemSessionEvidenceAccessGrant(sessionId, grantId),
  });
}

export function useSessionSafePoint(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.safePoint(sessionId),
    queryFn: ({ signal }) => getSessionSafePoint(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
    refetchInterval: (query) => {
      const expirations =
        query.state.data?.blockers
          .map((blocker) => blocker.expiresAt)
          .filter((value): value is string => Boolean(value))
          .map((value) => Date.parse(value))
          .filter(Number.isFinite) ?? [];
      if (!expirations.length) return false;
      return Math.max(250, Math.min(...expirations) - Date.now() + 100);
    },
  });
}

export function useSessionMigration(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.migration(sessionId),
    queryFn: ({ signal }) => getSessionMigration(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useSessionProxyRebind(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.proxyRebind(sessionId),
    queryFn: ({ signal }) =>
      getSessionProxyRebind(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useRebindSessionProxy(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: ProxyRebindRequest) =>
      rebindSessionProxy(
        sessionId,
        body,
        `proxy-rebind-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.proxyRebind(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.safePoint(sessionId),
        }),
      ]);
    },
  });
}

export function useRecoveryContracts() {
  return useQuery({
    queryKey: sessionKeys.recoveryContracts,
    queryFn: ({ signal }) => listRecoveryContracts(undefined, signal),
  });
}

export function useUpsertRecoveryContract() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      applicationId,
      body,
    }: {
      applicationId: string;
      body: UpsertRecoveryContractRequest;
    }) => upsertRecoveryContract(applicationId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.recoveryContracts,
      });
    },
  });
}

export function useRecoveryContractRevisions(applicationId?: string) {
  return useQuery({
    queryKey: sessionKeys.recoveryContractRevisions(applicationId ?? ''),
    queryFn: ({ signal }) =>
      listRecoveryContractRevisions(applicationId ?? '', undefined, signal),
    enabled: Boolean(applicationId),
  });
}

export function useRecoveryContractDiff(
  applicationId: string | undefined,
  fromVersion: number | undefined,
  toVersion: number | undefined
) {
  return useQuery({
    queryKey: sessionKeys.recoveryContractDiff(
      applicationId ?? '',
      fromVersion ?? 0,
      toVersion ?? 0
    ),
    queryFn: ({ signal }) =>
      getRecoveryContractDiff(
        applicationId ?? '',
        fromVersion ?? 0,
        toVersion ?? 0,
        undefined,
        signal
      ),
    enabled:
      Boolean(applicationId) &&
      Boolean(fromVersion) &&
      Boolean(toVersion) &&
      fromVersion !== toVersion,
  });
}

export function useRestoreRecoveryContractRevision() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      applicationId,
      body,
    }: {
      applicationId: string;
      body: RestoreRecoveryContractRevisionRequest;
    }) =>
      restoreRecoveryContractRevision(
        applicationId,
        body,
        `recovery-contract-restore-${crypto.randomUUID()}`
      ),
    onSuccess: async (_data, variables) => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.recoveryContracts,
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.recoveryContractRevisions(
            variables.applicationId
          ),
        }),
      ]);
    },
  });
}

export function useRequestRecoveryContractApproval() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      applicationId,
      body,
    }: {
      applicationId: string;
      body: RequestRecoveryContractApprovalRequest;
    }) => requestRecoveryContractApproval(applicationId, body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.recoveryContracts,
      });
    },
  });
}

export function useDecideRecoveryContractApproval() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      applicationId,
      approvalId,
      decision,
    }: {
      applicationId: string;
      approvalId: string;
      decision: 'approve' | 'reject';
    }) => decideRecoveryContractApproval(applicationId, approvalId, decision),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.recoveryContracts,
      });
    },
  });
}

export function useBusinessRecovery(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.businessRecovery(sessionId),
    queryFn: ({ signal }) => getBusinessRecovery(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useBusinessRecoveryProviderEvidence(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.providerEvidence(sessionId),
    queryFn: ({ signal }) =>
      getBusinessRecoveryProviderEvidence(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useValidateBusinessRecovery(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () =>
      validateBusinessRecovery(
        sessionId,
        `business-recovery-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.businessRecovery(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.migration(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.proxyRebind(sessionId),
        }),
      ]);
    },
  });
}

export function useSessionApplicationBinding(sessionId: string) {
  return useQuery({
    queryKey: sessionKeys.applicationBinding(sessionId),
    queryFn: ({ signal }) =>
      getSessionApplicationBinding(sessionId, undefined, signal),
    enabled: Boolean(sessionId),
  });
}

export function useRebindSessionApplication(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: RebindSessionApplicationRequest) =>
      rebindSessionApplication(
        sessionId,
        body,
        `application-binding-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.applicationBinding(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challenges(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challengeAutomation(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.challenges(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.businessRecovery(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
      ]);
    },
  });
}

export function useSessionResourceStream(
  sessionId: string,
  enabled: boolean
): ResourceStreamConnectionState {
  const queryClient = useQueryClient();
  const [connectionState, setConnectionState] =
    useState<ResourceStreamConnectionState>(enabled ? 'CONNECTING' : 'IDLE');

  useEffect(() => {
    if (!enabled || !sessionId) {
      setConnectionState('IDLE');
      return;
    }
    const controller = new AbortController();
    let lastEventId: string | undefined;
    let reconnectAttempt = 0;

    const invalidateAllSessionViews = () =>
      Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.browserState(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resourceEvents(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.safePoint(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.migration(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.proxyRebind(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.businessRecovery(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.evidence(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.evidenceCaptures(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.applicationBinding(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.desktopParticipants(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.desktopParticipantHistory(sessionId),
        }),
      ]);

    const run = async () => {
      while (!controller.signal.aborted) {
        if (!navigator.onLine) {
          setConnectionState('OFFLINE');
          if (!(await waitForReconnect(2_000, controller.signal))) return;
          continue;
        }
        setConnectionState(
          reconnectAttempt === 0 ? 'CONNECTING' : 'RECONNECTING'
        );
        try {
          await streamSessionChanges({
            sessionId,
            lastEventId,
            signal: controller.signal,
            onOpen: () => {
              setConnectionState(
                reconnectAttempt === 0 ? 'CONNECTING' : 'RECONNECTING'
              );
            },
            onControl: (control) => {
              reconnectAttempt = 0;
              lastEventId = String(control.cursor);
              setConnectionState('LIVE');
              void invalidateAllSessionViews();
            },
            onChange: (change) => {
              lastEventId = String(change.sequence);
              if (change.changeType === 'BROWSER_STATE') {
                void queryClient.invalidateQueries({
                  queryKey: sessionKeys.browserState(sessionId),
                });
              } else if (change.changeType === 'SESSION') {
                void Promise.all([
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.detail(sessionId),
                  }),
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.all,
                  }),
                ]);
              } else if (change.changeType === 'RESOURCE_SAMPLE') {
                void Promise.all([
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.resources(sessionId),
                  }),
                  queryClient.invalidateQueries({
                    queryKey: sessionKeys.safePoint(sessionId),
                  }),
                ]);
              } else {
                void invalidateAllSessionViews();
              }
            },
          });
        } catch {
          if (controller.signal.aborted) return;
        }
        reconnectAttempt += 1;
        setConnectionState(navigator.onLine ? 'RECONNECTING' : 'OFFLINE');
        const backoff =
          Math.min(30_000, 1_000 * 2 ** Math.min(reconnectAttempt - 1, 5)) +
          Math.round(Math.random() * 500);
        if (!(await waitForReconnect(backoff, controller.signal))) return;
      }
    };

    const markOffline = () => setConnectionState('OFFLINE');
    const markOnline = () => setConnectionState('RECONNECTING');
    window.addEventListener('offline', markOffline);
    window.addEventListener('online', markOnline);
    void run();
    return () => {
      controller.abort();
      window.removeEventListener('offline', markOffline);
      window.removeEventListener('online', markOnline);
    };
  }, [enabled, queryClient, sessionId]);

  return connectionState;
}

export function useRemoteDesktopParticipants(
  sessionId: string,
  enabled: boolean
) {
  return useQuery({
    queryKey: sessionKeys.desktopParticipants(sessionId),
    queryFn: ({ signal }) =>
      getRemoteDesktopParticipants(sessionId, undefined, signal),
    enabled: enabled && Boolean(sessionId),
  });
}

export function useRemoteDesktopParticipantHistory(
  sessionId: string,
  enabled: boolean
) {
  return useInfiniteQuery({
    queryKey: sessionKeys.desktopParticipantHistory(sessionId),
    queryFn: ({ pageParam, signal }) =>
      getRemoteDesktopParticipantHistory(
        sessionId,
        20,
        pageParam || undefined,
        undefined,
        signal
      ),
    initialPageParam: '',
    getNextPageParam: (page) =>
      page.hasMore ? page.nextCursor || undefined : undefined,
    enabled: enabled && Boolean(sessionId),
  });
}

export function useRevokeRemoteDesktopParticipant(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (connectionId: string) =>
      revokeRemoteDesktopParticipant(
        sessionId,
        connectionId,
        `desktop-revoke-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: sessionKeys.desktopParticipants(sessionId),
      });
    },
  });
}

async function waitForReconnect(milliseconds: number, signal: AbortSignal) {
  if (signal.aborted) return false;
  return new Promise<boolean>((resolve) => {
    const timeout = window.setTimeout(() => {
      signal.removeEventListener('abort', cancel);
      resolve(true);
    }, milliseconds);
    const cancel = () => {
      window.clearTimeout(timeout);
      resolve(false);
    };
    signal.addEventListener('abort', cancel, { once: true });
  });
}

export function useUpdateResourcePolicy(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (policy: ResourcePolicyRequest) =>
      updateSessionResourcePolicy(
        sessionId,
        policy,
        `resource-policy-${crypto.randomUUID()}`
      ),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resourceEvents(sessionId),
        }),
      ]);
    },
  });
}

export function useCreateSession() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      request,
      idempotencyKey,
    }: {
      request: CreateSessionRequest;
      idempotencyKey: string;
    }) => createSession(request, idempotencyKey),
    onSuccess: () =>
      queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
  });
}

export function useStartSession(sessionId: string) {
  return useSessionOperation(sessionId, () => startSession(sessionId));
}

export function useTerminateSession(sessionId: string) {
  return useSessionOperation(sessionId, () => terminateSession(sessionId));
}

export function useRequestHumanTakeover(sessionId: string) {
  return useSessionOperation(sessionId, () => requestHumanTakeover(sessionId));
}

export function useReleaseHumanTakeover(sessionId: string) {
  return useSessionOperation(sessionId, () => releaseHumanTakeover(sessionId));
}

export function useResyncBrowserState(sessionId: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: StateResyncRequest) =>
      resyncBrowserState(
        sessionId,
        request,
        `state-resync-${crypto.randomUUID()}`
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: sessionKeys.browserState(sessionId),
      }),
  });
}

function useSessionOperation(
  sessionId: string,
  operation: () => Promise<unknown>
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: operation,
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: sessionKeys.detail(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.browserState(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.resources(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.safePoint(sessionId),
        }),
        queryClient.invalidateQueries({
          queryKey: sessionKeys.migration(sessionId),
        }),
        queryClient.invalidateQueries({ queryKey: sessionKeys.all }),
      ]);
    },
  });
}
