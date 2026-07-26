package io.browsercloud.security;

import java.util.Set;

/** 由认证层生成的最小平台身份；业务 Controller 不再读取租户或 Actor 请求头。 */
public record PlatformPrincipal(String tenantId, String actorId, Set<String> roles) {

  public PlatformPrincipal {
    roles = Set.copyOf(roles);
  }
}
