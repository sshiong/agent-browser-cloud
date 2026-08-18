package io.browsercloud.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Local/Test 专用身份适配器；Production 配置不会注册该 Filter。 */
public final class LocalHeaderAuthenticationFilter extends OncePerRequestFilter {

  private static final String IDENTIFIER = "^[A-Za-z0-9_-]{1,128}$";
  private static final Set<String> ALLOWED_ROLES =
      Set.of(
          "TENANT_VIEWER",
          "TENANT_OPERATOR",
          "TENANT_ADMIN",
          "APPLICATION_ADAPTER",
          "VALIDATION_WORKER",
          "GAMEDAY_WORKER",
          "AGENT_WORKER",
          "REVIEWER_WORKER",
          "VISION_WORKER",
          "SECURITY_ADMIN",
          "PLATFORM_ADMIN");

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var tenantId = request.getHeader("X-Tenant-Id");
    var actorId = request.getHeader("X-Actor-Id");
    if (!valid(tenantId)) {
      unauthorized(response, "LOCAL_TENANT_HEADER_REQUIRED");
      return;
    }
    if (actorId == null || actorId.isBlank()) {
      actorId = "user-local";
    }
    if (!valid(actorId)) {
      unauthorized(response, "LOCAL_ACTOR_HEADER_INVALID");
      return;
    }
    var roles = parseRoles(request.getHeader("X-Roles"));
    var principal = new PlatformPrincipal(tenantId, actorId, roles);
    var authorities =
        roles.stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList();
    var authentication =
        UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authentication);
    try {
      filterChain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static Set<String> parseRoles(String value) {
    if (value == null || value.isBlank()) {
      return Set.of("TENANT_ADMIN");
    }
    var roles =
        Arrays.stream(value.split(","))
            .map(String::trim)
            .map(role -> role.toUpperCase(Locale.ROOT))
            .filter(ALLOWED_ROLES::contains)
            .collect(Collectors.toUnmodifiableSet());
    return roles.isEmpty() ? Set.of("TENANT_VIEWER") : roles;
  }

  private static boolean valid(String value) {
    return value != null && value.matches(IDENTIFIER);
  }

  private static void unauthorized(HttpServletResponse response, String code) throws IOException {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
    response.setContentType("application/json");
    response
        .getWriter()
        .write(
            "{\"code\":\""
                + code
                + "\",\"message\":\"Authenticated local identity is required\","
                + "\"details\":{},\"requestId\":\"\",\"timestamp\":\""
                + Instant.now()
                + "\"}");
  }
}
