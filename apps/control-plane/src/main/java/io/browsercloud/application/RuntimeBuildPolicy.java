package io.browsercloud.application;

import io.browsercloud.persistence.RuntimeBuildEntity;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Prevents unvalidated or unsigned Runtime artifacts from reaching Browser Nodes. */
@Service
public class RuntimeBuildPolicy {

  private final RuntimeBuildJpaRepository repository;
  private final boolean production;
  private final Map<String, PublicKey> trustedSigningKeys;

  public RuntimeBuildPolicy(
      RuntimeBuildJpaRepository repository,
      @Value("${app.environment:local}") String environment,
      @Value("${security.runtime-signing-public-keys:}") String trustedSigningKeys) {
    this.repository = repository;
    this.production = "production".equalsIgnoreCase(environment);
    this.trustedSigningKeys = parseTrustedKeys(trustedSigningKeys);
  }

  public void requireApproved(String buildId) {
    var build =
        repository
            .findById(buildId)
            .orElseThrow(() -> new RuntimeBuildRejectedException("BUILD_NOT_REGISTERED"));
    if (!"STABLE".equals(build.getRegressionStatus())
        || !"STABLE".equals(build.getReleaseChannel())
        || build.getValidatedAt() == null
        || build.getReleasedAt() == null) {
      throw new RuntimeBuildRejectedException("BUILD_NOT_VALIDATED");
    }
    requireSupplyChainEvidence(build);
  }

  public void requireReleaseCandidate(String buildId) {
    var build =
        repository
            .findById(buildId)
            .orElseThrow(() -> new RuntimeBuildRejectedException("BUILD_NOT_REGISTERED"));
    if (build.getValidatedAt() == null || "DISABLED".equals(build.getRegressionStatus())) {
      throw new RuntimeBuildRejectedException("BUILD_NOT_VALIDATED");
    }
    requireSupplyChainEvidence(build);
  }

  private void requireSupplyChainEvidence(RuntimeBuildEntity build) {
    if (build.getSignature() == null
        || build.getSignature().isBlank()
        || build.getSbomUrl() == null
        || build.getSbomUrl().isBlank()) {
      throw new RuntimeBuildRejectedException("SUPPLY_CHAIN_EVIDENCE_MISSING");
    }
    if (production) {
      if (build.getArtifactDigest() == null
          || !build.getArtifactDigest().matches("^sha256:[a-f0-9]{64}$")
          || build.getSigningKeyId() == null
          || build.getSigningKeyId().isBlank()
          || !(build.getSbomUrl().startsWith("https://")
              || build.getSbomUrl().startsWith("oci://"))) {
        throw new RuntimeBuildRejectedException("PRODUCTION_PROVENANCE_INVALID");
      }
      var publicKey = trustedSigningKeys.get(build.getSigningKeyId());
      if (publicKey == null) {
        throw new RuntimeBuildRejectedException("SIGNING_KEY_UNTRUSTED");
      }
      if (!verifySignature(build, publicKey)) {
        throw new RuntimeBuildRejectedException("RUNTIME_SIGNATURE_INVALID");
      }
    }
  }

  public boolean isSignatureVerified(RuntimeBuildEntity build) {
    if (!production) {
      return build.getSignature() != null && !build.getSignature().isBlank();
    }
    if (build.getArtifactDigest() == null
        || !build.getArtifactDigest().matches("^sha256:[a-f0-9]{64}$")
        || build.getSigningKeyId() == null) {
      return false;
    }
    var publicKey = trustedSigningKeys.get(build.getSigningKeyId());
    return publicKey != null && verifySignature(build, publicKey);
  }

  private static boolean verifySignature(RuntimeBuildEntity build, PublicKey publicKey) {
    try {
      var verifier = Signature.getInstance("Ed25519");
      verifier.initVerify(publicKey);
      verifier.update(canonicalPayload(build).getBytes(StandardCharsets.UTF_8));
      return verifier.verify(Base64.getDecoder().decode(build.getSignature()));
    } catch (IllegalArgumentException exception) {
      return false;
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to verify Runtime signature", exception);
    }
  }

  static String canonicalPayload(RuntimeBuildEntity build) {
    return build.getBuildId() + "|" + build.getArtifactDigest() + "|" + build.getSbomUrl();
  }

  private static Map<String, PublicKey> parseTrustedKeys(String encodedKeys) {
    if (encodedKeys == null || encodedKeys.isBlank()) {
      return Map.of();
    }
    try {
      var factory = KeyFactory.getInstance("Ed25519");
      var result = new HashMap<String, PublicKey>();
      for (var entry : encodedKeys.split(",")) {
        var fields = entry.trim().split("=", 2);
        if (fields.length != 2 || fields[0].isBlank() || fields[1].isBlank()) {
          throw new IllegalArgumentException("Invalid Runtime signing key entry");
        }
        result.put(
            fields[0],
            factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(fields[1]))));
      }
      return Map.copyOf(result);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException(
          "Invalid Runtime signing public key configuration", exception);
    }
  }

  public static final class RuntimeBuildRejectedException extends RuntimeException {
    public RuntimeBuildRejectedException(String reason) {
      super(reason);
    }
  }
}
