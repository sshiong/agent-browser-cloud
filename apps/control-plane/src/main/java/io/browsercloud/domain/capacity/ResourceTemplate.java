package io.browsercloud.domain.capacity;

import io.browsercloud.domain.session.ResourceClass;
import java.util.Arrays;

/**
 * Public-safe name for an internal scheduler resource class.
 *
 * <p>The L0-L5 enum remains an internal ordering/compatibility detail. API, SDK and enterprise
 * pricing surfaces expose template names instead, so Native OS can never be confused with a
 * resource tier.
 */
public enum ResourceTemplate {
  SUSPENDED("suspended-v1", ResourceClass.L0),
  STANDARD_LITE("standard-lite-v1", ResourceClass.L1),
  STANDARD("standard-v1", ResourceClass.L2),
  INTERACTIVE("interactive-v1", ResourceClass.L3),
  HEAVY("heavy-v1", ResourceClass.L4),
  NATIVE_STANDARD("native-standard-v1", ResourceClass.L5);

  private final String id;
  private final ResourceClass legacyClass;

  ResourceTemplate(String id, ResourceClass legacyClass) {
    this.id = id;
    this.legacyClass = legacyClass;
  }

  public String id() {
    return id;
  }

  public ResourceClass legacyClass() {
    return legacyClass;
  }

  public static ResourceTemplate from(ResourceClass resourceClass) {
    return Arrays.stream(values())
        .filter(template -> template.legacyClass == resourceClass)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unsupported internal resource class"));
  }

  public static ResourceTemplate parse(String templateId) {
    if (templateId == null) {
      throw new IllegalArgumentException("resource template is required");
    }
    return Arrays.stream(values())
        .filter(template -> template.id.equals(templateId))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("unsupported resource template"));
  }
}
