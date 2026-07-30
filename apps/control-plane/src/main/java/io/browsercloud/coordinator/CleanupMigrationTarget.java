package io.browsercloud.coordinator;

/** Fenced stop of a possibly-started migration target before another target may be selected. */
public record CleanupMigrationTarget(String sessionId, String reason) implements SessionCommand {}
