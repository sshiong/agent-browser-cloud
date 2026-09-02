package io.browsercloud.coordinator;

import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import java.util.List;
import java.util.Map;

/**
 * Session 仓储接口。
 *
 * <p>PostgreSQL 是权威状态存储。
 */
public interface SessionRepository {

  /**
   * 获取 Session，不存在则抛异常。
   *
   * @param sessionId Session ID
   * @return Session 上下文
   * @throws SessionNotFoundException 如果 Session 不存在
   */
  SessionContext require(String sessionId);

  /** 获取包含受控展示字段的 Session 查询投影。 */
  SessionDescriptor describe(String sessionId);

  /** Rename a tenant-owned Session without changing its runtime identity or execution state. */
  SessionDescriptor rename(String sessionId, String tenantId, String displayName);

  /** 在当前事务中锁定 Session 主行，用于串行化写命令。 */
  SessionContext requireForUpdate(String sessionId);

  /** 只获取 Session 主行锁；用于在读取 ownership 前统一全局锁顺序。 */
  void lockForUpdate(String sessionId);

  /**
   * 插入新的 Session。
   *
   * @param context Session 上下文
   */
  void insert(
      SessionContext context,
      String region,
      Map<String, String> metadata,
      String groupId,
      boolean humanTakeoverEnabled,
      AgentPolicy agentPolicy,
      List<String> extensionIds);

  /**
   * 使用预期的 context_epoch 更新 Session。
   *
   * <p>使用 CAS 保证不会覆盖并发修改。
   *
   * @param context 新的 Session 上下文
   * @param expectedContextEpoch 预期的 context_epoch
   * @throws StaleContextEpochException 如果 context_epoch 不匹配
   */
  void updateWithExpectedEpoch(SessionContext context, long expectedContextEpoch);

  List<SessionDescriptor> listByTenant(
      String tenantId,
      SessionState state,
      String query,
      SessionListFilter filter,
      int limit,
      int offset);

  long countByTenant(String tenantId, SessionState state, String query, SessionListFilter filter);
}
