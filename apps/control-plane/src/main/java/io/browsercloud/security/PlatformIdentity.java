package io.browsercloud.security;

import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** 从 Spring Security Principal 派生 Tenant、Actor 和 Role。 */
@Component
public class PlatformIdentity {

  private final String tenantClaim;

  public PlatformIdentity(@Value("${security.tenant-claim:tenant_id}") String tenantClaim) {
    this.tenantClaim = tenantClaim;
  }

  public PlatformPrincipal current() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication == null || !authentication.isAuthenticated()) {
      throw new MissingPlatformIdentityException();
    }
    if (authentication.getPrincipal() instanceof PlatformPrincipal principal) {
      return principal;
    }
    if (authentication instanceof JwtAuthenticationToken jwtAuthentication) {
      var tenantId = jwtAuthentication.getToken().getClaimAsString(tenantClaim);
      var actorId = jwtAuthentication.getToken().getSubject();
      if (!validIdentifier(tenantId) || !validIdentifier(actorId)) {
        throw new MissingPlatformIdentityException();
      }
      Set<String> roles =
          authentication.getAuthorities().stream()
              .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
              .map(value -> value.toUpperCase(Locale.ROOT))
              .collect(Collectors.toUnmodifiableSet());
      return new PlatformPrincipal(tenantId, actorId, roles);
    }
    throw new MissingPlatformIdentityException();
  }

  private static boolean validIdentifier(String value) {
    return value != null && value.matches("^[A-Za-z0-9_-]{1,128}$");
  }

  public static final class MissingPlatformIdentityException extends RuntimeException {
    public MissingPlatformIdentityException() {
      super("Authenticated platform identity is required");
    }
  }
}
