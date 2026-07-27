package io.browsercloud.domain.resource;

public enum ResourcePolicyStatus {
  STABLE,
  OBSERVING,
  SCALING_UP,
  SCALING_DOWN,
  AT_MAXIMUM,
  WAITING_SAFE_POINT,
  MIGRATING,
  AGENT_PAUSED,
  HIBERNATING,
  CRITICAL
}
