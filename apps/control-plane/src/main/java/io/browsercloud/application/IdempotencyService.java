package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.StateResyncRequest;
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
    return claim(
        tenantId, CREATE_SESSION, idempotencyKey, hashRequest(request), candidateSessionId);
  }

  String claimStateResync(
      String tenantId,
      String sessionId,
      String idempotencyKey,
      StateResyncRequest request,
      String candidateRequestId) {
    return claim(
        tenantId,
        "STATE_RESYNC:" + sessionId,
        idempotencyKey,
        hashRequest(request),
        candidateRequestId);
  }

  private String claim(
      String tenantId,
      String operationType,
      String idempotencyKey,
      String requestHash,
      String candidateResourceId) {
    int claimed =
        repository.claim(
            newId("idem_"),
            tenantId,
            operationType,
            idempotencyKey,
            requestHash,
            candidateResourceId,
            Instant.now());
    if (claimed == 1) {
      return candidateResourceId;
    }
    return repository
        .findByTenantIdAndOperationTypeAndIdempotencyKey(tenantId, operationType, idempotencyKey)
        .map(
            record -> {
              if (!record.getRequestHash().equals(requestHash)) {
                throw new IdempotencyConflictException();
              }
              return record.getResourceId();
            })
        .orElseThrow(() -> new IllegalStateException("Idempotency claim disappeared"));
  }

  private String hashRequest(Object request) {
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
