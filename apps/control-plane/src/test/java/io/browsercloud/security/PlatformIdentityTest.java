package io.browsercloud.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class PlatformIdentityTest {

  private final PlatformIdentity identity = new PlatformIdentity("tenant_id");

  @AfterEach
  void clearSecurityContext() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void derivesTenantActorAndRoleFromValidatedJwt() {
    var jwt =
        new Jwt(
            "encoded",
            Instant.parse("2026-07-26T00:00:00Z"),
            Instant.parse("2026-07-26T01:00:00Z"),
            Map.of("alg", "RS256"),
            Map.of("sub", "user-42", "tenant_id", "tenant-7"));
    SecurityContextHolder.getContext()
        .setAuthentication(
            new JwtAuthenticationToken(
                jwt, Set.of(new SimpleGrantedAuthority("ROLE_TENANT_OPERATOR"))));

    assertThat(identity.current())
        .isEqualTo(new PlatformPrincipal("tenant-7", "user-42", Set.of("TENANT_OPERATOR")));
  }

  @Test
  void rejectsJwtWithoutTenantClaim() {
    var jwt =
        new Jwt(
            "encoded",
            Instant.parse("2026-07-26T00:00:00Z"),
            Instant.parse("2026-07-26T01:00:00Z"),
            Map.of("alg", "RS256"),
            Map.of("sub", "user-42"));
    SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

    assertThatThrownBy(identity::current)
        .isInstanceOf(PlatformIdentity.MissingPlatformIdentityException.class);
  }

  @Test
  void localFilterRequiresTenantAndCreatesBoundPrincipal() throws Exception {
    var filter = new LocalHeaderAuthenticationFilter();
    var request = new MockHttpServletRequest("GET", "/api/v1/sessions");
    var response = new MockHttpServletResponse();

    filter.doFilter(request, response, new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(401);

    request = new MockHttpServletRequest("GET", "/api/v1/sessions");
    request.addHeader("X-Tenant-Id", "tenant-local");
    request.addHeader("X-Actor-Id", "operator-local");
    request.addHeader("X-Roles", "TENANT_OPERATOR");
    response = new MockHttpServletResponse();
    var captured = new PlatformPrincipal[1];

    filter.doFilter(
        request,
        response,
        (ignoredRequest, ignoredResponse) -> {
          var authentication = SecurityContextHolder.getContext().getAuthentication();
          assertThat(authentication).isInstanceOf(UsernamePasswordAuthenticationToken.class);
          captured[0] = (PlatformPrincipal) authentication.getPrincipal();
        });

    assertThat(captured[0])
        .isEqualTo(
            new PlatformPrincipal("tenant-local", "operator-local", Set.of("TENANT_OPERATOR")));
    assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
  }
}
