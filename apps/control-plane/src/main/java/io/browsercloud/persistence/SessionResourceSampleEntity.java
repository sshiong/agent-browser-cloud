package io.browsercloud.persistence;

import io.browsercloud.api.SessionResourceModels.RecordResourceSampleRequest;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "session_resource_samples")
public class SessionResourceSampleEntity {
  @Id private String sampleId;

  @Column(nullable = false)
  private String sessionId;

  @Column(nullable = false)
  private String tenantId;

  @Column(nullable = false)
  private String nodeId;

  private BigDecimal cpuPercent;
  private Integer memoryRssMib;
  private BigDecimal memoryPsiSomeAvg10;
  private Integer rendererCount;
  private Integer tabCount;
  private Integer mainThreadBlockedMs;
  private Integer agentActionLatencyMs;
  private Integer stateDiffQueueDepth;
  private Long profileIoBytesPerSecond;
  private BigDecimal extensionCpuPercent;
  private Integer extensionMemoryMib;
  private Integer remoteDesktopFrameAgeMs;
  private BigDecimal mediaEncoderPercent;
  private String dangerEvent;

  @Column(nullable = false)
  private Instant observedAt;

  @Column(nullable = false)
  private Instant receivedAt;

  @Column(name = "stream_sequence", insertable = false, updatable = false)
  private Long streamSequence;

  protected SessionResourceSampleEntity() {}

  public SessionResourceSampleEntity(
      String id,
      String sessionId,
      String tenantId,
      RecordResourceSampleRequest request,
      Instant now) {
    sampleId = id;
    this.sessionId = sessionId;
    this.tenantId = tenantId;
    nodeId = request.nodeId();
    cpuPercent = decimal(request.cpuPercent());
    memoryRssMib = request.memoryRssMib();
    memoryPsiSomeAvg10 = decimal(request.memoryPsiSomeAvg10());
    rendererCount = request.rendererCount();
    tabCount = request.tabCount();
    mainThreadBlockedMs = request.mainThreadBlockedMs();
    agentActionLatencyMs = request.agentActionLatencyMs();
    stateDiffQueueDepth = request.stateDiffQueueDepth();
    profileIoBytesPerSecond = request.profileIoBytesPerSecond();
    extensionCpuPercent = decimal(request.extensionCpuPercent());
    extensionMemoryMib = request.extensionMemoryMib();
    remoteDesktopFrameAgeMs = request.remoteDesktopFrameAgeMs();
    mediaEncoderPercent = decimal(request.mediaEncoderPercent());
    dangerEvent = request.dangerEvent();
    observedAt = request.observedAt() == null ? now : request.observedAt();
    receivedAt = now;
  }

  public Double getCpuPercent() {
    return number(cpuPercent);
  }

  public Integer getMemoryRssMib() {
    return memoryRssMib;
  }

  public Double getMemoryPsiSomeAvg10() {
    return number(memoryPsiSomeAvg10);
  }

  public Integer getRendererCount() {
    return rendererCount;
  }

  public Integer getTabCount() {
    return tabCount;
  }

  public Integer getAgentActionLatencyMs() {
    return agentActionLatencyMs;
  }

  public Integer getStateDiffQueueDepth() {
    return stateDiffQueueDepth;
  }

  public Integer getRemoteDesktopFrameAgeMs() {
    return remoteDesktopFrameAgeMs;
  }

  public Double getMediaEncoderPercent() {
    return number(mediaEncoderPercent);
  }

  public String getDangerEvent() {
    return dangerEvent;
  }

  public Instant getObservedAt() {
    return observedAt;
  }

  public Long getStreamSequence() {
    return streamSequence;
  }

  private static BigDecimal decimal(Double value) {
    return value == null ? null : BigDecimal.valueOf(value);
  }

  private static Double number(BigDecimal value) {
    return value == null ? null : value.doubleValue();
  }
}
