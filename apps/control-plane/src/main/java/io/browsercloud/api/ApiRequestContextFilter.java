package io.browsercloud.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** 统一请求 ID 与基础浏览器安全响应头。 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiRequestContextFilter extends OncePerRequestFilter {

  static final String REQUEST_ID_ATTRIBUTE = "browsercloud.requestId";

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    String requestId = request.getHeader("X-Request-Id");
    if (requestId == null || !requestId.matches("^[A-Za-z0-9._-]{1,128}$")) {
      requestId = UUID.randomUUID().toString();
    }
    request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
    response.setHeader("X-Request-Id", requestId);
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader("Cache-Control", "no-store");
    filterChain.doFilter(request, response);
  }
}
