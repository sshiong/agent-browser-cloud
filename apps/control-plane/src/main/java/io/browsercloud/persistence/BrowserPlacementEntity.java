package io.browsercloud.persistence;

import io.browsercloud.domain.session.ResourceClass;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "browser_placements")
public class BrowserPlacementEntity {

  @Id
  @Column(name = "session_id")
  private String sessionId;

  @Column(name = "tenant_id", nullable = false)
  private String tenantId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "requested_resource_class", nullable = false)
  private String requestedResourceClass;

  @Column(name = "effective_resource_class", nullable = false)
  private String effectiveResourceClass;

  @Column(name = "extension_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String extensionIds;

  @Column(name = "unknown_extension_count", nullable = false)
  private int unknownExtensionCount;

  @Column(name = "cpu_millis", nullable = false)
  private int cpuMillis;

  @Column(name = "memory_request_mib", nullable = false)
  private int memoryRequestMib;

  @Column(name = "memory_limit_mib", nullable = false)
  private int memoryLimitMib;

  @Column(name = "pid_limit", nullable = false)
  private int pidLimit;

  @Column(name = "tab_budget", nullable = false)
  private int tabBudget;

  @Column(name = "state_collector_budget_percent", nullable = false)
  private int stateCollectorBudgetPercent;

  @Column(name = "remote_desktop_bitrate_kbps", nullable = false)
  private int remoteDesktopBitrateKbps;

  @Column(name = "extension_cpu_weight", nullable = false)
  private int extensionCpuWeight;

  @Column(name = "requires_desktop", nullable = false)
  private boolean requiresDesktop;

  @Column(name = "requires_gpu", nullable = false)
  private boolean requiresGpu;

  @Column(name = "requires_native_os", nullable = false)
  private boolean requiresNativeOs;

  @Column(name = "requires_isolation", nullable = false)
  private boolean requiresIsolation;

  @Column(name = "requires_media", nullable = false)
  private boolean requiresMedia;

  @Column(name = "media_slots", nullable = false)
  private int mediaSlots;

  @Column(name = "media_encoder_slots", nullable = false)
  private int mediaEncoderSlots;

  @Column(name = "background_tabs_frozen", nullable = false)
  private boolean backgroundTabsFrozen;

  @Column(name = "new_tabs_blocked", nullable = false)
  private boolean newTabsBlocked;

  @Column(name = "paused_extension_ids", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String pausedExtensionIds;

  @Column(name = "success_trace_sample_percent", nullable = false)
  private int successTraceSamplePercent;

  @Column(name = "success_screenshot_sample_percent", nullable = false)
  private int successScreenshotSamplePercent;

  @Column(name = "observer_frame_rate_fps", nullable = false)
  private int observerFrameRateFps;

  @Column(name = "video_recording_requested", nullable = false)
  private boolean videoRecordingRequested;

  @Column(name = "video_recording_enabled", nullable = false)
  private boolean videoRecordingEnabled;

  @Column(name = "media_bitrate_kbps", nullable = false)
  private int mediaBitrateKbps;

  @Column(name = "placement_score", nullable = false)
  private int placementScore;

  @Column(nullable = false)
  private String state;

  @Column(name = "reason_codes", nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String reasonCodes;

  @Column(name = "reserved_at", nullable = false)
  private Instant reservedAt;

  @Column(name = "activated_at")
  private Instant activatedAt;

  @Column(name = "released_at")
  private Instant releasedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected BrowserPlacementEntity() {}

  public BrowserPlacementEntity(
      String sessionId,
      String tenantId,
      String nodeId,
      ResourceClass requestedResourceClass,
      ResourceClass effectiveResourceClass,
      String extensionIds,
      int unknownExtensionCount,
      int cpuMillis,
      int memoryRequestMib,
      int memoryLimitMib,
      int pidLimit,
      int tabBudget,
      boolean requiresDesktop,
      boolean requiresGpu,
      boolean requiresNativeOs,
      boolean requiresIsolation,
      boolean requiresMedia,
      int mediaSlots,
      int mediaBitrateKbps,
      int placementScore,
      String reasonCodes,
      Instant now) {
    this(
        sessionId,
        tenantId,
        nodeId,
        requestedResourceClass,
        effectiveResourceClass,
        extensionIds,
        unknownExtensionCount,
        cpuMillis,
        memoryRequestMib,
        memoryLimitMib,
        pidLimit,
        tabBudget,
        requiresDesktop,
        requiresGpu,
        requiresNativeOs,
        requiresIsolation,
        requiresMedia,
        mediaSlots,
        mediaBitrateKbps,
        false,
        placementScore,
        reasonCodes,
        now);
  }

  public BrowserPlacementEntity(
      String sessionId,
      String tenantId,
      String nodeId,
      ResourceClass requestedResourceClass,
      ResourceClass effectiveResourceClass,
      String extensionIds,
      int unknownExtensionCount,
      int cpuMillis,
      int memoryRequestMib,
      int memoryLimitMib,
      int pidLimit,
      int tabBudget,
      boolean requiresDesktop,
      boolean requiresGpu,
      boolean requiresNativeOs,
      boolean requiresIsolation,
      boolean requiresMedia,
      int mediaSlots,
      int mediaBitrateKbps,
      boolean videoRecordingRequested,
      int placementScore,
      String reasonCodes,
      Instant now) {
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    this.nodeId = nodeId;
    this.requestedResourceClass = requestedResourceClass.name();
    this.effectiveResourceClass = effectiveResourceClass.name();
    this.extensionIds = extensionIds;
    this.unknownExtensionCount = unknownExtensionCount;
    this.cpuMillis = cpuMillis;
    this.memoryRequestMib = memoryRequestMib;
    this.memoryLimitMib = memoryLimitMib;
    this.pidLimit = pidLimit;
    this.tabBudget = tabBudget;
    this.stateCollectorBudgetPercent = 50;
    this.remoteDesktopBitrateKbps = requiresDesktop ? 8_000 : 0;
    this.extensionCpuWeight = 100;
    this.requiresDesktop = requiresDesktop;
    this.requiresGpu = requiresGpu;
    this.requiresNativeOs = requiresNativeOs;
    this.requiresIsolation = requiresIsolation;
    this.requiresMedia = requiresMedia;
    this.mediaSlots = mediaSlots;
    this.mediaEncoderSlots = requiresMedia ? mediaSlots : 0;
    this.backgroundTabsFrozen = false;
    this.newTabsBlocked = false;
    this.pausedExtensionIds = "[]";
    this.successTraceSamplePercent = 100;
    this.successScreenshotSamplePercent = 100;
    this.observerFrameRateFps = requiresDesktop ? 30 : 0;
    this.videoRecordingRequested = videoRecordingRequested;
    this.videoRecordingEnabled = false;
    this.mediaBitrateKbps = mediaBitrateKbps;
    this.placementScore = placementScore;
    this.state = "RESERVED";
    this.reasonCodes = reasonCodes;
    this.reservedAt = now;
  }

  public void activate(Instant now) {
    if (state.equals("RELEASED")) {
      throw new IllegalStateException("released placement cannot be activated");
    }
    state = "ACTIVE";
    videoRecordingEnabled = videoRecordingRequested;
    activatedAt = now;
  }

  public boolean release(Instant now) {
    if (state.equals("RELEASED")) {
      return false;
    }
    state = "RELEASED";
    releasedAt = now;
    return true;
  }

  public void markEvicting(Instant now) {
    if (!state.equals("ACTIVE")) {
      throw new IllegalStateException("only an active placement can be pressure-evicted");
    }
    state = "WAITING_SAFE_POINT";
    reasonCodes =
        reasonCodes.equals("[]")
            ? "[\"NODE_PRESSURE_EVICTION\"]"
            : reasonCodes.substring(0, reasonCodes.length() - 1) + ",\"NODE_PRESSURE_EVICTION\"]";
    activatedAt = activatedAt == null ? now : activatedAt;
  }

  public void cancelEviction() {
    if (state.equals("WAITING_SAFE_POINT")) {
      state = "ACTIVE";
    }
  }

  public void applyResourceAdjustment(
      int nextCpuMillis,
      int nextMemoryRequestMib,
      int nextMemoryLimitMib,
      int nextPidLimit,
      int nextTabBudget,
      int nextStateCollectorBudgetPercent,
      int nextRemoteDesktopBitrateKbps,
      int nextExtensionCpuWeight,
      int nextMediaEncoderSlots,
      boolean nextBackgroundTabsFrozen,
      boolean nextNewTabsBlocked,
      String nextPausedExtensionIds,
      int nextSuccessTraceSamplePercent,
      int nextSuccessScreenshotSamplePercent,
      int nextObserverFrameRateFps,
      boolean nextVideoRecordingEnabled) {
    if (!state.equals("ACTIVE")) {
      throw new IllegalStateException("only an active placement can be adjusted");
    }
    if (nextCpuMillis <= 0
        || nextMemoryRequestMib <= 0
        || nextMemoryLimitMib < nextMemoryRequestMib
        || nextPidLimit < 32
        || nextTabBudget <= 0
        || nextStateCollectorBudgetPercent < 10
        || nextStateCollectorBudgetPercent > 100
        || nextRemoteDesktopBitrateKbps < 0
        || nextRemoteDesktopBitrateKbps > 100_000
        || nextSuccessTraceSamplePercent < 1
        || nextSuccessTraceSamplePercent > 100
        || nextSuccessScreenshotSamplePercent < 1
        || nextSuccessScreenshotSamplePercent > 100
        || nextObserverFrameRateFps < 0
        || nextObserverFrameRateFps > 60
        || (requiresDesktop && nextRemoteDesktopBitrateKbps < 250)
        || (!requiresDesktop && nextRemoteDesktopBitrateKbps != 0)
        || (requiresDesktop && nextObserverFrameRateFps < 1)
        || (!requiresDesktop && nextObserverFrameRateFps != 0)) {
      throw new IllegalArgumentException("resource adjustment is invalid");
    }
    if (nextVideoRecordingEnabled && !videoRecordingRequested) {
      throw new IllegalArgumentException("video recording was not requested");
    }
    if (nextExtensionCpuWeight < 1 || nextExtensionCpuWeight > 10_000) {
      throw new IllegalArgumentException("extension CPU weight is invalid");
    }
    if (nextPausedExtensionIds == null || nextPausedExtensionIds.isBlank()) {
      throw new IllegalArgumentException("paused extension IDs are invalid");
    }
    if ((!requiresMedia && nextMediaEncoderSlots != 0)
        || (requiresMedia && (nextMediaEncoderSlots < 1 || nextMediaEncoderSlots > mediaSlots))) {
      throw new IllegalArgumentException("Media Encoder Slot allocation is invalid");
    }
    cpuMillis = nextCpuMillis;
    memoryRequestMib = nextMemoryRequestMib;
    memoryLimitMib = nextMemoryLimitMib;
    pidLimit = nextPidLimit;
    tabBudget = nextTabBudget;
    stateCollectorBudgetPercent = nextStateCollectorBudgetPercent;
    remoteDesktopBitrateKbps = nextRemoteDesktopBitrateKbps;
    extensionCpuWeight = nextExtensionCpuWeight;
    mediaEncoderSlots = nextMediaEncoderSlots;
    backgroundTabsFrozen = nextBackgroundTabsFrozen;
    newTabsBlocked = nextNewTabsBlocked;
    pausedExtensionIds = nextPausedExtensionIds;
    successTraceSamplePercent = nextSuccessTraceSamplePercent;
    successScreenshotSamplePercent = nextSuccessScreenshotSamplePercent;
    observerFrameRateFps = nextObserverFrameRateFps;
    videoRecordingEnabled = nextVideoRecordingEnabled;
  }

  public void applyResourceAdjustment(
      int nextCpuMillis,
      int nextMemoryRequestMib,
      int nextMemoryLimitMib,
      int nextPidLimit,
      int nextTabBudget,
      int nextStateCollectorBudgetPercent,
      int nextRemoteDesktopBitrateKbps,
      int nextExtensionCpuWeight,
      int nextMediaEncoderSlots,
      boolean nextBackgroundTabsFrozen,
      boolean nextNewTabsBlocked,
      String nextPausedExtensionIds,
      int nextSuccessTraceSamplePercent,
      int nextSuccessScreenshotSamplePercent,
      int nextObserverFrameRateFps) {
    applyResourceAdjustment(
        nextCpuMillis,
        nextMemoryRequestMib,
        nextMemoryLimitMib,
        nextPidLimit,
        nextTabBudget,
        nextStateCollectorBudgetPercent,
        nextRemoteDesktopBitrateKbps,
        nextExtensionCpuWeight,
        nextMediaEncoderSlots,
        nextBackgroundTabsFrozen,
        nextNewTabsBlocked,
        nextPausedExtensionIds,
        nextSuccessTraceSamplePercent,
        nextSuccessScreenshotSamplePercent,
        nextObserverFrameRateFps,
        videoRecordingEnabled);
  }

  /** N-1 compatibility: screenshot sampling remains unchanged when an older caller omits it. */
  public void applyResourceAdjustment(
      int nextCpuMillis,
      int nextMemoryRequestMib,
      int nextMemoryLimitMib,
      int nextPidLimit,
      int nextTabBudget,
      int nextStateCollectorBudgetPercent,
      int nextRemoteDesktopBitrateKbps,
      int nextExtensionCpuWeight,
      int nextMediaEncoderSlots,
      boolean nextBackgroundTabsFrozen,
      boolean nextNewTabsBlocked,
      String nextPausedExtensionIds,
      int nextSuccessTraceSamplePercent,
      int nextObserverFrameRateFps) {
    applyResourceAdjustment(
        nextCpuMillis,
        nextMemoryRequestMib,
        nextMemoryLimitMib,
        nextPidLimit,
        nextTabBudget,
        nextStateCollectorBudgetPercent,
        nextRemoteDesktopBitrateKbps,
        nextExtensionCpuWeight,
        nextMediaEncoderSlots,
        nextBackgroundTabsFrozen,
        nextNewTabsBlocked,
        nextPausedExtensionIds,
        nextSuccessTraceSamplePercent,
        successScreenshotSamplePercent,
        nextObserverFrameRateFps,
        videoRecordingEnabled);
  }

  /** N-1 compatibility: screenshot sampling remains unchanged when an older caller omits it. */
  public void applyResourceAdjustment(
      int nextCpuMillis,
      int nextMemoryRequestMib,
      int nextMemoryLimitMib,
      int nextPidLimit,
      int nextTabBudget,
      int nextStateCollectorBudgetPercent,
      int nextRemoteDesktopBitrateKbps,
      int nextExtensionCpuWeight,
      int nextMediaEncoderSlots,
      boolean nextBackgroundTabsFrozen,
      boolean nextNewTabsBlocked,
      String nextPausedExtensionIds,
      int nextSuccessTraceSamplePercent,
      int nextObserverFrameRateFps,
      boolean nextVideoRecordingEnabled) {
    applyResourceAdjustment(
        nextCpuMillis,
        nextMemoryRequestMib,
        nextMemoryLimitMib,
        nextPidLimit,
        nextTabBudget,
        nextStateCollectorBudgetPercent,
        nextRemoteDesktopBitrateKbps,
        nextExtensionCpuWeight,
        nextMediaEncoderSlots,
        nextBackgroundTabsFrozen,
        nextNewTabsBlocked,
        nextPausedExtensionIds,
        nextSuccessTraceSamplePercent,
        successScreenshotSamplePercent,
        nextObserverFrameRateFps,
        nextVideoRecordingEnabled);
  }

  public String getSessionId() {
    return sessionId;
  }

  public String getTenantId() {
    return tenantId;
  }

  public String getNodeId() {
    return nodeId;
  }

  public ResourceClass requestedResourceClass() {
    return ResourceClass.valueOf(requestedResourceClass);
  }

  public ResourceClass effectiveResourceClass() {
    return ResourceClass.valueOf(effectiveResourceClass);
  }

  public String getExtensionIds() {
    return extensionIds;
  }

  public int getUnknownExtensionCount() {
    return unknownExtensionCount;
  }

  public int getCpuMillis() {
    return cpuMillis;
  }

  public int getMemoryRequestMib() {
    return memoryRequestMib;
  }

  public int getMemoryLimitMib() {
    return memoryLimitMib;
  }

  public int getPidLimit() {
    return pidLimit;
  }

  public int getTabBudget() {
    return tabBudget;
  }

  public int getStateCollectorBudgetPercent() {
    return stateCollectorBudgetPercent;
  }

  public int getRemoteDesktopBitrateKbps() {
    return remoteDesktopBitrateKbps;
  }

  public int getExtensionCpuWeight() {
    return extensionCpuWeight;
  }

  public boolean isRequiresDesktop() {
    return requiresDesktop;
  }

  public boolean isRequiresGpu() {
    return requiresGpu;
  }

  public boolean isRequiresNativeOs() {
    return requiresNativeOs;
  }

  public boolean isRequiresIsolation() {
    return requiresIsolation;
  }

  public boolean isRequiresMedia() {
    return requiresMedia;
  }

  public int getMediaSlots() {
    return mediaSlots;
  }

  public int getMediaEncoderSlots() {
    return mediaEncoderSlots;
  }

  public boolean isBackgroundTabsFrozen() {
    return backgroundTabsFrozen;
  }

  public boolean isNewTabsBlocked() {
    return newTabsBlocked;
  }

  public String getPausedExtensionIds() {
    return pausedExtensionIds;
  }

  public int getSuccessTraceSamplePercent() {
    return successTraceSamplePercent;
  }

  public int getSuccessScreenshotSamplePercent() {
    return successScreenshotSamplePercent;
  }

  public int getObserverFrameRateFps() {
    return observerFrameRateFps;
  }

  public boolean isVideoRecordingRequested() {
    return videoRecordingRequested;
  }

  public boolean isVideoRecordingEnabled() {
    return videoRecordingEnabled;
  }

  public int getMediaBitrateKbps() {
    return mediaBitrateKbps;
  }

  public int getPlacementScore() {
    return placementScore;
  }

  public String getState() {
    return state;
  }

  public String getReasonCodes() {
    return reasonCodes;
  }

  public Instant getReservedAt() {
    return reservedAt;
  }

  public Instant getActivatedAt() {
    return activatedAt;
  }

  public Instant getReleasedAt() {
    return releasedAt;
  }
}
