package io.browsercloud.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.browsercloud.domain.session.ResourceClass;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class BrowserPlacementEntityTest {

  @Test
  void keepsCurrentMediaEncoderSlotsInsideTheReservedPlacementLimit() {
    var placement = mediaPlacement(2);
    placement.activate(Instant.parse("2026-07-28T00:00:01Z"));

    placement.applyResourceAdjustment(
        2_000, 1_536, 3_072, 384, 20, 75, 0, 100, 1, true, true, "[]");

    assertThat(placement.getMediaSlots()).isEqualTo(2);
    assertThat(placement.getMediaEncoderSlots()).isEqualTo(1);
    assertThat(placement.isBackgroundTabsFrozen()).isTrue();
    assertThat(placement.isNewTabsBlocked()).isTrue();
    assertThatThrownBy(
            () ->
                placement.applyResourceAdjustment(
                    2_000, 1_536, 3_072, 384, 20, 75, 0, 100, 3, true, true, "[]"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Media Encoder Slot");
  }

  private static BrowserPlacementEntity mediaPlacement(int reservedSlots) {
    return new BrowserPlacementEntity(
        "ses_media_slots",
        "tenant-test",
        "node-test",
        ResourceClass.L4,
        ResourceClass.L4,
        "[]",
        0,
        2_000,
        1_536,
        3_072,
        384,
        20,
        false,
        false,
        false,
        false,
        true,
        reservedSlots,
        4_000,
        100,
        "[]",
        Instant.parse("2026-07-28T00:00:00Z"));
  }
}
