package io.browsercloud.coordinator;

/** PostgreSQL-authoritative route lookup used before every Coordinator command. */
public interface CoordinatorRouteAuthority {

  SessionRoute resolve(String sessionId);

  record SessionRoute(
      String sessionId, String tenantId, long routeEpoch, int virtualPartition, int shardId) {}
}
