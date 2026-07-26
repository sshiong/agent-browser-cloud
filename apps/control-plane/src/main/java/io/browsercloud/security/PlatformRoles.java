package io.browsercloud.security;

/** 平台 API 的 RBAC 表达式。 */
public final class PlatformRoles {

  public static final String READ =
      "hasAnyRole('TENANT_VIEWER','TENANT_OPERATOR','TENANT_ADMIN','SECURITY_ADMIN','PLATFORM_ADMIN')";
  public static final String OPERATE =
      "hasAnyRole('TENANT_OPERATOR','TENANT_ADMIN','SECURITY_ADMIN','PLATFORM_ADMIN')";
  public static final String ADMIN = "hasAnyRole('TENANT_ADMIN','SECURITY_ADMIN','PLATFORM_ADMIN')";
  public static final String SECURITY_ADMIN = "hasRole('SECURITY_ADMIN')";
  public static final String PLATFORM_ADMIN = "hasRole('PLATFORM_ADMIN')";

  private PlatformRoles() {}
}
