package io.browsercloud.application;

import io.browsercloud.coordinator.NodeEvent;
import java.net.URI;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Default recovery validator for the generic platform.
 *
 * <p>Tenant/application validators can be added later; unknown business state remains degraded
 * instead of being promoted to ready.
 */
@Component
public class BusinessRecoveryValidator {

  public Verdict validate(NodeEvent.StateUpdated state) {
    if (state.url() == null || state.url().isBlank()) {
      return new Verdict("BUSINESS_RECOVERY_UNKNOWN", false);
    }
    var quality = state.stateQuality();
    if ("HUMAN_REQUIRED".equals(quality) || "VISION_REQUIRED".equals(quality)) {
      return new Verdict("MANUAL_RECOVERY_REQUIRED", false);
    }
    if (!"COMPLETE".equals(quality) && !"DEPTH_LIMITED".equals(quality)) {
      return new Verdict("DEGRADED_STATE_QUALITY:" + quality, false);
    }
    try {
      var path =
          (URI.create(state.url()).getPath() == null ? "" : URI.create(state.url()).getPath())
              .toLowerCase(Locale.ROOT);
      if (path.contains("login")
          || path.contains("signin")
          || path.contains("sign-in")
          || path.contains("authenticate")) {
        return new Verdict("LOGIN_REQUIRED", false);
      }
    } catch (IllegalArgumentException ignored) {
      return new Verdict("INVALID_RECOVERED_URL", false);
    }
    return new Verdict("READY_DEFAULT_BROWSER_STATE_VALIDATOR", true);
  }

  public record Verdict(String code, boolean ready) {}
}
