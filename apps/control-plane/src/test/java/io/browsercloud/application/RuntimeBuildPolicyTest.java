package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.browsercloud.persistence.RuntimeBuildEntity;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuntimeBuildPolicyTest {

  @Test
  void acceptsTrustedEd25519SignatureForExactArtifactDigest() throws Exception {
    var fixture = signedBuild("sha256:" + "a".repeat(64), "release-key-2026");
    var repository = mock(RuntimeBuildJpaRepository.class);
    when(repository.findById("runtime-126")).thenReturn(Optional.of(fixture.build()));
    var policy =
        new RuntimeBuildPolicy(
            repository,
            "production",
            "release-key-2026="
                + Base64.getEncoder().encodeToString(fixture.publicKey().getEncoded()));

    assertDoesNotThrow(() -> policy.requireReleaseCandidate("runtime-126"));
    assertTrue(policy.isSignatureVerified(fixture.build()));
  }

  @Test
  void rejectsArtifactDigestChangedAfterSigning() throws Exception {
    var fixture = signedBuild("sha256:" + "a".repeat(64), "release-key-2026");
    when(fixture.build().getArtifactDigest()).thenReturn("sha256:" + "b".repeat(64));
    var repository = mock(RuntimeBuildJpaRepository.class);
    when(repository.findById("runtime-126")).thenReturn(Optional.of(fixture.build()));
    var policy =
        new RuntimeBuildPolicy(
            repository,
            "production",
            "release-key-2026="
                + Base64.getEncoder().encodeToString(fixture.publicKey().getEncoded()));

    var error =
        assertThrows(
            RuntimeBuildPolicy.RuntimeBuildRejectedException.class,
            () -> policy.requireReleaseCandidate("runtime-126"));

    assertEquals("RUNTIME_SIGNATURE_INVALID", error.getMessage());
  }

  @Test
  void rejectsSignatureFromUnknownKeyId() throws Exception {
    var fixture = signedBuild("sha256:" + "a".repeat(64), "unknown-key");
    var repository = mock(RuntimeBuildJpaRepository.class);
    when(repository.findById("runtime-126")).thenReturn(Optional.of(fixture.build()));
    var policy = new RuntimeBuildPolicy(repository, "production", "");

    var error =
        assertThrows(
            RuntimeBuildPolicy.RuntimeBuildRejectedException.class,
            () -> policy.requireReleaseCandidate("runtime-126"));

    assertEquals("SIGNING_KEY_UNTRUSTED", error.getMessage());
  }

  private static SignedBuild signedBuild(String digest, String keyId) throws Exception {
    var generator = KeyPairGenerator.getInstance("Ed25519");
    var keyPair = generator.generateKeyPair();
    var build = mock(RuntimeBuildEntity.class);
    when(build.getBuildId()).thenReturn("runtime-126");
    when(build.getValidatedAt()).thenReturn(Instant.now());
    when(build.getRegressionStatus()).thenReturn("PASSED");
    when(build.getArtifactDigest()).thenReturn(digest);
    when(build.getSigningKeyId()).thenReturn(keyId);
    when(build.getSbomUrl()).thenReturn("oci://registry.example/runtime-126-sbom");
    var signer = Signature.getInstance("Ed25519");
    signer.initSign(keyPair.getPrivate());
    signer.update(
        ("runtime-126|" + digest + "|oci://registry.example/runtime-126-sbom")
            .getBytes(StandardCharsets.UTF_8));
    when(build.getSignature()).thenReturn(Base64.getEncoder().encodeToString(signer.sign()));
    return new SignedBuild(build, keyPair.getPublic());
  }

  private record SignedBuild(RuntimeBuildEntity build, java.security.PublicKey publicKey) {}
}
