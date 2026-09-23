package io.browsercloud.application;

import static io.browsercloud.api.SessionEnvironmentModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.CreateSessionRequest;
import io.browsercloud.api.CreateSessionResponse;
import io.browsercloud.api.ResourcePolicyRequest;
import io.browsercloud.api.SessionIdentityModels;
import io.browsercloud.coordinator.SessionDescriptor;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.agent.AgentPolicy;
import io.browsercloud.domain.resource.ResourcePolicyMode;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.domain.session.SessionState;
import io.browsercloud.persistence.*;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionEnvironmentApplicationServiceTest {
  private static final String SESSION_ID = "ses_1234567890abcdef";
  private static final String TENANT_ID = "tenant-a";
  private static final Instant NOW = Instant.parse("2026-09-23T00:00:00Z");

  @Mock private SessionRepository sessions;
  @Mock private SessionJpaRepository sessionEntities;
  @Mock private SessionResourcePolicyJpaRepository policies;
  @Mock private SessionResourceDemandJpaRepository demands;
  @Mock private SessionTagAssignmentJpaRepository tags;
  @Mock private SessionApplicationBindingJpaRepository applicationBindings;
  @Mock private SessionProxyBindingAssignmentJpaRepository proxyAssignments;
  @Mock private SessionIdentityApplicationService identities;
  @Mock private ProfileApplicationService profiles;
  @Mock private SessionApplicationService sessionApplicationService;
  @Mock private AuditApplicationService audit;

  private SessionEnvironmentApplicationService service;

  @BeforeEach
  void setUp() {
    service =
        new SessionEnvironmentApplicationService(
            sessions,
            sessionEntities,
            policies,
            demands,
            tags,
            applicationBindings,
            proxyAssignments,
            identities,
            profiles,
            sessionApplicationService,
            audit,
            new ObjectMapper().findAndRegisterModules());
    stubSource();
  }

  @Test
  void exportProducesImportCompatibleSecretFreeManifest() {
    var result = service.exportConfiguration(SESSION_ID, TENANT_ID, "operator-a", "request-a");

    assertThat(result.manifestHash()).matches("[a-f0-9]{64}");
    assertThat(result.excludedData())
        .contains("cookiesAndSiteStorage", "proxyCredentials", "operationsAndExecutionHistory");
    var specification = result.manifest().environments().getFirst();
    assertThat(specification.displayName()).isEqualTo("Primary");
    assertThat(specification.description()).isEqualTo("Source environment");
    assertThat(specification.proxyBindingProfileId()).isEqualTo("pbind_1234567890abcdef");
    assertThat(specification.identitySpec().timezone()).isEqualTo("Asia/Shanghai");
    assertThat(specification.tagIds()).containsExactly("tag_1234567890abcdef");
    verify(audit).append(any());
  }

  @Test
  void cloneUsesDeterministicEmptyProfileAndPreservesGovernedConfiguration() {
    when(sessionApplicationService.create(
            any(), eq("clone-key"), anyString(), anyString(), eq(false)))
        .thenReturn(
            new CreateSessionResponse(
                "ses_fedcba0987654321", "op_1234567890abcdef", "CREATED", null, null));

    var result =
        service.cloneConfiguration(
            SESSION_ID,
            TENANT_ID,
            "operator-a",
            false,
            "clone-key",
            "request-a",
            new CloneEnvironmentRequest("Primary copy", CloneProfileMode.NEW_EMPTY_PROFILE));

    var request = ArgumentCaptor.forClass(CreateSessionRequest.class);
    verify(sessionApplicationService)
        .create(request.capture(), eq("clone-key"), eq("operator-a"), eq("request-a"), eq(false));
    assertThat(result.targetProfileId())
        .startsWith("profile_clone_")
        .isNotEqualTo("profile-source");
    assertThat(request.getValue().profileId()).isEqualTo(result.targetProfileId());
    assertThat(request.getValue().proxyBindingProfileId()).isEqualTo("pbind_1234567890abcdef");
    assertThat(request.getValue().identitySpec().timezone()).isEqualTo("Asia/Shanghai");
    assertThat(request.getValue().metadata())
        .containsEntry("displayName", "Primary copy")
        .containsEntry("clonedFromSessionId", SESSION_ID);
    verify(profiles)
        .ensureExists(
            TENANT_ID,
            result.targetProfileId(),
            "Primary copy Profile",
            "Empty Profile created for a configuration clone of " + SESSION_ID);
  }

  @Test
  void cloneOnlyReusesLoginStateWhenExplicitlyRequested() {
    when(sessionApplicationService.create(
            any(), anyString(), anyString(), anyString(), anyBoolean()))
        .thenReturn(
            new CreateSessionResponse(
                "ses_fedcba0987654321", "op_1234567890abcdef", "CREATED", null, null));

    var result =
        service.cloneConfiguration(
            SESSION_ID,
            TENANT_ID,
            "operator-a",
            false,
            "reuse-key",
            "request-a",
            new CloneEnvironmentRequest("Shared login", CloneProfileMode.REUSE_SOURCE_PROFILE));

    assertThat(result.targetProfileId()).isEqualTo("profile-source");
  }

  private void stubSource() {
    var context =
        new SessionContext(
            SESSION_ID,
            TENANT_ID,
            "profile-source",
            null,
            "runtime-stable",
            null,
            null,
            0,
            0,
            0,
            0,
            ResourceClass.L2,
            SessionState.CREATED,
            "",
            NOW,
            NOW);
    when(sessions.describe(SESSION_ID))
        .thenReturn(
            new SessionDescriptor(
                context,
                "singapore",
                "Primary",
                "grp_1234567890abcdef",
                true,
                AgentPolicy.BALANCED,
                List.of("extension.example")));
    var entity =
        new SessionEntity(
            SESSION_ID,
            TENANT_ID,
            "profile-source",
            "singapore",
            "L2",
            "CREATED",
            "",
            "{\"displayName\":\"Primary\",\"description\":\"Source environment\",\"private\":\"excluded\"}",
            true,
            AgentPolicy.BALANCED,
            "[\"extension.example\"]",
            NOW);
    when(sessionEntities.findById(SESSION_ID)).thenReturn(Optional.of(entity));
    var policy =
        SessionResourcePolicyEntity.create(
            SESSION_ID,
            TENANT_ID,
            new ResourcePolicyRequest(
                ResourcePolicyMode.AUTO,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null),
            NOW);
    when(policies.findBySessionIdAndTenantId(SESSION_ID, TENANT_ID))
        .thenReturn(Optional.of(policy));
    when(demands.findById(SESSION_ID))
        .thenReturn(
            Optional.of(
                new SessionResourceDemandEntity(
                    SESSION_ID,
                    TENANT_ID,
                    ResourceClass.L2,
                    2,
                    30,
                    true,
                    false,
                    false,
                    0,
                    0,
                    false,
                    "[\"extension.example\"]",
                    NOW)));
    when(tags.findAllByTenantIdAndSessionIdOrderByAssignedAtAsc(TENANT_ID, SESSION_ID))
        .thenReturn(
            List.of(
                new SessionTagAssignmentEntity(
                    "sta_1234567890abcdef",
                    TENANT_ID,
                    SESSION_ID,
                    "tag_1234567890abcdef",
                    "operator-a",
                    NOW)));
    when(applicationBindings.findBySessionIdAndTenantId(SESSION_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new SessionApplicationBindingEntity(
                    SESSION_ID, TENANT_ID, "crm", "contract-a", NOW)));
    when(proxyAssignments.findBySessionIdAndTenantId(SESSION_ID, TENANT_ID))
        .thenReturn(
            Optional.of(
                new SessionProxyBindingAssignmentEntity(
                    SESSION_ID,
                    TENANT_ID,
                    "pbind_1234567890abcdef",
                    1,
                    "provider-a",
                    "singapore",
                    "203.0.113.10",
                    "secret/ref",
                    "operator-a",
                    NOW)));
    var identity =
        new SessionIdentityModels.SessionIdentitySpecRequest(
            null,
            "Asia/Shanghai",
            "zh-CN",
            List.of("zh-CN"),
            null,
            null,
            1280,
            720,
            1920,
            1080,
            null,
            null,
            null);
    when(identities.get(SESSION_ID, TENANT_ID))
        .thenReturn(
            new SessionIdentityModels.SessionIdentitySpecView(
                SESSION_ID, 1, "a".repeat(64), true, identity, NOW, NOW));
  }
}
