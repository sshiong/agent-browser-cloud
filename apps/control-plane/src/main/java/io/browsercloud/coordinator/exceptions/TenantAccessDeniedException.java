package io.browsercloud.coordinator.exceptions;

/** 请求租户与 Session 所属租户不一致。异常消息不暴露资源所属租户。 */
public class TenantAccessDeniedException extends RuntimeException {

  public TenantAccessDeniedException(String sessionId) {
    super("Session is not accessible: " + sessionId);
  }
}
