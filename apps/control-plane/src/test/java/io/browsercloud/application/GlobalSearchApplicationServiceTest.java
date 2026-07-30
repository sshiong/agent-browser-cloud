package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.GlobalSearchModels.SearchResourceType;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import io.browsercloud.persistence.RuntimeBuildJpaRepository;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import io.browsercloud.persistence.WorkspaceGroupJpaRepository;
import io.browsercloud.persistence.WorkspaceTagJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class GlobalSearchApplicationServiceTest {

  @Mock private SessionJpaRepository sessions;
  @Mock private ProfileJpaRepository profiles;
  @Mock private WorkspaceGroupJpaRepository groups;
  @Mock private WorkspaceTagJpaRepository tags;
  @Mock private RuntimeBuildJpaRepository runtimes;
  @Mock private BrowserNodeJpaRepository nodes;

  private GlobalSearchApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new GlobalSearchApplicationService(
            sessions, profiles, groups, tags, runtimes, nodes, new ObjectMapper());
  }

  @Test
  void searchesOnlyRequestedTypesWithinTheCurrentTenantAndRanksExactMatchesFirst() {
    var now = Instant.parse("2026-07-30T00:00:00Z");
    var session =
        new SessionEntity(
            "ses_1234567890abcdef",
            "tenant-a",
            "profile-crm",
            "singapore",
            "L2",
            "RUNNING",
            "",
            "{\"displayName\":\"CRM Singapore\"}",
            true,
            AgentPolicy.BALANCED,
            "[]",
            now);
    var profile =
        new ProfileEntity(
            "profile-crm", "tenant-a", "CRM", "Primary CRM profile", "/profiles/crm", now);
    when(sessions.searchAllByTenantId(eq("tenant-a"), eq("CRM"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(session)));
    when(profiles.searchAllByTenantId(eq("tenant-a"), eq("CRM"), any(Pageable.class)))
        .thenReturn(List.of(profile));

    var response =
        service.search(
            "tenant-a",
            " CRM ",
            Set.of(SearchResourceType.SESSION, SearchResourceType.PROFILE),
            10,
            false);

    assertThat(response.query()).isEqualTo("CRM");
    assertThat(response.items())
        .extracting("resourceType")
        .containsExactly(SearchResourceType.PROFILE, SearchResourceType.SESSION);
    assertThat(response.items().get(1).title()).isEqualTo("CRM Singapore");
    assertThat(response.truncated()).isFalse();
    verify(groups, never()).searchAllByTenantId(any(), any(), any());
    verify(nodes, never()).searchAll(any(), any());
  }

  @Test
  void capsTheCombinedResultAndReportsTruncationWithoutUnboundedRepositoryReads() {
    var first = mock(ProfileEntity.class);
    var second = mock(ProfileEntity.class);
    when(first.getProfileId()).thenReturn("profile-alpha");
    when(first.getName()).thenReturn("Alpha");
    when(first.getState()).thenReturn("ACTIVE");
    when(first.getUpdatedAt()).thenReturn(Instant.parse("2026-07-30T00:00:00Z"));
    when(second.getProfileId()).thenReturn("profile-alpha-secondary");
    when(second.getName()).thenReturn("Alpha Secondary");
    when(second.getState()).thenReturn("ACTIVE");
    when(second.getUpdatedAt()).thenReturn(Instant.parse("2026-07-29T00:00:00Z"));
    when(profiles.searchAllByTenantId(eq("tenant-a"), eq("alpha"), any(Pageable.class)))
        .thenAnswer(
            invocation -> {
              Pageable pageable = invocation.getArgument(2);
              assertThat(pageable.getPageSize()).isEqualTo(2);
              return List.of(first, second);
            });

    var response =
        service.search("tenant-a", "alpha", Set.of(SearchResourceType.PROFILE), 1, false);

    assertThat(response.items()).hasSize(1);
    assertThat(response.truncated()).isTrue();
  }

  @Test
  void omitsAdminOnlyNodeResultsForReadOnlyIdentities() {
    var response = service.search("tenant-a", "node", Set.of(SearchResourceType.NODE), 10, false);

    assertThat(response.items()).isEmpty();
    verify(nodes, never()).searchAll(any(), any());
  }

  @Test
  void treatsSqlLikeWildcardsAsLiteralSearchCharacters() {
    when(profiles.searchAllByTenantId(eq("tenant-a"), eq("\\%\\_"), any(Pageable.class)))
        .thenReturn(List.of());

    var response = service.search("tenant-a", "%_", Set.of(SearchResourceType.PROFILE), 10, false);

    assertThat(response.query()).isEqualTo("%_");
    assertThat(response.items()).isEmpty();
  }
}
