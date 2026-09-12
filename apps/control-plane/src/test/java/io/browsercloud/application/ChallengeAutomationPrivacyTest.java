package io.browsercloud.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChallengeAutomationPrivacyTest {

  @Test
  void requiresLocalOcrPiiCapabilityBeforeClaimingPixels() {
    var missing =
        assertThrows(
            ChallengeAutomationApplicationService.ChallengeAutomationRejectedException.class,
            () ->
                ChallengeAutomationApplicationService.validateVisionCapabilities(
                    Map.of("screenshot-ocr-actions-v1", true)));
    assertEquals("VISION_PRIVACY_CAPABILITY_MISSING", missing.getReason());
    assertDoesNotThrow(
        () ->
            ChallengeAutomationApplicationService.validateVisionCapabilities(
                Map.of("screenshot-ocr-actions-v1", true, "local-ocr-pii-gate-v1", true)));
  }

  @Test
  void acceptsOnlyFullyRedactedHashOnlyPrivacyAttestation() {
    assertDoesNotThrow(
        () ->
            ChallengeAutomationApplicationService.validatePrivacyAttestation(
                "tesseract-pii-v1", "a".repeat(64), 0, 0, 0));
    assertDoesNotThrow(
        () ->
            ChallengeAutomationApplicationService.validatePrivacyAttestation(
                "tesseract-pii-v1", "a".repeat(64), 2, 1, 0));
    for (var invalid :
        new Object[][] {
          {"other", "a".repeat(64), 0, 0, 0},
          {"tesseract-pii-v1", "raw text", 0, 0, 0},
          {"tesseract-pii-v1", "a".repeat(64), 1, 0, 0},
          {"tesseract-pii-v1", "a".repeat(64), 1, 1, 1}
        }) {
      var rejected =
          assertThrows(
              ChallengeAutomationApplicationService.ChallengeAutomationRejectedException.class,
              () ->
                  ChallengeAutomationApplicationService.validatePrivacyAttestation(
                      (String) invalid[0],
                      (String) invalid[1],
                      (Integer) invalid[2],
                      (Integer) invalid[3],
                      (Integer) invalid[4]));
      assertEquals("VISION_PRIVACY_ATTESTATION_INVALID", rejected.getReason());
    }
  }

  @Test
  void mapsCropNormalizedVisionCoordinatesBackToViewportCoordinates() {
    assertEquals(
        new BigDecimal("0.25"),
        ChallengeAutomationApplicationService.normalizedViewportCoordinate(
            100, 200, new BigDecimal("0.5"), 800));
    assertEquals(
        0,
        ChallengeAutomationApplicationService.normalizedViewportCoordinate(
                700, 200, BigDecimal.ONE, 800)
            .compareTo(BigDecimal.ONE));
  }
}
