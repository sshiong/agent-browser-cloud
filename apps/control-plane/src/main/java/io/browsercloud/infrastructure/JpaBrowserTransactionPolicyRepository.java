package io.browsercloud.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.BrowserTransactionPolicy;
import io.browsercloud.coordinator.BrowserTransactionPolicyRepository;
import io.browsercloud.persistence.ApplicationRecoveryContractJpaRepository;
import io.browsercloud.persistence.ApplicationRecoveryContractRevisionJpaRepository;
import io.browsercloud.persistence.SessionApplicationBindingJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Repository;

/** PostgreSQL-backed resolver for the exact Recovery Contract revision bound to a Session. */
@Repository
public class JpaBrowserTransactionPolicyRepository implements BrowserTransactionPolicyRepository {
  private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

  private final SessionApplicationBindingJpaRepository bindings;
  private final ApplicationRecoveryContractJpaRepository heads;
  private final ApplicationRecoveryContractRevisionJpaRepository revisions;
  private final ObjectMapper objectMapper;

  public JpaBrowserTransactionPolicyRepository(
      SessionApplicationBindingJpaRepository bindings,
      ApplicationRecoveryContractJpaRepository heads,
      ApplicationRecoveryContractRevisionJpaRepository revisions,
      ObjectMapper objectMapper) {
    this.bindings = bindings;
    this.heads = heads;
    this.revisions = revisions;
    this.objectMapper = objectMapper;
  }

  @Override
  public BrowserTransactionPolicy find(String sessionId, String tenantId) {
    var binding = bindings.findBySessionIdAndTenantId(sessionId, tenantId).orElse(null);
    if (binding == null) return BrowserTransactionPolicy.empty();
    var head = heads.findById(binding.getContractId()).orElse(null);
    if (head == null || !head.getTenantId().equals(tenantId)) {
      return BrowserTransactionPolicy.empty();
    }
    var revision =
        revisions
            .findByContractIdAndContractVersionAndTenantIdAndApplicationId(
                binding.getContractId(),
                binding.getContractVersion(),
                tenantId,
                binding.getApplicationId())
            .orElseThrow(() -> new IllegalStateException("BOUND_RECOVERY_REVISION_NOT_FOUND"));
    var origins = read(revision.getExpectedOrigins());
    var payment = read(revision.getPaymentSecurityRoutePrefixes());
    var critical = read(revision.getCriticalTransactionRoutePrefixes());
    var version = revision.getContractVersion();
    var hash = hash(version, origins, payment, critical);
    return new BrowserTransactionPolicy(version, origins, payment, critical, hash);
  }

  static String hash(
      long version, List<String> origins, List<String> payment, List<String> critical) {
    var canonical =
        new StringBuilder("browser-transaction-policy-v1\n").append(version).append('\n');
    origins.forEach(value -> canonical.append("O:").append(value).append('\n'));
    payment.forEach(value -> canonical.append("P:").append(value).append('\n'));
    critical.forEach(value -> canonical.append("C:").append(value).append('\n'));
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }

  private List<String> read(String value) {
    try {
      return objectMapper.readValue(value, STRING_LIST);
    } catch (Exception exception) {
      throw new IllegalStateException("STORED_BROWSER_TRANSACTION_POLICY_INVALID", exception);
    }
  }
}
