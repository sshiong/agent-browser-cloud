package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.browsercloud.api.AuditEventListResponse;
import io.browsercloud.api.AuditEventView;
import io.browsercloud.infrastructure.OffsetPageRequest;
import io.browsercloud.persistence.AuditEventEntity;
import io.browsercloud.persistence.AuditEventJpaRepository;
import io.browsercloud.persistence.TenantAuditHeadJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 追加式、防篡改、租户隔离的审计服务。 */
@Service
public class AuditApplicationService {

  private static final Set<String> SENSITIVE_FIELDS =
      Set.of(
          "password",
          "passwd",
          "pwd",
          "cookie",
          "authorization",
          "bearer",
          "otp",
          "one_time_code",
          "onetimecode",
          "验证码",
          "密码",
          "口令");

  private final AuditEventJpaRepository eventRepository;
  private final TenantAuditHeadJpaRepository headRepository;
  private final ObjectMapper objectMapper;
  private final Duration retention;

  public AuditApplicationService(
      AuditEventJpaRepository eventRepository,
      TenantAuditHeadJpaRepository headRepository,
      ObjectMapper objectMapper,
      @Value("${audit.retention-days:365}") long retentionDays) {
    if (retentionDays < 30 || retentionDays > 3650) {
      throw new IllegalStateException("Audit retention must be between 30 and 3650 days");
    }
    this.eventRepository = eventRepository;
    this.headRepository = headRepository;
    this.objectMapper = objectMapper;
    this.retention = Duration.ofDays(retentionDays);
  }

  @Transactional
  public AuditEventView append(AuditRecord record) {
    headRepository.ensure(record.tenantId());
    var head = headRepository.findForUpdate(record.tenantId()).orElseThrow();
    var sequence = head.getSequenceNo() + 1;
    // PostgreSQL timestamptz keeps microsecond precision. Hash the same precision that is
    // persisted.
    var now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    var eventId = "aud_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var details = redactedJson(record.details());
    var eventHash =
        hash(
            record.tenantId(),
            Long.toString(sequence),
            nullToEmpty(head.getHeadHash()),
            eventId,
            nullToEmpty(record.sessionId()),
            record.eventType(),
            record.actorType(),
            nullToEmpty(record.actorId()),
            nullToEmpty(record.resourceType()),
            nullToEmpty(record.resourceId()),
            record.action(),
            record.result(),
            details,
            now.toString());
    var entity = new AuditEventEntity();
    entity.setEventId(eventId);
    entity.setTenantId(record.tenantId());
    entity.setSessionId(record.sessionId());
    entity.setEventType(record.eventType());
    entity.setActorType(record.actorType());
    entity.setActorId(record.actorId());
    entity.setResourceType(record.resourceType());
    entity.setResourceId(record.resourceId());
    entity.setAction(record.action());
    entity.setResult(record.result());
    entity.setDetails(details);
    entity.setSequenceNo(sequence);
    entity.setPreviousEventHash(head.getHeadHash());
    entity.setEventHash(eventHash);
    entity.setRequestId(record.requestId());
    entity.setRetentionUntil(now.plus(retention));
    entity.setLegalHold(false);
    entity.setCreatedAt(now);
    eventRepository.save(entity);
    head.advance(sequence, eventHash, now);
    headRepository.save(head);
    return toView(entity);
  }

  @Transactional(readOnly = true)
  public AuditEventListResponse list(
      String tenantId, String sessionId, String eventType, int limit, int offset) {
    var page = new OffsetPageRequest(offset, limit, Sort.unsorted());
    var normalizedSession = blankToNull(sessionId);
    var normalizedType = blankToNull(eventType);
    var events =
        normalizedSession != null && normalizedType != null
            ? eventRepository.findAllByTenantIdAndSessionIdAndEventTypeOrderBySequenceNoDesc(
                tenantId, normalizedSession, normalizedType, page)
            : normalizedSession != null
                ? eventRepository.findAllByTenantIdAndSessionIdOrderBySequenceNoDesc(
                    tenantId, normalizedSession, page)
                : normalizedType != null
                    ? eventRepository.findAllByTenantIdAndEventTypeOrderBySequenceNoDesc(
                        tenantId, normalizedType, page)
                    : eventRepository.findAllByTenantIdOrderBySequenceNoDesc(tenantId, page);
    var verification = verify(tenantId);
    return new AuditEventListResponse(
        events.stream().map(this::toView).toList(),
        eventRepository.countByTenantId(tenantId),
        verification.valid(),
        verification.headHash());
  }

  @Transactional(readOnly = true)
  public ChainVerification verify(String tenantId) {
    String previous = null;
    long expectedSequence = 1;
    for (var event : eventRepository.findAllByTenantIdOrderBySequenceNoAsc(tenantId)) {
      if (event.getSequenceNo() == null || event.getSequenceNo() != expectedSequence) {
        return new ChainVerification(false, previous);
      }
      var recalculated =
          hash(
              event.getTenantId(),
              Long.toString(event.getSequenceNo()),
              nullToEmpty(previous),
              event.getEventId(),
              nullToEmpty(event.getSessionId()),
              event.getEventType(),
              event.getActorType(),
              nullToEmpty(event.getActorId()),
              nullToEmpty(event.getResourceType()),
              nullToEmpty(event.getResourceId()),
              event.getAction(),
              event.getResult(),
              canonicalJson(event.getDetails()),
              event.getCreatedAt().toString());
      if (event.getEventHash() == null
          || event.getCreatedAt() == null
          || !java.util.Objects.equals(event.getPreviousEventHash(), previous)
          || !MessageDigest.isEqual(
              recalculated.getBytes(StandardCharsets.UTF_8),
              event.getEventHash().getBytes(StandardCharsets.UTF_8))) {
        return new ChainVerification(false, previous);
      }
      previous = event.getEventHash();
      expectedSequence++;
    }
    return new ChainVerification(true, previous);
  }

  private AuditEventView toView(AuditEventEntity event) {
    try {
      return new AuditEventView(
          event.getEventId(),
          event.getSequenceNo(),
          event.getSessionId(),
          event.getEventType(),
          event.getActorType(),
          event.getActorId(),
          event.getResourceType(),
          event.getResourceId(),
          event.getAction(),
          event.getResult(),
          objectMapper.readValue(event.getDetails(), new TypeReference<Map<String, Object>>() {}),
          event.getPreviousEventHash(),
          event.getEventHash(),
          event.getRequestId(),
          event.getRetentionUntil(),
          event.isLegalHold(),
          event.getCreatedAt());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Stored audit details are invalid", exception);
    }
  }

  private String redactedJson(Map<String, Object> details) {
    try {
      var tree = objectMapper.valueToTree(details == null ? Map.of() : details);
      redactTree(tree);
      return canonicalJson(objectMapper.writeValueAsString(tree));
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("Audit details are not serializable", exception);
    }
  }

  private String canonicalJson(String json) {
    try {
      var value = objectMapper.readValue(json, Object.class);
      return objectMapper
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Audit JSON cannot be canonicalized", exception);
    }
  }

  private void redactTree(JsonNode node) {
    if (node instanceof ObjectNode object) {
      object
          .properties()
          .forEach(
              entry -> {
                var normalized = entry.getKey().toLowerCase(Locale.ROOT).replace("-", "_");
                if (SENSITIVE_FIELDS.contains(normalized)) {
                  object.put(entry.getKey(), "[REDACTED]");
                } else if (entry.getValue().isTextual()) {
                  object.put(entry.getKey(), AgentDataMinimizer.redact(entry.getValue().asText()));
                } else {
                  redactTree(entry.getValue());
                }
              });
    } else if (node.isArray()) {
      node.forEach(this::redactTree);
    }
  }

  private static String hash(String... values) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      for (var value : values) {
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
      }
      return java.util.HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  public record AuditRecord(
      String tenantId,
      String sessionId,
      String eventType,
      String actorType,
      String actorId,
      String resourceType,
      String resourceId,
      String action,
      String result,
      Map<String, Object> details,
      String requestId) {}

  public record ChainVerification(boolean valid, String headHash) {}
}
