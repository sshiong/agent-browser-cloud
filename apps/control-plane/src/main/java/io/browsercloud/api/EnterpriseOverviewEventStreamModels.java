package io.browsercloud.api;

import java.time.Instant;

/** Payload-free control and change frames for the resumable Enterprise Overview stream. */
public final class EnterpriseOverviewEventStreamModels {

  private EnterpriseOverviewEventStreamModels() {}

  public record EnterpriseOverviewStreamControl(
      long cursor, boolean resetRequired, Instant connectedAt) {}

  public record EnterpriseOverviewStreamChange(
      long sequence, String changeType, Instant occurredAt, boolean replayed) {}
}
