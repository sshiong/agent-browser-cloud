package io.browsercloud.api;

import java.time.Instant;

/** Payload-free control and change frames for the resumable tenant audit stream. */
public final class AuditEventStreamModels {

  private AuditEventStreamModels() {}

  public record AuditEventStreamControl(long cursor, boolean resetRequired, Instant connectedAt) {}

  public record AuditEventStreamChange(long sequence, Instant occurredAt, boolean replayed) {}
}
