package io.browsercloud.security;

import io.browsercloud.application.AgentConnectionAccessService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
public class AgentConnectionSecurityConfiguration {
  @Bean
  @Order(0)
  SecurityFilterChain agentConnectionChain(HttpSecurity http, AgentConnectionAccessService access)
      throws Exception {
    return http.securityMatcher("/api/v1/agent-access/**")
        .csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(a -> a.anyRequest().hasRole("MCP_AGENT"))
        .exceptionHandling(e -> e.authenticationEntryPoint((r, s, x) -> s.sendError(401)))
        .addFilterBefore(
            new OncePerRequestFilter() {
              @Override
              protected void doFilterInternal(
                  HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                  throws ServletException, IOException {
                var header = request.getHeader("Authorization");
                var identity =
                    access.authenticate(
                        header != null && header.startsWith("Bearer ")
                            ? header.substring(7)
                            : null);
                if (identity == null) {
                  response.setStatus(401);
                  response.setHeader("Cache-Control", "no-store");
                  return;
                }
                SecurityContextHolder.getContext()
                    .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(
                            identity, null, List.of(new SimpleGrantedAuthority("ROLE_MCP_AGENT"))));
                response.setHeader("Cache-Control", "no-store");
                chain.doFilter(request, response);
              }
            },
            AnonymousAuthenticationFilter.class)
        .build();
  }
}
