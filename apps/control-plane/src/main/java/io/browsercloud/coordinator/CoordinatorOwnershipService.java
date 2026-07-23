package io.browsercloud.coordinator;

import io.browsercloud.persistence.CoordinatorOwnershipEntity;
import java.time.Instant;
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

  public CoordinatorOwnershipService(CoordinatorOwnershipJpaRepository ownershipJpa) {
    this.ownershipJpa = ownershipJpa;
  }

  /**
   * 尝试接管 Session 的 Coordinator 所有权。
   *
   * @param sessionId Session ID
   * @param coordinatorId Coordinator 实例 ID
   * @return 是否成功接管
   */
  @Transactional
  public boolean claimSession(String sessionId, String coordinatorId) {
    var now = Instant.now();
    return ownershipJpa.claimIfAbsentOrExpired(sessionId, coordinatorId, now, now.minusSeconds(30))
        == 1;
  }

  /**
   * 更新心跳。
   *
   * @param sessionId Session ID
   * @param coordinatorId Coordinator 实例 ID
   */
  @Transactional
  public void heartbeat(String sessionId, String coordinatorId) {
    ownershipJpa.heartbeatIfOwner(sessionId, coordinatorId, Instant.now());
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
