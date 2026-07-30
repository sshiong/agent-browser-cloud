package io.browsercloud.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.GlobalSearchModels.GlobalSearchResponse;
import io.browsercloud.api.GlobalSearchModels.GlobalSearchResult;
import io.browsercloud.api.GlobalSearchModels.SearchResourceType;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import io.browsercloud.persistence.RuntimeBuildEntity;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceGroupEntity;
import io.browsercloud.persistence.WorkspaceGroupJpaRepository;
import io.browsercloud.persistence.WorkspaceTagEntity;
import io.browsercloud.persistence.WorkspaceTagJpaRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlobalSearchApplicationService {

  private static final Set<SearchResourceType> ALL_TYPES =
      Set.copyOf(EnumSet.allOf(SearchResourceType.class));

  private final SessionJpaRepository sessions;
  private final ProfileJpaRepository profiles;
  private final WorkspaceGroupJpaRepository groups;
  private final WorkspaceTagJpaRepository tags;
  private final RuntimeBuildJpaRepository runtimes;
  private final BrowserNodeJpaRepository nodes;
  private final ObjectMapper objectMapper;

  public GlobalSearchApplicationService(
      SessionJpaRepository sessions,
      ProfileJpaRepository profiles,
      WorkspaceGroupJpaRepository groups,
      WorkspaceTagJpaRepository tags,
      RuntimeBuildJpaRepository runtimes,
      BrowserNodeJpaRepository nodes,
      ObjectMapper objectMapper) {
    this.sessions = sessions;
    this.profiles = profiles;
    this.groups = groups;
    this.tags = tags;
    this.runtimes = runtimes;
    this.nodes = nodes;
    this.objectMapper = objectMapper;
  }

  @Transactional(readOnly = true)
  public GlobalSearchResponse search(
      String tenantId,
      String rawQuery,
      Set<SearchResourceType> requestedTypes,
      int requestedLimit,
      boolean canReadAdminResources) {
    var query = rawQuery == null ? "" : rawQuery.strip();
    if (query.length() < 2 || query.length() > 128) {
      throw new IllegalArgumentException("Global search query must contain 2 to 128 characters");
    }
    var types =
        requestedTypes == null || requestedTypes.isEmpty() ? ALL_TYPES : Set.copyOf(requestedTypes);
    var repositoryQuery = escapeLikeLiteral(query);
    int limit = Math.max(1, Math.min(requestedLimit, 50));
    int fetchLimit = limit + 1;
    var pageable = PageRequest.of(0, fetchLimit, Sort.unsorted());
    var candidates = new ArrayList<RankedResult>();
    boolean sourceTruncated = false;

    if (types.contains(SearchResourceType.SESSION)) {
      var page = sessions.searchAllByTenantId(tenantId, repositoryQuery, pageable);
      sourceTruncated |= page.hasNext() || page.getNumberOfElements() > limit;
      page.getContent().stream()
          .map(this::sessionResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }
    if (types.contains(SearchResourceType.PROFILE)) {
      var items = profiles.searchAllByTenantId(tenantId, repositoryQuery, pageable);
      sourceTruncated |= items.size() > limit;
      items.stream()
          .map(this::profileResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }
    if (types.contains(SearchResourceType.GROUP)) {
      var items = groups.searchAllByTenantId(tenantId, repositoryQuery, pageable);
      sourceTruncated |= items.size() > limit;
      items.stream()
          .map(this::groupResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }
    if (types.contains(SearchResourceType.TAG)) {
      var items = tags.searchAllByTenantId(tenantId, repositoryQuery, pageable);
      sourceTruncated |= items.size() > limit;
      items.stream()
          .map(this::tagResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }
    if (types.contains(SearchResourceType.RUNTIME)) {
      var items = runtimes.searchAll(repositoryQuery, pageable);
      sourceTruncated |= items.size() > limit;
      items.stream()
          .map(this::runtimeResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }
    if (types.contains(SearchResourceType.NODE) && canReadAdminResources) {
      var items = nodes.searchAll(repositoryQuery, pageable);
      sourceTruncated |= items.size() > limit;
      items.stream()
          .map(this::nodeResult)
          .map(result -> rank(query, result))
          .forEach(candidates::add);
    }

    candidates.sort(
        Comparator.comparingInt(RankedResult::score)
            .thenComparing(
                result -> result.result().updatedAt(),
                Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(result -> result.result().resourceId()));
    boolean truncated = sourceTruncated || candidates.size() > limit;
    var items = candidates.stream().limit(limit).map(RankedResult::result).toList();
    return new GlobalSearchResponse(query, items, limit, truncated);
  }

  private GlobalSearchResult sessionResult(SessionEntity session) {
    return new GlobalSearchResult(
        SearchResourceType.SESSION,
        session.getId(),
        sessionDisplayName(session),
        session.getProfileId(),
        session.getState(),
        session.getRegion(),
        session.getUpdatedAt());
  }

  private GlobalSearchResult profileResult(ProfileEntity profile) {
    return new GlobalSearchResult(
        SearchResourceType.PROFILE,
        profile.getProfileId(),
        profile.getName(),
        profile.getDescription(),
        profile.getState(),
        null,
        profile.getUpdatedAt());
  }

  private GlobalSearchResult groupResult(WorkspaceGroupEntity group) {
    return new GlobalSearchResult(
        SearchResourceType.GROUP,
        group.getGroupId(),
        group.getName(),
        group.getDescription(),
        "ACTIVE",
        null,
        group.getUpdatedAt());
  }

  private GlobalSearchResult tagResult(WorkspaceTagEntity tag) {
    return new GlobalSearchResult(
        SearchResourceType.TAG,
        tag.getTagId(),
        tag.getName(),
        tag.getDescription(),
        "ACTIVE",
        null,
        tag.getUpdatedAt());
  }

  private GlobalSearchResult runtimeResult(RuntimeBuildEntity runtime) {
    return new GlobalSearchResult(
        SearchResourceType.RUNTIME,
        runtime.getBuildId(),
        runtime.getBuildId(),
        runtime.getEngine() + " " + runtime.getVersion() + " · " + runtime.getPlatform(),
        runtime.getReleaseChannel(),
        null,
        runtime.getCreatedAt());
  }

  private GlobalSearchResult nodeResult(BrowserNodeEntity node) {
    return new GlobalSearchResult(
        SearchResourceType.NODE,
        node.getNodeId(),
        node.getNodeId(),
        node.getAdmissionState() + " · " + node.getPressureState(),
        node.getLifecycleState(),
        node.getRegion(),
        node.getUpdatedAt());
  }

  private String sessionDisplayName(SessionEntity session) {
    try {
      var root = objectMapper.readTree(session.getMetadata());
      var displayName = root == null ? null : root.path("displayName").asText(null);
      return displayName == null || displayName.isBlank() ? session.getId() : displayName.strip();
    } catch (Exception ignored) {
      return session.getId();
    }
  }

  private static RankedResult rank(String query, GlobalSearchResult result) {
    var needle = query.toLowerCase(Locale.ROOT);
    var id = result.resourceId().toLowerCase(Locale.ROOT);
    var title = result.title().toLowerCase(Locale.ROOT);
    int score;
    if (id.equals(needle) || title.equals(needle)) {
      score = 0;
    } else if (id.startsWith(needle) || title.startsWith(needle)) {
      score = 1;
    } else if (id.contains(needle) || title.contains(needle)) {
      score = 2;
    } else {
      score = 3;
    }
    return new RankedResult(result, score);
  }

  private static String escapeLikeLiteral(String query) {
    return query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private record RankedResult(GlobalSearchResult result, int score) {}
}
