package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorOwnershipService;
import io.browsercloud.coordinator.SessionListFilter;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.persistence.SessionContextEntity;
import io.browsercloud.persistence.SessionEntity;
import io.browsercloud.persistence.SessionJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class JpaSessionRepositoryTest {

  @Mock private SessionJpaRepository sessions;
  @Mock private SessionContextJpaRepository contexts;
  @Mock private CoordinatorOwnershipService ownership;
  @Mock private SessionFilteredQueryRepository filteredQueries;

  private JpaSessionRepository repository;

  @BeforeEach
  void setUp() {
    repository =
        new JpaSessionRepository(
            sessions,
            contexts,
            new ObjectMapper().findAndRegisterModules(),
            ownership,
            filteredQueries);
  }

  @Test
  void listsLatestContextsAndOwnershipTermsWithTwoBatchQueries() {
    var now = Instant.parse("2026-07-29T00:00:00Z");
    var first = session("ses_first", "First", now);
    var second = session("ses_second", "Second", now.minusSeconds(30));
    var firstContext = context(first.getId(), 3, 2, "node-first", now);
    var secondContext = context(second.getId(), 5, 4, "node-second", now);
    var sessionIds = List.of(first.getId(), second.getId());
    when(sessions.findAllByTenantId(eq("tenant-test"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(first, second)));
    when(contexts.findLatestBySessionIds(sessionIds))
        .thenReturn(List.of(firstContext, secondContext));
    when(ownership.getCurrentTerms(sessionIds)).thenReturn(Map.of(first.getId(), 7L));

    var result = repository.listByTenant("tenant-test", null, "", SessionListFilter.empty(), 20, 0);

    assertThat(result).hasSize(2);
    assertThat(result.getFirst().context().nodeId()).isEqualTo("node-first");
    assertThat(result.getFirst().context().contextEpoch()).isEqualTo(3);
    assertThat(result.getFirst().context().coordinatorTerm()).isEqualTo(7);
    assertThat(result.get(1).context().coordinatorTerm()).isEqualTo(4);
    verify(contexts, never())
        .findTopBySessionIdOrderByContextEpochDesc(org.mockito.ArgumentMatchers.anyString());
    verify(ownership, never()).getCurrentTerm(org.mockito.ArgumentMatchers.anyString());
  }

  private SessionEntity session(String sessionId, String displayName, Instant createdAt) {
    return new SessionEntity(
        sessionId,
        "tenant-test",
        "profile-test",
        "local",
        "L2",
        "CREATED",
        "",
        "{\"displayName\":\"" + displayName + "\"}",
        true,
        AgentPolicy.BALANCED,
        "[]",
        createdAt);
  }

  private SessionContextEntity context(
      String sessionId,
      long contextEpoch,
      long coordinatorTerm,
      String nodeId,
      Instant committedAt) {
    var context = new SessionContextEntity();
    context.setSessionId(sessionId);
    context.setContextEpoch(contextEpoch);
    context.setCoordinatorTerm(coordinatorTerm);
    context.setNodeId(nodeId);
    context.setRuntimeBuildId("runtime-test");
    context.setNetworkRevision(1);
    context.setBrowserGeneration(2);
    context.setResourceClass("L2");
    context.setPolicyHash("");
    context.setCommittedAt(committedAt);
    return context;
  }
}
