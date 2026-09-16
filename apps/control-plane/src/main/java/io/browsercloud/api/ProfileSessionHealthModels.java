package io.browsercloud.api;

import java.time.Instant;
import java.util.List;

public final class ProfileSessionHealthModels {
  private ProfileSessionHealthModels() {}

  public record ProfileSessionHealthSummary(
      String state, int siteCount, Instant checkedAt, Instant freshUntil) {}

  public record ProfileSiteSessionHealthView(
      String healthId,
      String profileId,
      String siteOrigin,
      String applicationId,
      String state,
      String reasonCode,
      String sourceSessionId,
      long contextEpoch,
      long stateVersion,
      Instant checkedAt,
      Instant freshUntil,
      Instant authenticatedAt,
      Instant reauthRequiredAt) {}

  public record ProfileSiteSessionHealthListResponse(
      ProfileSessionHealthSummary summary, List<ProfileSiteSessionHealthView> items, int total) {}
}
