package io.browsercloud.domain.resource;

public enum MaximumReachedPolicy {
  PAUSE_AGENT,
  WAIT_SAFE_POINT_MIGRATE,
  HIBERNATE,
  TERMINATE_STRICT
}
