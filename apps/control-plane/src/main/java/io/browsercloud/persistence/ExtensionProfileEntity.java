package io.browsercloud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "extension_profiles")
public class ExtensionProfileEntity {

  @Id
  @Column(name = "extension_id")
  private String extensionId;

  @Column(name = "display_name", nullable = false)
  private String displayName;

  @Column(name = "static_cpu_weight", nullable = false)
  private int staticCpuWeight;

  @Column(name = "static_memory_weight", nullable = false)
  private int staticMemoryWeight;

  @Column(name = "startup_weight", nullable = false)
  private int startupWeight;

  @Column(name = "page_injection_weight", nullable = false)
  private int pageInjectionWeight;

  @Column(name = "service_worker_weight", nullable = false)
  private int serviceWorkerWeight;

  @Column(name = "crypto_weight", nullable = false)
  private int cryptoWeight;

  @Column(name = "network_weight", nullable = false)
  private int networkWeight;

  @Column(name = "observed_multiplier", nullable = false)
  private BigDecimal observedMultiplier;

  @Column(nullable = false)
  private BigDecimal confidence;

  @Column(name = "profile_state", nullable = false)
  private String profileState;

  @Column(nullable = false)
  private boolean web3;

  @Column(name = "service_worker", nullable = false)
  private boolean serviceWorker;

  @Column(nullable = false)
  private boolean crypto;

  @Column(nullable = false)
  private boolean privileged;

  @Column(nullable = false)
  private long samples;

  @Column(name = "p95_cpu_millis")
  private Integer p95CpuMillis;

  @Column(name = "p95_memory_mib")
  private Integer p95MemoryMib;

  @Column(name = "last_profiled_at")
  private Instant lastProfiledAt;

  @Column(name = "sampling_tier", nullable = false)
  private String samplingTier;

  @Column(name = "healthy_sample_streak", nullable = false)
  private int healthySampleStreak;

  @Column(name = "next_sample_at")
  private Instant nextSampleAt;

  @Column(name = "sampling_cpu_budget_millis", nullable = false)
  private int samplingCpuBudgetMillis;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Version
  @Column(nullable = false)
  private long version;

  protected ExtensionProfileEntity() {}

  public ExtensionProfileEntity(
      String extensionId,
      String displayName,
      int staticCpuWeight,
      int staticMemoryWeight,
      int startupWeight,
      int pageInjectionWeight,
      int serviceWorkerWeight,
      int cryptoWeight,
      int networkWeight,
      BigDecimal observedMultiplier,
      BigDecimal confidence,
      String profileState,
      boolean web3,
      boolean serviceWorker,
      boolean crypto,
      boolean privileged,
      Instant now) {
    this.extensionId = extensionId;
    this.createdAt = now;
    this.samplingTier = "HIGH";
    this.samplingCpuBudgetMillis = 25;
    this.nextSampleAt = now;
    update(
        displayName,
        staticCpuWeight,
        staticMemoryWeight,
        startupWeight,
        pageInjectionWeight,
        serviceWorkerWeight,
        cryptoWeight,
        networkWeight,
        observedMultiplier,
        confidence,
        profileState,
        web3,
        serviceWorker,
        crypto,
        privileged,
        now);
  }

  public void update(
      String displayName,
      int staticCpuWeight,
      int staticMemoryWeight,
      int startupWeight,
      int pageInjectionWeight,
      int serviceWorkerWeight,
      int cryptoWeight,
      int networkWeight,
      BigDecimal observedMultiplier,
      BigDecimal confidence,
      String profileState,
      boolean web3,
      boolean serviceWorker,
      boolean crypto,
      boolean privileged,
      Instant now) {
    this.displayName = displayName;
    this.staticCpuWeight = staticCpuWeight;
    this.staticMemoryWeight = staticMemoryWeight;
    this.startupWeight = startupWeight;
    this.pageInjectionWeight = pageInjectionWeight;
    this.serviceWorkerWeight = serviceWorkerWeight;
    this.cryptoWeight = cryptoWeight;
    this.networkWeight = networkWeight;
    this.observedMultiplier = observedMultiplier;
    this.confidence = confidence;
    this.profileState = profileState;
    this.web3 = web3;
    this.serviceWorker = serviceWorker;
    this.crypto = crypto;
    this.privileged = privileged;
    this.updatedAt = now;
  }

  public int effectiveCpuMillis() {
    int base =
        Math.addExact(
            Math.addExact(staticCpuWeight, startupWeight),
            Math.addExact(pageInjectionWeight, Math.addExact(cryptoWeight, networkWeight)));
    return observedMultiplier
        .multiply(BigDecimal.valueOf(base))
        .setScale(0, RoundingMode.CEILING)
        .intValueExact();
  }

  public int effectiveMemoryMib() {
    int base =
        Math.addExact(
            staticMemoryWeight,
            Math.addExact(serviceWorkerWeight, Math.addExact(cryptoWeight, pageInjectionWeight)));
    return observedMultiplier
        .multiply(BigDecimal.valueOf(base))
        .setScale(0, RoundingMode.CEILING)
        .intValueExact();
  }

  public void applyObservation(
      long sampleCount,
      int p95CpuMillis,
      int p95MemoryMib,
      boolean pressureBurst,
      Instant observedAt) {
    this.samples = sampleCount;
    this.p95CpuMillis = p95CpuMillis;
    this.p95MemoryMib = p95MemoryMib;
    this.lastProfiledAt = observedAt;

    int cpuBase =
        Math.max(
            1,
            staticCpuWeight + startupWeight + pageInjectionWeight + cryptoWeight + networkWeight);
    int memoryBase =
        Math.max(1, staticMemoryWeight + serviceWorkerWeight + cryptoWeight + pageInjectionWeight);
    BigDecimal observedRatio =
        BigDecimal.valueOf(
                Math.max(p95CpuMillis / (double) cpuBase, p95MemoryMib / (double) memoryBase))
            .setScale(4, RoundingMode.CEILING);

    if (pressureBurst) {
      samplingTier = "DEEP";
      healthySampleStreak = 0;
      nextSampleAt = observedAt.plusSeconds(15);
    } else if (sampleCount < 20 || profileState.equals("PROBATION")) {
      samplingTier = "HIGH";
      healthySampleStreak = 0;
      nextSampleAt = observedAt.plusSeconds(30);
    } else if (observedRatio.compareTo(new BigDecimal("1.5000")) > 0) {
      observedMultiplier = observedMultiplier.max(observedRatio);
      samplingTier = "HIGH";
      healthySampleStreak = 0;
      nextSampleAt = observedAt.plusSeconds(30);
    } else {
      healthySampleStreak++;
      if (healthySampleStreak >= 3) {
        samplingTier =
            switch (samplingTier) {
              case "DEEP", "HIGH" -> "MEDIUM";
              case "MEDIUM" -> "LOW";
              default -> "LOW";
            };
        healthySampleStreak = 0;
      }
      nextSampleAt =
          observedAt.plusSeconds(
              switch (samplingTier) {
                case "DEEP" -> 15;
                case "HIGH" -> 30;
                case "MEDIUM" -> 120;
                default -> 300;
              });
    }
    updatedAt = observedAt;
  }

  public String getExtensionId() {
    return extensionId;
  }

  public String getDisplayName() {
    return displayName;
  }

  public int getStaticCpuWeight() {
    return staticCpuWeight;
  }

  public int getStaticMemoryWeight() {
    return staticMemoryWeight;
  }

  public int getStartupWeight() {
    return startupWeight;
  }

  public int getPageInjectionWeight() {
    return pageInjectionWeight;
  }

  public int getServiceWorkerWeight() {
    return serviceWorkerWeight;
  }

  public int getCryptoWeight() {
    return cryptoWeight;
  }

  public int getNetworkWeight() {
    return networkWeight;
  }

  public BigDecimal getObservedMultiplier() {
    return observedMultiplier;
  }

  public BigDecimal getConfidence() {
    return confidence;
  }

  public String getProfileState() {
    return profileState;
  }

  public boolean isWeb3() {
    return web3;
  }

  public boolean isServiceWorker() {
    return serviceWorker;
  }

  public boolean isCrypto() {
    return crypto;
  }

  public boolean isPrivileged() {
    return privileged;
  }

  public long getSamples() {
    return samples;
  }

  public Integer getP95CpuMillis() {
    return p95CpuMillis;
  }

  public Integer getP95MemoryMib() {
    return p95MemoryMib;
  }

  public Instant getLastProfiledAt() {
    return lastProfiledAt;
  }

  public String getSamplingTier() {
    return samplingTier;
  }

  public int getHealthySampleStreak() {
    return healthySampleStreak;
  }

  public Instant getNextSampleAt() {
    return nextSampleAt;
  }

  public int getSamplingCpuBudgetMillis() {
    return samplingCpuBudgetMillis;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
