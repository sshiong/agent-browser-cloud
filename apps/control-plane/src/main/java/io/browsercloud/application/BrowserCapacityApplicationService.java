package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.api.BrowserNodeListResponse;
import io.browsercloud.api.BrowserNodeView;
import io.browsercloud.api.BrowserPlacementView;
import io.browsercloud.api.ExtensionProfileListResponse;
import io.browsercloud.api.ExtensionProfileView;
import io.browsercloud.api.RecordNodePressureRequest;
import io.browsercloud.api.RegisterBrowserNodeRequest;
import io.browsercloud.api.UpsertExtensionProfileRequest;
import io.browsercloud.coordinator.SessionRepository;
import io.browsercloud.domain.capacity.BrowserResourceBudget;
import io.browsercloud.domain.session.ResourceClass;
import io.browsercloud.domain.session.SessionContext;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.persistence.BrowserPlacementEntity;
import io.browsercloud.persistence.BrowserPlacementJpaRepository;
import io.browsercloud.persistence.ExtensionProfileEntity;
import io.browsercloud.persistence.ExtensionProfileJpaRepository;
import io.browsercloud.persistence.SessionResourceDemandEntity;
import io.browsercloud.persistence.SessionResourceDemandJpaRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Browser Density 的权威资源画像、Placement、Admission 与压力治理。 */
@Service
public class BrowserCapacityApplicationService {

  private static final Duration NODE_HEARTBEAT_TTL = Duration.ofSeconds(60);
  private static final int UNKNOWN_EXTENSION_CPU_MILLIS = 150;
  private static final int UNKNOWN_EXTENSION_MEMORY_MIB = 256;
  private static final int UNKNOWN_EXTENSION_PER_NODE_LIMIT = 2;
  private static final Set<String> ACTIVE_PLACEMENT_STATES = Set.of("RESERVED", "ACTIVE");

  private final BrowserNodeJpaRepository nodeRepository;
  private final ExtensionProfileJpaRepository extensionRepository;
  private final SessionResourceDemandJpaRepository demandRepository;
  private final BrowserPlacementJpaRepository placementRepository;
  private final SessionRepository sessionRepository;
  private final ObjectMapper objectMapper;

  public BrowserCapacityApplicationService(
      BrowserNodeJpaRepository nodeRepository,
      ExtensionProfileJpaRepository extensionRepository,
      SessionResourceDemandJpaRepository demandRepository,
      BrowserPlacementJpaRepository placementRepository,
      SessionRepository sessionRepository,
      ObjectMapper objectMapper) {
    this.nodeRepository = nodeRepository;
    this.extensionRepository = extensionRepository;
    this.demandRepository = demandRepository;
    this.placementRepository = placementRepository;
    this.sessionRepository = sessionRepository;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public BrowserNodeView registerNode(
      String nodeId, RegisterBrowserNodeRequest request, Instant now) {
    validateNodeId(nodeId);
    if (request.supportsGpu() && request.certifiedGpuSlots() == 0) {
      throw new IllegalArgumentException("GPU-capable node must certify at least one GPU slot");
    }
    var labels = writeJson(request.labels() == null ? Map.of() : request.labels());
    var existing = nodeRepository.findForUpdate(nodeId);
    var node =
        existing.orElseGet(
            () ->
                new BrowserNodeEntity(
                    nodeId,
                    request.region(),
                    request.grpcTarget(),
                    request.certifiedCpuMillis(),
                    request.certifiedMemoryMib(),
                    request.certifiedPidCount(),
                    request.certifiedGpuSlots(),
                    request.safetyMarginPercent(),
                    request.maxSessions(),
                    request.supportsDesktop(),
                    request.supportsGpu(),
                    request.supportsNativeOs(),
                    request.isolationCapable(),
                    labels,
                    now));
    if (existing.isPresent()) {
      node.updateRegistration(
          request.region(),
          request.grpcTarget(),
          request.certifiedCpuMillis(),
          request.certifiedMemoryMib(),
          request.certifiedPidCount(),
          request.certifiedGpuSlots(),
          request.safetyMarginPercent(),
          request.maxSessions(),
          request.supportsDesktop(),
          request.supportsGpu(),
          request.supportsNativeOs(),
          request.isolationCapable(),
          labels,
          now);
    }
    return toNodeView(nodeRepository.save(node));
  }

  @Transactional(readOnly = true)
  public BrowserNodeListResponse listNodes() {
    var items = nodeRepository.findAllByOrderByNodeIdAsc().stream().map(this::toNodeView).toList();
    return new BrowserNodeListResponse(items, items.size());
  }

  @Transactional
  public BrowserNodeView recordPressure(
      String nodeId, RecordNodePressureRequest request, Instant now) {
    var node =
        nodeRepository
            .findForUpdate(nodeId)
            .orElseThrow(() -> new BrowserNodeNotFoundException(nodeId));
    node.recordPressure(
        request.memoryPsiSomeAvg10(),
        request.memoryPsiFullAvg10(),
        request.cpuPsiSomeAvg10(),
        request.ioPsiFullAvg10(),
        request.reason(),
        now);
    return toNodeView(nodeRepository.save(node));
  }

  @Transactional
  public ExtensionProfileView upsertExtension(
      String extensionId, UpsertExtensionProfileRequest request, Instant now) {
    validateExtensionId(extensionId);
    if (request.profileState().equals("CERTIFIED")
        && (request.confidence().compareTo(new BigDecimal("0.8000")) < 0)) {
      throw new ExtensionProfileRejectedException("CERTIFIED extension requires confidence >= 0.8");
    }
    var extension =
        extensionRepository
            .findById(extensionId)
            .orElseGet(
                () ->
                    new ExtensionProfileEntity(
                        extensionId,
                        request.displayName().strip(),
                        request.staticCpuWeight(),
                        request.staticMemoryWeight(),
                        request.startupWeight(),
                        request.pageInjectionWeight(),
                        request.serviceWorkerWeight(),
                        request.cryptoWeight(),
                        request.networkWeight(),
                        request.observedMultiplier(),
                        request.confidence(),
                        request.profileState(),
                        request.web3(),
                        request.serviceWorker(),
                        request.crypto(),
                        request.privileged(),
                        now));
    if (extensionRepository.existsById(extensionId)) {
      extension.update(
          request.displayName().strip(),
          request.staticCpuWeight(),
          request.staticMemoryWeight(),
          request.startupWeight(),
          request.pageInjectionWeight(),
          request.serviceWorkerWeight(),
          request.cryptoWeight(),
          request.networkWeight(),
          request.observedMultiplier(),
          request.confidence(),
          request.profileState(),
          request.web3(),
          request.serviceWorker(),
          request.crypto(),
          request.privileged(),
          now);
    }
    return toExtensionView(extensionRepository.save(extension));
  }

  @Transactional(readOnly = true)
  public ExtensionProfileListResponse listExtensions() {
    var items =
        extensionRepository.findAllByOrderByExtensionIdAsc().stream()
            .map(BrowserCapacityApplicationService::toExtensionView)
            .toList();
    return new ExtensionProfileListResponse(items, items.size());
  }

  @Transactional
  public void recordDemand(
      String sessionId,
      String tenantId,
      ResourceClass requestedClass,
      int requestedTabs,
      int agentActionsPerMinute,
      boolean remoteDesktop,
      boolean web3Workload,
      List<String> extensionIds,
      Instant now) {
    var normalizedExtensions = normalizeExtensionIds(extensionIds);
    demandRepository.save(
        new SessionResourceDemandEntity(
            sessionId,
            tenantId,
            requestedClass,
            requestedTabs,
            agentActionsPerMinute,
            remoteDesktop,
            web3Workload,
            writeJson(normalizedExtensions),
            now));
  }

  /** 在同一事务内锁定候选 Node、执行反亲和打分、预留资源并提交 Session Context。 */
  @Transactional
  public BrowserPlacementView reserve(SessionContext session, String region) {
    var existing = placementRepository.findForUpdate(session.sessionId());
    if (existing.isPresent() && !existing.orElseThrow().getState().equals("RELEASED")) {
      return toPlacementView(existing.orElseThrow());
    }
    var demand =
        demandRepository
            .findById(session.sessionId())
            .orElseThrow(() -> new BrowserCapacityUnavailableException("RESOURCE_DEMAND_MISSING"));
    if (!demand.getTenantId().equals(session.tenantId())) {
      throw new BrowserCapacityUnavailableException("RESOURCE_DEMAND_TENANT_MISMATCH");
    }
    var calculated = calculateDemand(demand);
    var now = Instant.now();
    var candidates =
        nodeRepository.lockPlacementCandidates(region, now.minus(NODE_HEARTBEAT_TTL)).stream()
            .map(node -> scoreCandidate(node, session.tenantId(), calculated))
            .filter(Candidate::eligible)
            .sorted(Comparator.comparingInt(Candidate::score))
            .toList();
    if (candidates.isEmpty()) {
      throw new BrowserCapacityUnavailableException("NO_ELIGIBLE_BROWSER_NODE");
    }
    var chosen = candidates.getFirst();
    var node = chosen.node();
    node.reserve(
        calculated.cpuMillis(),
        calculated.memoryRequestMib(),
        calculated.pidLimit(),
        calculated.requiresGpu() ? 1 : 0,
        now);
    nodeRepository.save(node);
    if (existing.isPresent()) {
      placementRepository.delete(existing.orElseThrow());
      placementRepository.flush();
    }
    var placement =
        new BrowserPlacementEntity(
            session.sessionId(),
            session.tenantId(),
            node.getNodeId(),
            demand.resourceClass(),
            calculated.effectiveClass(),
            writeJson(calculated.extensionIds()),
            calculated.unknownExtensionCount(),
            calculated.cpuMillis(),
            calculated.memoryRequestMib(),
            calculated.memoryLimitMib(),
            calculated.pidLimit(),
            calculated.tabBudget(),
            calculated.requiresDesktop(),
            calculated.requiresGpu(),
            calculated.requiresNativeOs(),
            calculated.requiresIsolation(),
            chosen.score(),
            writeJson(calculated.reasonCodes()),
            now);
    placementRepository.save(placement);
    var placedSession =
        session.withPlacement(
            node.getNodeId(), calculated.effectiveClass(), session.contextEpoch() + 1);
    sessionRepository.updateWithExpectedEpoch(placedSession, session.contextEpoch());
    return toPlacementView(placement);
  }

  @Transactional
  public void activate(String sessionId) {
    placementRepository
        .findForUpdate(sessionId)
        .ifPresent(
            placement -> {
              placement.activate(Instant.now());
              placementRepository.save(placement);
            });
  }

  @Transactional
  public void release(String sessionId) {
    var placement = placementRepository.findForUpdate(sessionId);
    if (placement.isEmpty() || placement.orElseThrow().getState().equals("RELEASED")) {
      return;
    }
    var current = placement.orElseThrow();
    var node =
        nodeRepository
            .findForUpdate(current.getNodeId())
            .orElseThrow(() -> new BrowserNodeNotFoundException(current.getNodeId()));
    var now = Instant.now();
    if (current.release(now)) {
      node.release(
          current.getCpuMillis(),
          current.getMemoryRequestMib(),
          current.getPidLimit(),
          current.isRequiresGpu() ? 1 : 0,
          now);
      nodeRepository.save(node);
      placementRepository.save(current);
    }
  }

  @Transactional(readOnly = true)
  public BrowserPlacementView getPlacement(String sessionId, String tenantId) {
    var placement =
        placementRepository
            .findById(sessionId)
            .orElseThrow(() -> new BrowserPlacementNotFoundException(sessionId));
    if (!placement.getTenantId().equals(tenantId)) {
      throw new BrowserPlacementNotFoundException(sessionId);
    }
    return toPlacementView(placement);
  }

  private CalculatedDemand calculateDemand(SessionResourceDemandEntity demand) {
    if (demand.resourceClass() == ResourceClass.L0) {
      throw new BrowserCapacityUnavailableException("L0_DORMANT_CANNOT_START");
    }
    var extensionIds = readStringList(demand.getExtensionIds());
    var profiles =
        extensionRepository.findAllById(extensionIds).stream()
            .collect(Collectors.toMap(ExtensionProfileEntity::getExtensionId, Function.identity()));
    var unknownIds =
        extensionIds.stream().filter(id -> !profiles.containsKey(id)).collect(Collectors.toSet());
    var disabled =
        profiles.values().stream()
            .filter(profile -> profile.getProfileState().equals("DISABLED"))
            .map(ExtensionProfileEntity::getExtensionId)
            .sorted()
            .toList();
    if (!disabled.isEmpty()) {
      throw new ExtensionProfileRejectedException("disabled extension requested");
    }

    boolean web3 =
        demand.isWeb3Workload()
            || profiles.values().stream()
                .anyMatch(profile -> profile.isWeb3() || profile.isCrypto());
    boolean crypto = profiles.values().stream().anyMatch(ExtensionProfileEntity::isCrypto);
    boolean privileged = profiles.values().stream().anyMatch(ExtensionProfileEntity::isPrivileged);
    var effectiveClass = demand.resourceClass();
    var reasons = new ArrayList<String>();
    if (!unknownIds.isEmpty() && effectiveClass.ordinal() < ResourceClass.L2.ordinal()) {
      effectiveClass = ResourceClass.L2;
      reasons.add("UNKNOWN_EXTENSION_PROBATION");
    }
    if (web3 && effectiveClass.ordinal() < ResourceClass.L2.ordinal()) {
      effectiveClass = ResourceClass.L2;
      reasons.add("WEB3_PROMOTION");
    }
    if ((crypto || privileged || demand.isRemoteDesktop())
        && effectiveClass.ordinal() < ResourceClass.L3.ordinal()) {
      effectiveClass = ResourceClass.L3;
      reasons.add(
          crypto ? "CRYPTO_PROMOTION" : privileged ? "PRIVILEGED_PROMOTION" : "DESKTOP_PROMOTION");
    }

    int extensionCpu =
        profiles.values().stream().mapToInt(ExtensionProfileEntity::effectiveCpuMillis).sum()
            + unknownIds.size() * UNKNOWN_EXTENSION_CPU_MILLIS;
    int extensionMemory =
        profiles.values().stream().mapToInt(ExtensionProfileEntity::effectiveMemoryMib).sum()
            + unknownIds.size() * UNKNOWN_EXTENSION_MEMORY_MIB;
    int activityCpu = ((demand.getAgentActionsPerMinute() + 9) / 10) * 20;

    BrowserResourceBudget budget;
    int cpu;
    int memory;
    while (true) {
      budget = BrowserResourceBudget.of(effectiveClass);
      int excessTabs = Math.max(0, demand.getRequestedTabs() - budget.tabBudget());
      cpu =
          Math.addExact(
              budget.cpuMillis(), Math.addExact(extensionCpu, activityCpu + excessTabs * 40));
      memory =
          Math.addExact(budget.memoryRequestMib(), Math.addExact(extensionMemory, excessTabs * 64));
      boolean fits =
          demand.getRequestedTabs() <= budget.tabBudget()
              && (!demand.isRemoteDesktop() || budget.desktopAllowed())
              && cpu <= Math.max(1, budget.cpuMillis() * 2)
              && memory <= budget.memoryLimitMib();
      if (fits || effectiveClass == ResourceClass.L5) {
        break;
      }
      effectiveClass = BrowserResourceBudget.promote(effectiveClass);
      reasons.add("OBSERVED_WEIGHT_PROMOTION");
    }
    if (memory > budget.memoryLimitMib()) {
      throw new BrowserCapacityUnavailableException("RESOURCE_DEMAND_EXCEEDS_L5");
    }
    if (unknownIds.isEmpty()) {
      reasons.add("EXTENSIONS_PROFILED");
    }
    int memoryLimit = Math.max(memory, budget.memoryLimitMib());
    return new CalculatedDemand(
        effectiveClass,
        List.copyOf(extensionIds),
        unknownIds.size(),
        cpu,
        memory,
        memoryLimit,
        budget.pidLimit(),
        Math.max(budget.tabBudget(), demand.getRequestedTabs()),
        demand.isRemoteDesktop(),
        budget.gpuRequired(),
        budget.nativeOsRequired(),
        privileged,
        crypto,
        List.copyOf(reasons));
  }

  private Candidate scoreCandidate(
      BrowserNodeEntity node, String tenantId, CalculatedDemand demand) {
    var active =
        placementRepository.findAllByNodeIdAndStateIn(node.getNodeId(), ACTIVE_PLACEMENT_STATES);
    long probationSessions =
        active.stream().filter(placement -> placement.getUnknownExtensionCount() > 0).count();
    boolean containsIsolated =
        active.stream().anyMatch(BrowserPlacementEntity::isRequiresIsolation);
    boolean isolationEligible = demand.requiresIsolation() ? active.isEmpty() : !containsIsolated;
    boolean probationEligible =
        demand.unknownExtensionCount() == 0 || probationSessions < UNKNOWN_EXTENSION_PER_NODE_LIMIT;
    boolean capacityEligible =
        node.canReserve(
            demand.cpuMillis(),
            demand.memoryRequestMib(),
            demand.pidLimit(),
            demand.requiresGpu() ? 1 : 0,
            demand.requiresDesktop(),
            demand.requiresNativeOs(),
            demand.requiresIsolation());
    if (!isolationEligible || !probationEligible || !capacityEligible) {
      return new Candidate(node, Integer.MAX_VALUE, false);
    }

    int score =
        node.getActiveSessions() * 20
            + (node.getReservedMemoryMib() * 100 / Math.max(1, node.getCertifiedMemoryMib()));
    var requested = new HashSet<>(demand.extensionIds());
    for (var placement : active) {
      if (placement.getTenantId().equals(tenantId)) {
        score += 100;
      }
      var overlap = new HashSet<>(readStringList(placement.getExtensionIds()));
      overlap.retainAll(requested);
      score += overlap.size() * 200;
      if (demand.crypto() && overlap.size() > 0) {
        score += 300;
      }
    }
    return new Candidate(node, score, true);
  }

  private BrowserNodeView toNodeView(BrowserNodeEntity node) {
    return new BrowserNodeView(
        node.getNodeId(),
        node.getRegion(),
        node.getGrpcTarget(),
        node.getLifecycleState(),
        node.getAdmissionState(),
        node.getCertifiedCpuMillis(),
        node.getCertifiedMemoryMib(),
        node.getCertifiedPidCount(),
        node.getCertifiedGpuSlots(),
        node.getSafetyMarginPercent(),
        node.getReservedCpuMillis(),
        node.getReservedMemoryMib(),
        node.getReservedPidCount(),
        node.getReservedGpuSlots(),
        node.getActiveSessions(),
        node.getMaxSessions(),
        node.getMemoryPsiSomeAvg10(),
        node.getMemoryPsiFullAvg10(),
        node.getCpuPsiSomeAvg10(),
        node.getIoPsiFullAvg10(),
        node.getPressureState(),
        node.getPressureReason(),
        node.isSupportsDesktop(),
        node.isSupportsGpu(),
        node.isSupportsNativeOs(),
        node.isIsolationCapable(),
        readStringMap(node.getLabels()),
        node.getLastHeartbeatAt(),
        node.getUpdatedAt());
  }

  private static ExtensionProfileView toExtensionView(ExtensionProfileEntity extension) {
    return new ExtensionProfileView(
        extension.getExtensionId(),
        extension.getDisplayName(),
        extension.getStaticCpuWeight(),
        extension.getStaticMemoryWeight(),
        extension.getStartupWeight(),
        extension.getPageInjectionWeight(),
        extension.getServiceWorkerWeight(),
        extension.getCryptoWeight(),
        extension.getNetworkWeight(),
        extension.getObservedMultiplier(),
        extension.getConfidence(),
        extension.getProfileState(),
        extension.isWeb3(),
        extension.isServiceWorker(),
        extension.isCrypto(),
        extension.isPrivileged(),
        extension.getSamples(),
        extension.getP95CpuMillis(),
        extension.getP95MemoryMib(),
        extension.getLastProfiledAt(),
        extension.getUpdatedAt());
  }

  private BrowserPlacementView toPlacementView(BrowserPlacementEntity placement) {
    return new BrowserPlacementView(
        placement.getSessionId(),
        placement.getTenantId(),
        placement.getNodeId(),
        placement.requestedResourceClass(),
        placement.effectiveResourceClass(),
        readStringList(placement.getExtensionIds()),
        placement.getUnknownExtensionCount(),
        placement.getCpuMillis(),
        placement.getMemoryRequestMib(),
        placement.getMemoryLimitMib(),
        placement.getPidLimit(),
        placement.getTabBudget(),
        placement.isRequiresDesktop(),
        placement.isRequiresGpu(),
        placement.isRequiresNativeOs(),
        placement.isRequiresIsolation(),
        placement.getPlacementScore(),
        placement.getState(),
        readStringList(placement.getReasonCodes()),
        placement.getReservedAt(),
        placement.getActivatedAt(),
        placement.getReleasedAt());
  }

  private List<String> normalizeExtensionIds(List<String> extensionIds) {
    if (extensionIds == null || extensionIds.isEmpty()) {
      return List.of();
    }
    return extensionIds.stream()
        .map(String::strip)
        .peek(BrowserCapacityApplicationService::validateExtensionId)
        .distinct()
        .sorted()
        .toList();
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("capacity data cannot be serialized", exception);
    }
  }

  private List<String> readStringList(String json) {
    try {
      return List.copyOf(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("persisted capacity list is invalid", exception);
    }
  }

  private Map<String, String> readStringMap(String json) {
    try {
      return Map.copyOf(objectMapper.readValue(json, new TypeReference<Map<String, String>>() {}));
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("persisted capacity labels are invalid", exception);
    }
  }

  private static void validateNodeId(String nodeId) {
    if (nodeId == null || !nodeId.matches("^node_[a-zA-Z0-9_-]{1,123}$")) {
      throw new IllegalArgumentException("invalid Browser Node ID");
    }
  }

  private static void validateExtensionId(String extensionId) {
    if (extensionId == null || !extensionId.matches("^[a-zA-Z0-9_.-]{1,128}$")) {
      throw new IllegalArgumentException("invalid Extension ID");
    }
  }

  private record Candidate(BrowserNodeEntity node, int score, boolean eligible) {}

  private record CalculatedDemand(
      ResourceClass effectiveClass,
      List<String> extensionIds,
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
      boolean crypto,
      List<String> reasonCodes) {}

  public static final class BrowserCapacityUnavailableException extends RuntimeException {
    public BrowserCapacityUnavailableException(String reason) {
      super(reason);
    }
  }

  public static final class BrowserNodeNotFoundException extends RuntimeException {
    public BrowserNodeNotFoundException(String nodeId) {
      super(nodeId);
    }
  }

  public static final class BrowserPlacementNotFoundException extends RuntimeException {
    public BrowserPlacementNotFoundException(String sessionId) {
      super(sessionId);
    }
  }

  public static final class ExtensionProfileRejectedException extends RuntimeException {
    public ExtensionProfileRejectedException(String reason) {
      super(reason);
    }
  }
}
