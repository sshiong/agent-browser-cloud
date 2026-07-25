package io.browsercloud.domain.session;

import java.time.Instant;

/**
 * Session 会话上下文。
 *
 * <p>表示当前会话的稳定运行上下文，包含版本控制信息。 所有状态转换必须通过 Session Coordinator 串行处理。
 */
public record SessionContext(
    String sessionId,
    String tenantId,
    String profileId,
    String nodeId,
    String runtimeBuildId,
    String isolationProfileId,
    String proxyBindingId,
    long coordinatorTerm,
    long contextEpoch,
    long browserGeneration,
    long networkRevision,
    ResourceClass resourceClass,
    SessionState state,
    String policyHash,
    Instant createdAt,
    Instant updatedAt) {
  /**
   * 递增 context_epoch，表示核心运行环境发生变化。
   *
   * <p>触发条件：Browser 重启、Node 迁移、Runtime Build 变化、Profile 恢复等。
   *
   * @param newNodeId 新的 Node ID
   * @param newRuntimeBuildId 新的 Runtime Build ID
   * @param newBrowserGeneration 新的 Browser Generation
   * @return 新的 SessionContext
   */
  public SessionContext nextContextEpoch(
      String newNodeId, String newRuntimeBuildId, long newBrowserGeneration) {
    return new SessionContext(
        sessionId,
        tenantId,
        profileId,
        newNodeId,
        newRuntimeBuildId,
        isolationProfileId,
        proxyBindingId,
        coordinatorTerm,
        contextEpoch + 1,
        newBrowserGeneration,
        networkRevision,
        resourceClass,
        state,
        policyHash,
        createdAt,
        Instant.now());
  }

  /**
   * 递增 network_revision，表示轻量网络变化。
   *
   * <p>触发条件：同城市 Proxy IP 漂移、同 ASN 出口变化、DNS Resolver 切换等。
   *
   * @return 新的 SessionContext
   */
  public SessionContext nextNetworkRevision() {
    return new SessionContext(
        sessionId,
        tenantId,
        profileId,
        nodeId,
        runtimeBuildId,
        isolationProfileId,
        proxyBindingId,
        coordinatorTerm,
        contextEpoch,
        browserGeneration,
        networkRevision + 1,
        resourceClass,
        state,
        policyHash,
        createdAt,
        Instant.now());
  }

  /** 绑定新的网络出口；Proxy 身份属于核心运行上下文。 */
  public SessionContext withProxyBinding(String newProxyBindingId) {
    return new SessionContext(
        sessionId,
        tenantId,
        profileId,
        nodeId,
        runtimeBuildId,
        isolationProfileId,
        newProxyBindingId,
        coordinatorTerm,
        contextEpoch + 1,
        browserGeneration,
        networkRevision + 1,
        resourceClass,
        state,
        policyHash,
        createdAt,
        Instant.now());
  }

  /**
   * 更新状态。
   *
   * @param newState 新状态
   * @return 新的 SessionContext
   */
  public SessionContext withState(SessionState newState) {
    return new SessionContext(
        sessionId,
        tenantId,
        profileId,
        nodeId,
        runtimeBuildId,
        isolationProfileId,
        proxyBindingId,
        coordinatorTerm,
        contextEpoch,
        browserGeneration,
        networkRevision,
        resourceClass,
        newState,
        policyHash,
        createdAt,
        Instant.now());
  }
}
