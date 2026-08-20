package io.browsercloud.coordinator;

import java.math.BigDecimal;
import java.util.List;

/** Immutable Browser identity inputs resolved from PostgreSQL at every Runtime start. */
public record BrowserIdentitySpec(
    String userAgent,
    String timezone,
    String locale,
    List<String> languages,
    String webRtcPolicy,
    String dnsPolicy,
    Integer viewportWidth,
    Integer viewportHeight,
    Integer screenWidth,
    Integer screenHeight,
    BigDecimal deviceScaleFactor,
    String fingerprintProfile,
    String operatingSystemProfile,
    long version,
    String specHash) {

  public BrowserIdentitySpec {
    languages = languages == null ? List.of() : List.copyOf(languages);
  }

  public static BrowserIdentitySpec empty() {
    return new BrowserIdentitySpec(
        null, null, null, List.of(), "DEFAULT", "SYSTEM", null, null, null, null, null, null, null,
        0, "");
  }
}
