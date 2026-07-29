package io.browsercloud.persistence;

import io.browsercloud.api.EnvironmentSavedViewModels.EnvironmentPrimaryView;
import io.browsercloud.api.EnvironmentSavedViewModels.SavedViewScope;
import io.browsercloud.domain.session.SessionState;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "environment_saved_views")
public class EnvironmentSavedViewEntity {

  @Id
  @Column(name = "saved_view_id")
  private String savedViewId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "owner_actor_id", nullable = false)
  private String ownerActorId;

  @Column(nullable = false)
  private String scope;

  @Column(nullable = false)
  private String name;

  @Column(name = "primary_view", nullable = false)
  private String primaryView;

  @Column(name = "session_state")
  private String sessionState;

  @Column(name = "search_query", nullable = false)
  private String searchQuery;

  @Column(name = "show_runtime_column", nullable = false)
  private boolean showRuntimeColumn;

  @Column(name = "show_context_column", nullable = false)
  private boolean showContextColumn;

  @Column(name = "show_operation_column", nullable = false)
  private boolean showOperationColumn;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected EnvironmentSavedViewEntity() {}

  public EnvironmentSavedViewEntity(
      String savedViewId,
      String tenantId,
      String ownerActorId,
      SavedViewScope scope,
      String name,
      EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      String searchQuery,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn,
      Instant now) {
    this.savedViewId = savedViewId;
    this.tenantId = tenantId;
    this.ownerActorId = ownerActorId;
    this.scope = scope.name();
    this.createdAt = now;
    update(
        name,
        primaryView,
        sessionState,
        searchQuery,
        showRuntimeColumn,
        showContextColumn,
        showOperationColumn,
        now);
  }

  public void update(
      String name,
      EnvironmentPrimaryView primaryView,
      SessionState sessionState,
      String searchQuery,
      boolean showRuntimeColumn,
      boolean showContextColumn,
      boolean showOperationColumn,
      Instant now) {
    this.name = name.strip();
    this.primaryView = primaryView.name();
    this.sessionState = sessionState == null ? null : sessionState.name();
    this.searchQuery = searchQuery == null ? "" : searchQuery.strip();
    this.showRuntimeColumn = showRuntimeColumn;
    this.showContextColumn = showContextColumn;
    this.showOperationColumn = showOperationColumn;
    this.updatedAt = now;
  }

  public String getSavedViewId() {
    return savedViewId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getOwnerActorId() {
    return ownerActorId;
  }

  public SavedViewScope getScope() {
    return SavedViewScope.valueOf(scope);
  }

  public String getName() {
    return name;
  }

  public EnvironmentPrimaryView getPrimaryView() {
    return EnvironmentPrimaryView.valueOf(primaryView);
  }

  public SessionState getSessionState() {
    return sessionState == null ? null : SessionState.valueOf(sessionState);
  }

  public String getSearchQuery() {
    return searchQuery;
  }

  public boolean isShowRuntimeColumn() {
    return showRuntimeColumn;
  }

  public boolean isShowContextColumn() {
    return showContextColumn;
  }

  public boolean isShowOperationColumn() {
    return showOperationColumn;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }
}
