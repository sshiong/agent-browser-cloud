package io.browsercloud.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AdminMfaJwtValidatorTest {

  private final AdminMfaJwtValidator validator = new AdminMfaJwtValidator("roles");

  @Test
  void requiresMfaForAdministrativeTokens() {
    assertThat(validator.validate(jwt(List.of("TENANT_ADMIN"), List.of("pwd"))).hasErrors())
        .isTrue();
    assertThat(
            validator.validate(jwt(List.of("SECURITY_ADMIN"), List.of("pwd", "mfa"))).hasErrors())
        .isFalse();
  }

  @Test
  void doesNotRequireMfaForOperatorToken() {
    assertThat(validator.validate(jwt(List.of("TENANT_OPERATOR"), List.of("pwd"))).hasErrors())
        .isFalse();
  }

  private Jwt jwt(List<String> roles, List<String> amr) {
    return new Jwt(
        "encoded",
        Instant.parse("2026-07-26T00:00:00Z"),
        Instant.parse("2026-07-26T01:00:00Z"),
        Map.of("alg", "RS256"),
        Map.of("sub", "user-42", "tenant_id", "tenant-7", "roles", roles, "amr", amr));
  }
}
