package io.browsercloud.coordinator;

/** 结束人工接管并强制释放输入、重新同步 Browser State。 */
public record ReleaseHumanTakeover(String sessionId, String userId) implements SessionCommand {}
