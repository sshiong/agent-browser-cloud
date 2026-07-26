package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "browser_nodes")
public class BrowserNodeEntity {

  @Id
  @Column(name = "node_id")
  private String nodeId;

  @Column(nullable = false)
  private String region;

  @Column(name = "grpc_target", nullable = false)
  private String grpcTarget;

  @Column(name = "lifecycle_state", nullable = false)
  private String lifecycleState;

  @Column(name = "admission_state", nullable = false)
  private String admissionState;

  @Column(name = "certified_cpu_millis", nullable = false)
  private int certifiedCpuMillis;

  @Column(name = "certified_memory_mib", nullable = false)
  private int certifiedMemoryMib;

  @Column(name = "certified_pid_count", nullable = false)
  private int certifiedPidCount;

  @Column(name = "certified_gpu_slots", nullable = false)
  private int certifiedGpuSlots;

  @Column(name = "certified_media_slots", nullable = false)
  private int certifiedMediaSlots;

  @Column(name = "safety_margin_percent", nullable = false)
  private int safetyMarginPercent;

  @Column(name = "reserved_cpu_millis", nullable = false)
  private int reservedCpuMillis;

  @Column(name = "reserved_memory_mib", nullable = false)
  private int reservedMemoryMib;

  @Column(name = "reserved_pid_count", nullable = false)
  private int reservedPidCount;

  @Column(name = "reserved_gpu_slots", nullable = false)
  private int reservedGpuSlots;

  @Column(name = "reserved_media_slots", nullable = false)
  private int reservedMediaSlots;

  @Column(name = "active_sessions", nullable = false)
  private int activeSessions;

  @Column(name = "max_sessions", nullable = false)
  private int maxSessions;

  @Column(name = "memory_psi_some_avg10", nullable = false)
  private BigDecimal memoryPsiSomeAvg10;

  @Column(name = "memory_psi_full_avg10", nullable = false)
  private BigDecimal memoryPsiFullAvg10;

  @Column(name = "cpu_psi_some_avg10", nullable = false)
  private BigDecimal cpuPsiSomeAvg10;

  @Column(name = "io_psi_full_avg10", nullable = false)
  private BigDecimal ioPsiFullAvg10;

  @Column(name = "pressure_state", nullable = false)
  private String pressureState;

  @Column(name = "pressure_reason")
  private String pressureReason;

  @Column(name = "pressure_recovery_streak", nullable = false)
  private int pressureRecoveryStreak;

  @Column(name = "supports_desktop", nullable = false)
  private boolean supportsDesktop;

  @Column(name = "supports_gpu", nullable = false)
  private boolean supportsGpu;

  @Column(name = "supports_media", nullable = false)
  private boolean supportsMedia;

  @Column(name = "supports_native_os", nullable = false)
  private boolean supportsNativeOs;

  @Column(name = "isolation_capable", nullable = false)
  private boolean isolationCapable;

  @Column(nullable = false, columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String labels;

  @Column(name = "last_heartbeat_at", nullable = false)
  private Instant lastHeartbeatAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected BrowserNodeEntity() {}

  public BrowserNodeEntity(
      String nodeId,
      String region,
      String grpcTarget,
      int certifiedCpuMillis,
      int certifiedMemoryMib,
      int certifiedPidCount,
      int certifiedGpuSlots,
      int certifiedMediaSlots,
      int safetyMarginPercent,
      int maxSessions,
      boolean supportsDesktop,
      boolean supportsGpu,
      boolean supportsMedia,
      boolean supportsNativeOs,
      boolean isolationCapable,
      String labels,
      Instant now) {
    this.nodeId = nodeId;
    this.region = region;
    this.grpcTarget = grpcTarget;
    this.lifecycleState = "READY";
    this.admissionState = "OPEN";
    this.certifiedCpuMillis = certifiedCpuMillis;
    this.certifiedMemoryMib = certifiedMemoryMib;
    this.certifiedPidCount = certifiedPidCount;
    this.certifiedGpuSlots = certifiedGpuSlots;
    this.certifiedMediaSlots = certifiedMediaSlots;
    this.safetyMarginPercent = safetyMarginPercent;
    this.maxSessions = maxSessions;
    this.supportsDesktop = supportsDesktop;
    this.supportsGpu = supportsGpu;
    this.supportsMedia = supportsMedia;
    this.supportsNativeOs = supportsNativeOs;
    this.isolationCapable = isolationCapable;
    this.memoryPsiSomeAvg10 = BigDecimal.ZERO;
    this.memoryPsiFullAvg10 = BigDecimal.ZERO;
    this.cpuPsiSomeAvg10 = BigDecimal.ZERO;
    this.ioPsiFullAvg10 = BigDecimal.ZERO;
    this.pressureState = "NORMAL";
    this.labels = labels;
    this.lastHeartbeatAt = now;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void updateRegistration(
      String region,
      String grpcTarget,
      int certifiedCpuMillis,
      int certifiedMemoryMib,
      int certifiedPidCount,
      int certifiedGpuSlots,
      int certifiedMediaSlots,
      int safetyMarginPercent,
      int maxSessions,
      boolean supportsDesktop,
      boolean supportsGpu,
      boolean supportsMedia,
      boolean supportsNativeOs,
      boolean isolationCapable,
      String labels,
      Instant now) {
    if (reservedCpuMillis > certifiedCpuMillis
        || reservedMemoryMib > certifiedMemoryMib
        || reservedPidCount > certifiedPidCount
        || reservedGpuSlots > certifiedGpuSlots
        || reservedMediaSlots > certifiedMediaSlots) {
      throw new IllegalArgumentException("new certified capacity is below existing reservations");
    }
    this.region = region;
    this.grpcTarget = grpcTarget;
    this.certifiedCpuMillis = certifiedCpuMillis;
    this.certifiedMemoryMib = certifiedMemoryMib;
    this.certifiedPidCount = certifiedPidCount;
    this.certifiedGpuSlots = certifiedGpuSlots;
    this.certifiedMediaSlots = certifiedMediaSlots;
    this.safetyMarginPercent = safetyMarginPercent;
    this.maxSessions = maxSessions;
    this.supportsDesktop = supportsDesktop;
    this.supportsGpu = supportsGpu;
    this.supportsMedia = supportsMedia;
    this.supportsNativeOs = supportsNativeOs;
    this.isolationCapable = isolationCapable;
    this.labels = labels;
    this.lifecycleState = "READY";
    this.lastHeartbeatAt = now;
    this.updatedAt = now;
  }

  public boolean canReserve(
      int cpuMillis,
      int memoryMib,
      int pidCount,
      int gpuSlots,
      int mediaSlots,
      boolean desktop,
      boolean nativeOs,
      boolean isolated,
      boolean media) {
    int usablePercent = 100 - safetyMarginPercent;
    return lifecycleState.equals("READY")
        && admissionState.equals("OPEN")
        && pressureState.equals("NORMAL")
        && (!desktop || supportsDesktop)
        && (gpuSlots == 0 || supportsGpu)
        && (!media || supportsMedia)
        && (!nativeOs || supportsNativeOs)
        && (!isolated || isolationCapable)
        && activeSessions < maxSessions
        && (reservedCpuMillis + cpuMillis) * 100L <= certifiedCpuMillis * (long) usablePercent
        && (reservedMemoryMib + memoryMib) * 100L <= certifiedMemoryMib * (long) usablePercent
        && (reservedPidCount + pidCount) * 100L <= certifiedPidCount * (long) usablePercent
        && (reservedGpuSlots + gpuSlots) * 100L <= certifiedGpuSlots * (long) usablePercent
        && (reservedMediaSlots + mediaSlots) * 100L <= certifiedMediaSlots * (long) usablePercent;
  }

  public void reserve(
      int cpuMillis, int memoryMib, int pidCount, int gpuSlots, int mediaSlots, Instant now) {
    reservedCpuMillis = Math.addExact(reservedCpuMillis, cpuMillis);
    reservedMemoryMib = Math.addExact(reservedMemoryMib, memoryMib);
    reservedPidCount = Math.addExact(reservedPidCount, pidCount);
    reservedGpuSlots = Math.addExact(reservedGpuSlots, gpuSlots);
    reservedMediaSlots = Math.addExact(reservedMediaSlots, mediaSlots);
    activeSessions = Math.addExact(activeSessions, 1);
    updatedAt = now;
  }

  public void release(
      int cpuMillis, int memoryMib, int pidCount, int gpuSlots, int mediaSlots, Instant now) {
    reservedCpuMillis = Math.max(0, reservedCpuMillis - cpuMillis);
    reservedMemoryMib = Math.max(0, reservedMemoryMib - memoryMib);
    reservedPidCount = Math.max(0, reservedPidCount - pidCount);
    reservedGpuSlots = Math.max(0, reservedGpuSlots - gpuSlots);
    reservedMediaSlots = Math.max(0, reservedMediaSlots - mediaSlots);
    activeSessions = Math.max(0, activeSessions - 1);
    updatedAt = now;
  }

  public void recordPressure(
      BigDecimal memorySome,
      BigDecimal memoryFull,
      BigDecimal cpuSome,
      BigDecimal ioFull,
      String reason,
      Instant now) {
    memoryPsiSomeAvg10 = requirePsi(memorySome);
    memoryPsiFullAvg10 = requirePsi(memoryFull);
    cpuPsiSomeAvg10 = requirePsi(cpuSome);
    ioPsiFullAvg10 = requirePsi(ioFull);
    boolean critical =
        memoryFull.doubleValue() >= 1.0
            || memorySome.doubleValue() >= 20.0
            || cpuSome.doubleValue() >= 50.0
            || ioFull.doubleValue() >= 5.0;
    boolean degraded =
        memoryFull.doubleValue() >= 0.5
            || memorySome.doubleValue() >= 10.0
            || cpuSome.doubleValue() >= 25.0
            || ioFull.doubleValue() >= 1.0;
    if (critical) {
      pressureState = "CRITICAL";
      admissionState = "CLOSED";
      pressureRecoveryStreak = 0;
      pressureReason = normalizeReason(reason, "PSI_CRITICAL");
    } else if (degraded) {
      pressureState = "DEGRADED";
      admissionState = "CLOSED";
      pressureRecoveryStreak = 0;
      pressureReason = normalizeReason(reason, "PSI_DEGRADED");
    } else if (!pressureState.equals("NORMAL")) {
      pressureRecoveryStreak++;
      if (pressureRecoveryStreak >= 3) {
        pressureState = "NORMAL";
        admissionState = "OPEN";
        pressureRecoveryStreak = 0;
        pressureReason = null;
      }
    } else {
      admissionState = "OPEN";
      pressureReason = null;
    }
    lastHeartbeatAt = now;
    updatedAt = now;
  }

  private static BigDecimal requirePsi(BigDecimal value) {
    if (value == null || value.signum() < 0 || value.doubleValue() > 100.0) {
      throw new IllegalArgumentException("PSI value must be between 0 and 100");
    }
    return value;
  }

  private static String normalizeReason(String reason, String fallback) {
    if (reason == null || reason.isBlank()) {
      return fallback;
    }
    var normalized = reason.strip();
    return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
  }

  public String getNodeId() {
    return nodeId;
  }

  public String getRegion() {
    return region;
  }

  public String getGrpcTarget() {
    return grpcTarget;
  }

  public boolean isReadyForDispatch() {
    return "READY".equals(lifecycleState) && lastHeartbeatAt != null;
  }

  public String getLifecycleState() {
    return lifecycleState;
  }

  public String getAdmissionState() {
    return admissionState;
  }

  public int getCertifiedCpuMillis() {
    return certifiedCpuMillis;
  }

  public int getCertifiedMemoryMib() {
    return certifiedMemoryMib;
  }

  public int getCertifiedPidCount() {
    return certifiedPidCount;
  }

  public int getCertifiedGpuSlots() {
    return certifiedGpuSlots;
  }

  public int getCertifiedMediaSlots() {
    return certifiedMediaSlots;
  }

  public int getSafetyMarginPercent() {
    return safetyMarginPercent;
  }

  public int getReservedCpuMillis() {
    return reservedCpuMillis;
  }

  public int getReservedMemoryMib() {
    return reservedMemoryMib;
  }

  public int getReservedPidCount() {
    return reservedPidCount;
  }

  public int getReservedGpuSlots() {
    return reservedGpuSlots;
  }

  public int getReservedMediaSlots() {
    return reservedMediaSlots;
  }

  public int getActiveSessions() {
    return activeSessions;
  }

  public int getMaxSessions() {
    return maxSessions;
  }

  public BigDecimal getMemoryPsiSomeAvg10() {
    return memoryPsiSomeAvg10;
  }

  public BigDecimal getMemoryPsiFullAvg10() {
    return memoryPsiFullAvg10;
  }

  public BigDecimal getCpuPsiSomeAvg10() {
    return cpuPsiSomeAvg10;
  }

  public BigDecimal getIoPsiFullAvg10() {
    return ioPsiFullAvg10;
  }

  public String getPressureState() {
    return pressureState;
  }

  public String getPressureReason() {
    return pressureReason;
  }

  public boolean isSupportsDesktop() {
    return supportsDesktop;
  }

  public boolean isSupportsGpu() {
    return supportsGpu;
  }

  public boolean isSupportsMedia() {
    return supportsMedia;
  }

  public boolean isSupportsNativeOs() {
    return supportsNativeOs;
  }

  public boolean isIsolationCapable() {
    return isolationCapable;
  }

  public String getLabels() {
    return labels;
  }

  public Instant getLastHeartbeatAt() {
    return lastHeartbeatAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
