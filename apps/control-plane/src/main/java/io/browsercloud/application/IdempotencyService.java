package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.coordinator.exceptions.IdempotencyConflictException;
import io.browsercloud.persistence.ApiIdempotencyJpaRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** PostgreSQL 权威幂等记录；Redis 只能作为未来的加速层。 */
@Service
public class IdempotencyService {

  static final String CREATE_SESSION = "CREATE_SESSION";

  private final ApiIdempotencyJpaRepository repository;
  private final ObjectMapper canonicalMapper;

  IdempotencyService(ApiIdempotencyJpaRepository repository) {
    this.repository = repository;
    this.canonicalMapper =
        JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();
  }

  String claimCreateSession(
      String tenantId,
      String idempotencyKey,
      CreateSessionRequest request,
      String candidateSessionId) {
    String requestHash = hashRequest(request);
    int claimed =
        repository.claim(
            newId("idem_"),
            tenantId,
            CREATE_SESSION,
            idempotencyKey,
            requestHash,
            candidateSessionId,
            Instant.now());
    if (claimed == 1) {
      return candidateSessionId;
    }

    return repository
        .findByTenantIdAndOperationTypeAndIdempotencyKey(tenantId, CREATE_SESSION, idempotencyKey)
        .map(
            record -> {
              if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
              }
              return record.getResourceId();
            })
        .orElseThrow(() -> new IllegalStateException("Idempotency claim disappeared"));
  }

  private String hashRequest(CreateSessionRequest request) {
    try {
      byte[] json = canonicalMapper.writeValueAsBytes(request);
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(json);
      return HexFormat.of().formatHex(digest);
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Failed to hash idempotent request", exception);
    }
  }

  private static String newId(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
