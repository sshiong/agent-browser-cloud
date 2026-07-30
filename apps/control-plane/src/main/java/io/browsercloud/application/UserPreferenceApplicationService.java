package io.browsercloud.application;

import io.browsercloud.api.UserPreferenceModels.ThemeMode;
import io.browsercloud.api.UserPreferenceModels.UserPreferencesView;
import java.time.Clock;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Authenticated actor preference service shared by Web and Tauri clients. */
@Service
public class UserPreferenceApplicationService {

  private final UserPreferenceStore store;
  private final AuditApplicationService audit;
  private final Clock clock;

  @Autowired
  public UserPreferenceApplicationService(
      UserPreferenceStore store, AuditApplicationService audit) {
    this(store, audit, Clock.systemUTC());
  }

  UserPreferenceApplicationService(
      UserPreferenceStore store, AuditApplicationService audit, Clock clock) {
    this.store = store;
    this.audit = audit;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public UserPreferencesView get(String tenantId, String actorId) {
    return store
        .find(tenantId, actorId)
        .map(
            preference ->
                new UserPreferencesView(
                    preference.themeMode(),
                    "USER_OVERRIDE",
                    preference.updatedAt(),
                    preference.version()))
        .orElseGet(() -> new UserPreferencesView(ThemeMode.SYSTEM, "SYSTEM_DEFAULT", null, 0));
  }

  @Transactional
  public UserPreferencesView update(
      String tenantId, String actorId, String requestId, ThemeMode requestedThemeMode) {
    var previous =
        store.find(tenantId, actorId).map(UserPreferenceStore.StoredUserPreferences::themeMode);
    var persisted = store.save(tenantId, actorId, requestedThemeMode, clock.instant());
    if (previous.isEmpty() || previous.get() != persisted.themeMode()) {
      audit.append(
          new AuditApplicationService.AuditRecord(
              tenantId,
              null,
              "USER_PREFERENCES",
              "USER",
              actorId,
              "USER_PREFERENCES",
              actorId,
              "USER_THEME_UPDATED",
              "COMMITTED",
              Map.of("themeMode", persisted.themeMode().name()),
              requestId));
    }
    return new UserPreferencesView(
        persisted.themeMode(), "USER_OVERRIDE", persisted.updatedAt(), persisted.version());
  }
}
