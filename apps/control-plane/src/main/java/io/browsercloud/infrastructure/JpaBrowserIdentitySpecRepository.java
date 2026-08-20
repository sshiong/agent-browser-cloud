package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.SessionIdentityModels.SessionIdentitySpecRequest;
import io.browsercloud.coordinator.BrowserIdentitySpec;
import io.browsercloud.coordinator.BrowserIdentitySpecRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** PostgreSQL authority adapter used by the Coordinator on first start and recovery. */
@Repository
public class JpaBrowserIdentitySpecRepository implements BrowserIdentitySpecRepository {

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  public JpaBrowserIdentitySpecRepository(JdbcTemplate jdbc, ObjectMapper objectMapper) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
  }

  @Override
  public BrowserIdentitySpec require(String sessionId, String tenantId) {
    return jdbc.queryForObject(
        "SELECT spec_json::text, version, spec_hash FROM session_identity_specs WHERE session_id=? AND tenant_id=?",
        (result, row) -> {
          var spec = read(result.getString(1));
          return new BrowserIdentitySpec(
              spec.userAgent(),
              spec.timezone(),
              spec.locale(),
              spec.languages(),
              spec.webRtcPolicy() == null ? "DEFAULT" : spec.webRtcPolicy().name(),
              spec.dnsPolicy() == null ? "SYSTEM" : spec.dnsPolicy().name(),
              spec.viewportWidth(),
              spec.viewportHeight(),
              spec.screenWidth(),
              spec.screenHeight(),
              spec.deviceScaleFactor(),
              spec.fingerprintProfile(),
              spec.operatingSystemProfile(),
              result.getLong(2),
              result.getString(3));
        },
        sessionId,
        tenantId);
  }

  private SessionIdentitySpecRequest read(String json) {
    try {
      return objectMapper.readValue(json, SessionIdentitySpecRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Session identity spec is invalid", exception);
    }
  }
}
