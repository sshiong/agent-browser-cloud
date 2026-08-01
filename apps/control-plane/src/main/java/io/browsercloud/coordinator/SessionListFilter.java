package io.browsercloud.coordinator;

import java.util.List;

/** Tenant-scoped Session list dimensions applied by PostgreSQL before pagination. */
public record SessionListFilter(String groupId, List<String> tagIds, TagMatch tagMatch) {

  public SessionListFilter {
    tagIds = tagIds == null ? List.of() : tagIds.stream().distinct().toList();
    tagMatch = tagMatch == null ? TagMatch.ANY : tagMatch;
  }

  public static SessionListFilter empty() {
    return new SessionListFilter(null, List.of(), TagMatch.ANY);
  }

  public boolean hasWorkspaceDimensions() {
    return (groupId != null && !groupId.isBlank()) || !tagIds.isEmpty();
  }

  public enum TagMatch {
    ANY,
    ALL
  }
}
