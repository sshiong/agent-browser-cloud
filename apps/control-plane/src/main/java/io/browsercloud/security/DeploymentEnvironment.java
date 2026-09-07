package io.browsercloud.security;

/** Development exemptions require the same explicit environment names as local authentication. */
public final class DeploymentEnvironment {
  private DeploymentEnvironment() {}

  public static boolean requiresProductionSecurity(String environment) {
    return !"local".equals(environment) && !"test".equals(environment);
  }
}
