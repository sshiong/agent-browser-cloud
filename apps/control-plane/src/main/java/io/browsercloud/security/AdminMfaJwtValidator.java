package io.browsercloud.security;

import java.util.Collection;
import java.util.Locale;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Production administrators must present an IdP-issued MFA authentication method reference. */
public final class AdminMfaJwtValidator implements OAuth2TokenValidator<Jwt> {

  private static final OAuth2Error MFA_REQUIRED =
      new OAuth2Error("admin_mfa_required", "Administrative tokens require amr=mfa", null);

  private final String rolesClaim;

  public AdminMfaJwtValidator(String rolesClaim) {
    this.rolesClaim = rolesClaim;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    if (!hasAdministrativeRole(token.getClaims().get(rolesClaim))) {
      return OAuth2TokenValidatorResult.success();
    }
    var amr = token.getClaims().get("amr");
    if (amr instanceof Collection<?> methods
        && methods.stream()
            .map(String::valueOf)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(value -> value.equals("mfa"))) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(MFA_REQUIRED);
  }

  private boolean hasAdministrativeRole(Object claim) {
    if (!(claim instanceof Collection<?> roles)) {
      return false;
    }
    return roles.stream()
        .map(String::valueOf)
        .map(value -> value.toUpperCase(Locale.ROOT))
        .anyMatch(value -> value.equals("TENANT_ADMIN") || value.equals("SECURITY_ADMIN"));
  }
}
