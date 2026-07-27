package io.browsercloud.coordinator;

/** Safely checkpoints and stops a running Browser while preserving the Session for restart. */
public record HibernateSession(String sessionId, String reason) implements SessionCommand {}
