package io.browsercloud.application;

import static io.browsercloud.api.BusinessRecoveryModels.Verdict;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.browsercloud.persistence.ProfileEntity;
import io.browsercloud.persistence.ProfileJpaRepository;
import io.browsercloud.persistence.ProfileSiteSessionHealthEntity;
import io.browsercloud.persistence.ProfileSiteSessionHealthJpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfileSessionHealthApplicationServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

  @Mock private ProfileSiteSessionHealthJpaRepository repository;
  @Mock private ProfileJpaRepository profiles;
  private ProfileSessionHealthApplicationService service;
  private AtomicReference<ProfileSiteSessionHealthEntity> stored;

  @BeforeEach
  void setUp() {
    service = new ProfileSessionHealthApplicationService(repository, profiles);
    stored = new AtomicReference<>();
    when(repository.findByTenantIdAndProfileIdAndSiteOrigin(any(), any(), any()))
        .thenAnswer(invocation -> Optional.ofNullable(stored.get()));
    when(repository.save(any()))
        .thenAnswer(
            invocation -> {
              stored.set(invocation.getArgument(0));
              return stored.get();
            });
    when(repository.findAllByTenantIdAndProfileIdOrderByUpdatedAtDesc("tenant-a", "profile-a"))
        .thenAnswer(invocation -> stored.get() == null ? List.of() : List.of(stored.get()));
    when(profiles.findById("profile-a"))
        .thenReturn(
            Optional.of(
                new ProfileEntity("profile-a", "tenant-a", "Profile A", null, "profiles/a", NOW)));
    when(profiles.findForUpdate("profile-a"))
        .thenReturn(
            Optional.of(
                new ProfileEntity("profile-a", "tenant-a", "Profile A", null, "profiles/a", NOW)));
  }

  @Test
  void reauthPersistsPastFreshnessUntilAReadyObservationClearsIt() {
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        "crm",
        "HTTPS://CRM.EXAMPLE.TEST:443/sign-in",
        7,
        12,
        Verdict.LOGIN_REQUIRED,
        NOW);

    var reauth = service.list("tenant-a", "profile-a", NOW.plusSeconds(3600));
    assertThat(reauth.summary().state()).isEqualTo("REAUTH_REQUIRED");
    assertThat(reauth.items())
        .singleElement()
        .satisfies(
            item -> {
              assertThat(item.siteOrigin()).isEqualTo("https://crm.example.test");
              assertThat(item.reauthRequiredAt()).isEqualTo(NOW);
              assertThat(item.state()).isEqualTo("REAUTH_REQUIRED");
            });

    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        "crm",
        "https://crm.example.test/customers",
        8,
        13,
        Verdict.READY,
        NOW.plusSeconds(3601));

    var healthy = service.list("tenant-a", "profile-a", NOW.plusSeconds(3602));
    assertThat(healthy.summary().state()).isEqualTo("HEALTHY");
    assertThat(healthy.items().getFirst().reauthRequiredAt()).isNull();
    assertThat(healthy.items().getFirst().authenticatedAt()).isEqualTo(NOW.plusSeconds(3601));
  }

  @Test
  void healthyEvidenceBecomesStaleWithoutChangingCheckpointState() {
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        null,
        "https://mail.example.test/inbox",
        2,
        5,
        Verdict.READY_WITH_WARNING,
        NOW);

    assertThat(service.list("tenant-a", "profile-a", NOW.plusSeconds(901)).summary().state())
        .isEqualTo("STALE");
  }

  @Test
  void olderStateCannotClearNewerReauthenticationEvidence() {
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        "crm",
        "https://crm.example.test/sign-in",
        8,
        20,
        Verdict.LOGIN_REQUIRED,
        NOW);
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        "crm",
        "https://crm.example.test/customers",
        8,
        19,
        Verdict.READY,
        NOW.plusSeconds(1));

    assertThat(service.list("tenant-a", "profile-a", NOW.plusSeconds(2)).summary().state())
        .isEqualTo("REAUTH_REQUIRED");
  }

  @Test
  void newerSessionCanRefreshHealthDespiteItsLowerLocalEpoch() {
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_1234567890abcdef",
        "crm",
        "https://crm.example.test/sign-in",
        8,
        20,
        Verdict.LOGIN_REQUIRED,
        NOW);
    service.observe(
        "tenant-a",
        "profile-a",
        "ses_fedcba0987654321",
        "crm",
        "https://crm.example.test/customers",
        1,
        2,
        Verdict.READY,
        NOW.plusSeconds(1));

    assertThat(service.list("tenant-a", "profile-a", NOW.plusSeconds(2)).summary().state())
        .isEqualTo("HEALTHY");
  }
}
