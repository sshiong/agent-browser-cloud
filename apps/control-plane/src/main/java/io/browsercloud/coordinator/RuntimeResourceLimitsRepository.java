package io.browsercloud.coordinator;

import io.browsercloud.domain.capacity.RuntimeResourceLimits;

/** Runtime 恢复必须重用已提交 Placement 的限制，禁止以较宽默认值重新启动。 */
public interface RuntimeResourceLimitsRepository {
  RuntimeResourceLimits require(String sessionId);
}
