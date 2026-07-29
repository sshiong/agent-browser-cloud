package io.browsercloud.security;

import java.util.Collection;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {

  private static void common(HttpSecurity http) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(
            sessions -> sessions.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .formLogin(form -> form.disable())
        .httpBasic(basic -> basic.disable());
  }

  @Configuration
  @ConditionalOnExpression("'${app.environment:local}' != 'production'")
  static class LocalSecurity {

    @Bean
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
      common(http);
      http.authorizeHttpRequests(requests -> requests.anyRequest().permitAll())
          .addFilterBefore(
              new LocalHeaderAuthenticationFilter(), AnonymousAuthenticationFilter.class);
      return http.build();
    }
  }

  @Configuration
  @ConditionalOnProperty(name = "app.environment", havingValue = "production")
  static class ProductionSecurity {

    @Bean
    SecurityFilterChain productionSecurityFilterChain(
        HttpSecurity http, @Value("${security.roles-claim:roles}") String rolesClaim)
        throws Exception {
      common(http);
      http.authorizeHttpRequests(
              requests ->
                  requests
                      .requestMatchers("/actuator/prometheus")
                      .hasRole("PLATFORM_ADMIN")
                      .requestMatchers("/actuator/health", "/actuator/info")
                      .permitAll()
                      .anyRequest()
                      .authenticated())
          .oauth2ResourceServer(
              resource ->
                  resource.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtConverter(rolesClaim))));
      return http.build();
    }

    @Bean
    JwtDecoder productionJwtDecoder(
        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer,
        @Value("${security.roles-claim:roles}") String rolesClaim) {
      var decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
      decoder.setJwtValidator(
          new DelegatingOAuth2TokenValidator<>(
              JwtValidators.createDefaultWithIssuer(issuer), new AdminMfaJwtValidator(rolesClaim)));
      return decoder;
    }

    private static JwtAuthenticationConverter jwtConverter(String rolesClaim) {
      var converter = new JwtAuthenticationConverter();
      converter.setJwtGrantedAuthoritiesConverter(new RoleClaimConverter(rolesClaim));
      return converter;
    }
  }

  private record RoleClaimConverter(String claimName)
      implements Converter<Jwt, Collection<GrantedAuthority>> {

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      var roles = jwt.getClaimAsStringList(claimName);
      if (roles == null) {
        return java.util.List.of();
      }
      return roles.stream()
          .map(role -> role.toUpperCase(Locale.ROOT))
          .filter(
              role ->
                  role.equals("TENANT_VIEWER")
                      || role.equals("TENANT_OPERATOR")
                      || role.equals("TENANT_ADMIN")
                      || role.equals("APPLICATION_ADAPTER")
                      || role.equals("SECURITY_ADMIN")
                      || role.equals("PLATFORM_ADMIN"))
          .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
          .toList();
    }
  }
}
