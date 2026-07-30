package io.browsercloud.application;

import static io.browsercloud.api.UserPreferenceModels.ThemeMode.DARK;
import static io.browsercloud.api.UserPreferenceModels.ThemeMode.LIGHT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.browsercloud.application.UserPreferenceStore.StoredUserPreferences;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserPreferenceApplicationServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-31T03:00:00Z");

  @Mock private UserPreferenceStore store;
  @Mock private AuditApplicationService audit;

  private UserPreferenceApplicationService service;

  @BeforeEach
  void setUp() {
    service = new UserPreferenceApplicationService(store, audit, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void resolvesSystemDefaultWithoutPersistedPreference() {
    when(store.find("tenant-a", "actor-a")).thenReturn(Optional.empty());

    var result = service.get("tenant-a", "actor-a");

    assertThat(result.themeMode().name()).isEqualTo("SYSTEM");
    assertThat(result.source()).isEqualTo("SYSTEM_DEFAULT");
    assertThat(result.version()).isZero();
    assertThat(result.updatedAt()).isNull();
  }

  @Test
  void persistsAndAuditsAChangedTheme() {
    when(store.find("tenant-a", "actor-a"))
        .thenReturn(Optional.of(new StoredUserPreferences(DARK, NOW.minusSeconds(60), 2)));
    when(store.save("tenant-a", "actor-a", LIGHT, NOW))
        .thenReturn(new StoredUserPreferences(LIGHT, NOW, 3));

    var result = service.update("tenant-a", "actor-a", "req-a", LIGHT);

    assertThat(result.themeMode()).isEqualTo(LIGHT);
    assertThat(result.version()).isEqualTo(3);
    verify(audit).append(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void repeatedThemeWriteDoesNotAppendDuplicateAuditEvent() {
    when(store.find("tenant-a", "actor-a"))
        .thenReturn(Optional.of(new StoredUserPreferences(DARK, NOW.minusSeconds(60), 2)));
    when(store.save("tenant-a", "actor-a", DARK, NOW))
        .thenReturn(new StoredUserPreferences(DARK, NOW.minusSeconds(60), 2));

    var result = service.update("tenant-a", "actor-a", "req-a", DARK);

    assertThat(result.version()).isEqualTo(2);
    verify(audit, never()).append(org.mockito.ArgumentMatchers.any());
  }
}
