package io.browsercloud.coordinator;

import io.browsercloud.coordinator.exceptions.CoordinatorNotOwnerException;
import io.browsercloud.coordinator.exceptions.StaleCoordinatorTermException;
import io.browsercloud.persistence.CoordinatorOwnershipEntity;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coordinator 所有权服务。
 *
 * <p>负责管理 Coordinator 的所有权，防止双主。
 */
@Service
public class CoordinatorOwnershipService {

  private final CoordinatorOwnershipJpaRepository ownershipJpa;
  private final String coordinatorId;
  private final Duration leaseDuration;

  public CoordinatorOwnershipService(
      CoordinatorOwnershipJpaRepository ownershipJpa,
      @Value("${coordinator.instance-id:${random.uuid}}") String coordinatorId,
      @Value("${coordinator.lease-seconds:30}") long leaseSeconds) {
    this.ownershipJpa = ownershipJpa;
    this.coordinatorId = coordinatorId;
    this.leaseDuration = Duration.ofSeconds(Math.max(1, leaseSeconds));
  }

  /**
   * 尝试接管 Session 的 Coordinator 所有权。
   *
   * @param sessionId Session ID
   * @return 当前实例持有的 Coordinator Term
   */
  @Transactional
  public long acquireSession(String sessionId, long routeEpoch) {
    var now = Instant.now();
    var existing = ownershipJpa.findById(sessionId);
    if (existing
        .filter(ownership -> ownership.getCoordinatorOwner().equals(coordinatorId))
        .filter(ownership -> ownership.getRouteEpoch() == routeEpoch)
        .isPresent()) {
      if (ownershipJpa.heartbeatIfOwner(sessionId, coordinatorId, routeEpoch, now) == 1) {
        return existing.orElseThrow().getCoordinatorTerm();
      }
    }

    ownershipJpa.claimIfAbsentOrExpired(
        sessionId, coordinatorId, routeEpoch, now, now.minus(leaseDuration));
    return ownershipJpa
        .findById(sessionId)
        .filter(ownership -> ownership.getCoordinatorOwner().equals(coordinatorId))
        .filter(ownership -> ownership.getRouteEpoch() == routeEpoch)
        .map(CoordinatorOwnershipEntity::getCoordinatorTerm)
        .orElseThrow(() -> new CoordinatorNotOwnerException(sessionId));
  }

  /** 仅允许当前实例确认并续租给定 Term。 */
  @Transactional
  public void assertCurrentOwner(String sessionId, long coordinatorTerm, long routeEpoch) {
    var current =
        ownershipJpa
            .findById(sessionId)
            .filter(ownership -> ownership.getCoordinatorOwner().equals(coordinatorId))
            .orElseThrow(() -> new CoordinatorNotOwnerException(sessionId));
    if (current.getRouteEpoch() != routeEpoch) {
      throw new CoordinatorNotOwnerException(sessionId);
    }
    if (current.getCoordinatorTerm() != coordinatorTerm) {
      throw new StaleCoordinatorTermException(
          sessionId, coordinatorTerm, current.getCoordinatorTerm());
    }
    if (ownershipJpa.heartbeatIfOwner(
            current.getSessionId(), current.getCoordinatorOwner(), routeEpoch, Instant.now())
        != 1) {
      throw new CoordinatorNotOwnerException(sessionId);
    }
  }

  /**
   * 校验来自 Browser Node 的事件仍属于当前路由和 Coordinator 世代。
   *
   * <p>Node Event 入口由 Service 负载均衡到任意健康 Control Plane Pod，接收 Pod 不一定是逻辑 Coordinator
   * Owner。因此这里只校验数据库中的权威 Route Epoch、Coordinator Term 与远端 Owner Lease 新鲜度；只有事件落到 Owner Pod
   * 时才续租，绝不替其他 Pod 续租。Session 行锁负责串行化事件提交，命令路径仍通过 {@link #acquireSession} 维护单 Owner。
   */
  @Transactional
  public void assertCurrentGeneration(String sessionId, long coordinatorTerm, long routeEpoch) {
    var current =
        ownershipJpa
            .findById(sessionId)
            .orElseThrow(() -> new CoordinatorNotOwnerException(sessionId));
    if (current.getRouteEpoch() != routeEpoch) {
      throw new CoordinatorNotOwnerException(sessionId);
    }
    if (current.getCoordinatorTerm() != coordinatorTerm) {
      throw new StaleCoordinatorTermException(
          sessionId, coordinatorTerm, current.getCoordinatorTerm());
    }
    var now = Instant.now();
    if (current.getCoordinatorOwner().equals(coordinatorId)) {
      if (ownershipJpa.heartbeatIfOwner(sessionId, coordinatorId, routeEpoch, now) != 1) {
        throw new CoordinatorNotOwnerException(sessionId);
      }
      return;
    }
    if (current.getOwnerHeartbeatAt().isBefore(now.minus(leaseDuration))) {
      throw new CoordinatorNotOwnerException(sessionId);
    }
  }

  /**
   * 获取当前 Coordinator Term。
   *
   * @param sessionId Session ID
   * @return Coordinator Term
   */
  public long getCurrentTerm(String sessionId) {
    return ownershipJpa
        .findById(sessionId)
        .map(CoordinatorOwnershipEntity::getCoordinatorTerm)
        .orElse(0L);
  }
}
