package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "extension_profile_samples")
public class ExtensionProfileSampleEntity {

  @Id
  @Column(name = "sample_id")
  private String sampleId;

  @Column(name = "extension_id", nullable = false)
  private String extensionId;

  @Column(name = "node_id", nullable = false)
  private String nodeId;

  @Column(name = "cpu_millis", nullable = false)
  private int cpuMillis;

  @Column(name = "memory_mib", nullable = false)
  private int memoryMib;

  @Column(name = "cgroup_psi_burst", nullable = false)
  private boolean cgroupPsiBurst;

  @Column(name = "sample_cpu_millis", nullable = false)
  private int sampleCpuMillis;

  @Column(name = "observed_at", nullable = false)
  private Instant observedAt;

  @Column(name = "recorded_at", nullable = false)
  private Instant recordedAt;

  protected ExtensionProfileSampleEntity() {}

  public ExtensionProfileSampleEntity(
      String sampleId,
      String extensionId,
      String nodeId,
      int cpuMillis,
      int memoryMib,
      boolean cgroupPsiBurst,
      int sampleCpuMillis,
      Instant observedAt,
      Instant recordedAt) {
    this.sampleId = sampleId;
    this.extensionId = extensionId;
    this.nodeId = nodeId;
    this.cpuMillis = cpuMillis;
    this.memoryMib = memoryMib;
    this.cgroupPsiBurst = cgroupPsiBurst;
    this.sampleCpuMillis = sampleCpuMillis;
    this.observedAt = observedAt;
    this.recordedAt = recordedAt;
  }

  public int getCpuMillis() {
    return cpuMillis;
  }

  public int getMemoryMib() {
    return memoryMib;
  }

  public boolean isCgroupPsiBurst() {
    return cgroupPsiBurst;
  }

  public Instant getObservedAt() {
    return observedAt;
  }
}
