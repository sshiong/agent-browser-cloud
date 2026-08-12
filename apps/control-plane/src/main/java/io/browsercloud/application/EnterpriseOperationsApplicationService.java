package io.browsercloud.application;

import static io.browsercloud.api.EnterpriseOperationsModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.browsercloud.application.AuditApplicationService.AuditRecord;
import io.browsercloud.domain.capacity.ResourceTemplate;
import io.browsercloud.domain.session.ResourceClass;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Phase 7 的 Validation、Cost、SLO、Compliance、GameDay 与 DR 权威服务。 */
@Service
public class EnterpriseOperationsApplicationService {

  private static final Set<String> AUTOMATED_GAMEDAY_SCENARIOS =
      Set.of(
          "POSTGRES_PRIMARY_FAILURE",
          "REDIS_TOTAL_LOSS",
          "MESSAGE_BUS_PARTITION",
          "BROWSER_NODE_POWER_LOSS",
          "OBJECT_STORAGE_UNAVAILABLE",
          "WARM_TIER_UNAVAILABLE",
          "KMS_UNAVAILABLE",
          "PROXY_PROVIDER_MASS_FAILURE",
          "COORDINATOR_SPLIT_BRAIN",
          "WORKFLOW_CALLBACK_LOSS",
          "RUNTIME_BUILD_ROLLBACK",
          "REGION_NETWORK_ISOLATION",
          "MEDIA_GATEWAY_FAILURE");
  private static final String GAMEDAY_VIEW_SELECT =
      """
      SELECT g.*,
             j.gameday_id AS job_gameday_id,
             j.scenario_code AS job_scenario_code,
             j.environment AS job_environment,
             j.required_worker_capabilities AS job_required_worker_capabilities,
             j.state AS job_state,
             j.current_stage AS job_current_stage,
             j.attempt AS job_attempt,
             j.maximum_attempts AS job_maximum_attempts,
             j.recovery_attempt AS job_recovery_attempt,
             j.maximum_recovery_attempts AS job_maximum_recovery_attempts,
             j.claim_owner AS job_claim_owner,
             j.claim_epoch AS job_claim_epoch,
             j.available_at AS job_available_at,
             j.lease_expires_at AS job_lease_expires_at,
             j.last_heartbeat_at AS job_last_heartbeat_at,
             j.abort_deadline AS job_abort_deadline,
             j.abort_requested AS job_abort_requested,
             j.fault_injected AS job_fault_injected,
             j.recovery_confirmed AS job_recovery_confirmed,
             j.failure_code AS job_failure_code,
             j.result_hash AS job_result_hash,
             j.updated_at AS job_updated_at
        FROM enterprise_recovery_gamedays g
        LEFT JOIN recovery_gameday_jobs j ON j.gameday_id = g.gameday_id
      """;

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final AuditApplicationService auditService;
  private final ReleaseFreezeApplicationService releaseFreezeService;
  private final RecoveryGameDayGovernanceApplicationService gameDayGovernance;
  private final String auditExportSigningKey;
  private final String auditExportSigningKeyId;
  private final boolean gameDayProductionEnabled;

  public EnterpriseOperationsApplicationService(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      AuditApplicationService auditService,
      ReleaseFreezeApplicationService releaseFreezeService,
      RecoveryGameDayGovernanceApplicationService gameDayGovernance,
      @Value("${enterprise.audit-export.signing-key:local-development-audit-export-key}")
          String auditExportSigningKey,
      @Value("${enterprise.audit-export.signing-key-id:local-development}") String signingKeyId,
      @Value("${enterprise.gameday-worker.production-enabled:false}")
          boolean gameDayProductionEnabled) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.auditService = auditService;
    this.releaseFreezeService = releaseFreezeService;
    this.gameDayGovernance = gameDayGovernance;
    this.auditExportSigningKey = auditExportSigningKey;
    this.auditExportSigningKeyId = signingKeyId;
    this.gameDayProductionEnabled = gameDayProductionEnabled;
  }

  @Transactional
  public RuntimeValidationView startValidation(
      StartRuntimeValidationRequest request, String actorId) {
    return enqueueValidation(request, actorId);
  }

  @Transactional
  public List<RuntimeValidationView> startValidationMatrix(
      StartRuntimeValidationMatrixRequest request, String actorId) {
    requireExists(
        "SELECT count(*) FROM runtime_builds WHERE build_id = ?",
        request.buildId(),
        "Runtime Build");
    return request.cells().stream()
        .map(
            cell ->
                enqueueValidation(
                    new StartRuntimeValidationRequest(
                        request.buildId(),
                        request.suiteVersion(),
                        cell.environmentDigest(),
                        request.replayDatasetId(),
                        request.persona(),
                        cell.browserEngine(),
                        cell.browserVersion(),
                        cell.operatingSystem(),
                        cell.architecture(),
                        cell.requiredWorkerCapabilities(),
                        cell.maximumAttempts()),
                    actorId))
        .toList();
  }

  private RuntimeValidationView enqueueValidation(
      StartRuntimeValidationRequest request, String actorId) {
    requireExists(
        "SELECT count(*) FROM runtime_builds WHERE build_id = ?",
        request.buildId(),
        "Runtime Build");
    var id = id("val_");
    var now = Instant.now();
    jdbc.update(
        """
        INSERT INTO runtime_validation_runs(
          validation_id, build_id, suite_version, environment_digest,
          replay_dataset_id, persona, state, requested_by, started_at
        ) VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
        """,
        id,
        request.buildId(),
        request.suiteVersion(),
        request.environmentDigest(),
        request.replayDatasetId(),
        request.persona(),
        actorId,
        sqlTime(now));
    jdbc.update(
        """
        INSERT INTO runtime_validation_jobs(
          validation_id, browser_engine, browser_version, operating_system, architecture,
          required_worker_capabilities, maximum_attempts, available_at, created_at, updated_at
        ) VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
        """,
        id,
        valueOrDefault(request.browserEngine(), "chromium"),
        valueOrDefault(request.browserVersion(), "stable"),
        valueOrDefault(request.operatingSystem(), "linux"),
        valueOrDefault(request.architecture(), "amd64"),
        json(
            request.requiredWorkerCapabilities() == null
                ? Map.of()
                : request.requiredWorkerCapabilities()),
        request.maximumAttempts() == null ? 3 : request.maximumAttempts(),
        sqlTime(now),
        sqlTime(now),
        sqlTime(now));
    appendValidationJobEvent(id, "ENQUEUED", null, "QUEUED", null, 0, 0, null, now);
    return requireValidation(id);
  }

  @Transactional
  public RuntimeValidationView completeValidation(
      String validationId, CompleteRuntimeValidationRequest request, String actorId) {
    if (request.requiredFailures() > request.requiredTests()
        || request.optionalFailures() > request.optionalTests()) {
      throw new IllegalArgumentException("validation failures cannot exceed test count");
    }
    lockValidationJobIfPresent(validationId);
    var current =
        jdbc
            .query(
                validationSelect() + " WHERE run.validation_id = ? FOR UPDATE OF run",
                this::validation,
                validationId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new EnterpriseResourceNotFoundException("Runtime Validation"));
    if (!current.state().equals("RUNNING")) {
      return current;
    }
    boolean capabilitiesMatch =
        new TreeMap<>(request.declaredCapabilities())
            .equals(new TreeMap<>(request.observedCapabilities()));
    boolean requiredPassed =
        request.requiredFailures() == 0 && capabilitiesMatch && request.personaConsistent();
    String state =
        !requiredPassed ? "FAILED" : request.optionalFailures() > 0 ? "DEGRADED" : "PASSED";
    var now = Instant.now();
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("validationId", validationId);
    evidence.put("buildId", current.buildId());
    evidence.put("suiteVersion", current.suiteVersion());
    evidence.put("environmentDigest", current.environmentDigest());
    evidence.put("replayDatasetId", current.replayDatasetId());
    evidence.put("persona", current.persona());
    evidence.put("requiredTests", request.requiredTests());
    evidence.put("requiredFailures", request.requiredFailures());
    evidence.put("optionalTests", request.optionalTests());
    evidence.put("optionalFailures", request.optionalFailures());
    evidence.put("personaConsistent", request.personaConsistent());
    evidence.put("capabilitiesMatch", capabilitiesMatch);
    evidence.put("state", state);
    evidence.put("declaredCapabilities", new TreeMap<>(request.declaredCapabilities()));
    evidence.put("observedCapabilities", new TreeMap<>(request.observedCapabilities()));
    evidence.put("optionalFailureCodes", request.optionalFailureCodes().stream().sorted().toList());
    var evidenceHash = hash(evidence);
    jdbc.update(
        """
        UPDATE runtime_validation_runs
        SET state = ?, required_tests = ?, required_failures = ?,
            optional_tests = ?, optional_failures = ?,
            declared_capabilities = CAST(? AS jsonb),
            observed_capabilities = CAST(? AS jsonb),
            optional_failure_codes = CAST(? AS jsonb),
            evidence_hash = ?, completed_at = ?
        WHERE validation_id = ?
        """,
        state,
        request.requiredTests(),
        request.requiredFailures(),
        request.optionalTests(),
        request.optionalFailures(),
        json(request.declaredCapabilities()),
        json(request.observedCapabilities()),
        json(request.optionalFailureCodes()),
        evidenceHash,
        sqlTime(now),
        validationId);
    jdbc.update(
        """
        UPDATE runtime_builds
        SET regression_status = ?,
            capabilities = CAST(? AS jsonb),
            validated_at = ?
        WHERE build_id = ?
        """,
        state.equals("FAILED") ? "QUARANTINED" : "STABLE",
        json(request.observedCapabilities()),
        sqlTime(now),
        current.buildId());
    audit(
        "platform-control",
        actorId,
        "RUNTIME_VALIDATION",
        validationId,
        "COMPLETE",
        state,
        Map.of("buildId", current.buildId(), "evidenceHash", evidenceHash));
    commitValidationJobIfPresent(validationId, actorId, evidenceHash, now);
    return requireValidation(validationId);
  }

  @Transactional(readOnly = true)
  public List<RuntimeValidationView> listValidations() {
    return jdbc.query(
        validationSelect() + " ORDER BY run.started_at DESC LIMIT 100", this::validation);
  }

  @Transactional(readOnly = true)
  public RuntimeValidationView getValidation(String validationId) {
    return requireValidation(validationId);
  }

  @Transactional
  public RuntimeValidationView failValidationExecution(
      String validationId, String failureCode, String actorId) {
    var current = requireValidationForUpdate(validationId);
    if (!current.state().equals("RUNNING")) {
      return current;
    }
    var now = Instant.now();
    var evidence =
        Map.<String, Object>of(
            "validationId",
            validationId,
            "buildId",
            current.buildId(),
            "state",
            "FAILED",
            "failureCode",
            failureCode);
    var evidenceHash = hash(evidence);
    jdbc.update(
        """
        UPDATE runtime_validation_runs
           SET state = 'FAILED', evidence_hash = ?, completed_at = ?
         WHERE validation_id = ? AND state = 'RUNNING'
        """,
        evidenceHash,
        sqlTime(now),
        validationId);
    jdbc.update(
        """
        UPDATE runtime_builds
           SET regression_status = 'QUARANTINED', validated_at = ?
         WHERE build_id = ?
        """,
        sqlTime(now),
        current.buildId());
    audit(
        "platform-control",
        actorId,
        "RUNTIME_VALIDATION",
        validationId,
        "WORKER_FAILED",
        "FAILED",
        Map.of(
            "buildId", current.buildId(),
            "failureCode", failureCode,
            "evidenceHash", evidenceHash));
    return requireValidation(validationId);
  }

  @Transactional
  public CostRateView createCostRate(CreateCostRateRequest request, String actorId) {
    requireExists(
        "SELECT count(*) FROM enterprise_regions WHERE region_id = ?", request.region(), "Region");
    var version = "price_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    var now = Instant.now();
    var template = ResourceTemplate.parse(request.resourceTemplate());
    jdbc.update(
        """
        INSERT INTO enterprise_cost_rates(
          pricing_version, region, resource_class, resource_template, base_hourly_usd,
          cpu_core_hourly_usd, memory_gib_hourly_usd, desktop_hourly_usd,
          remote_desktop_egress_gib_usd, gpu_hourly_usd, media_hourly_usd,
          effective_at, created_by, created_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        version,
        request.region(),
        template.legacyClass().name(),
        template.id(),
        request.baseHourlyUsd(),
        request.cpuCoreHourlyUsd(),
        request.memoryGibHourlyUsd(),
        request.desktopHourlyUsd(),
        request.remoteDesktopEgressGibUsd() == null
            ? BigDecimal.ZERO
            : request.remoteDesktopEgressGibUsd(),
        request.gpuHourlyUsd(),
        request.mediaHourlyUsd(),
        sqlTime(request.effectiveAt()),
        actorId,
        sqlTime(now));
    return requireCostRate(version);
  }

  @Transactional(readOnly = true)
  public List<CostRateView> listCostRates() {
    return jdbc.query(
        "SELECT * FROM enterprise_cost_rates ORDER BY effective_at DESC, pricing_version",
        this::costRate);
  }

  @Transactional(readOnly = true)
  public SessionCostExplanationView explainSessionCost(String sessionId, String tenantId) {
    var placement =
        jdbc
            .queryForList(
                """
                SELECT p.session_id, p.tenant_id, p.node_id, n.region,
                       p.effective_resource_class, p.cpu_millis, p.memory_request_mib,
                       p.requires_desktop, p.requires_gpu, p.requires_media, p.reserved_at
                FROM browser_placements p
                JOIN browser_nodes n ON n.node_id = p.node_id
                WHERE p.session_id = ? AND p.tenant_id = ?
                """,
                sessionId,
                tenantId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new EnterpriseResourceNotFoundException("Browser Placement"));
    var rate =
        jdbc
            .query(
                """
                SELECT * FROM enterprise_cost_rates
                WHERE region = ? AND resource_template = ? AND effective_at <= ?
                ORDER BY effective_at DESC LIMIT 1
                """,
                this::costRate,
                placement.get("region"),
                ResourceTemplate.from(
                        ResourceClass.valueOf((String) placement.get("effective_resource_class")))
                    .id(),
                placement.get("reserved_at"))
            .stream()
            .findFirst()
            .orElseThrow(() -> new EnterpriseResourceNotFoundException("Cost Rate"));
    int cpuMillis = ((Number) placement.get("cpu_millis")).intValue();
    int memoryMib = ((Number) placement.get("memory_request_mib")).intValue();
    boolean desktop = (Boolean) placement.get("requires_desktop");
    boolean gpu = (Boolean) placement.get("requires_gpu");
    boolean media = (Boolean) placement.get("requires_media");
    var cpu =
        rate.cpuCoreHourlyUsd()
            .multiply(BigDecimal.valueOf(cpuMillis))
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    var memory =
        rate.memoryGibHourlyUsd()
            .multiply(BigDecimal.valueOf(memoryMib))
            .divide(BigDecimal.valueOf(1024), 6, RoundingMode.HALF_UP);
    var desktopCost = desktop ? rate.desktopHourlyUsd() : BigDecimal.ZERO;
    var gpuCost = gpu ? rate.gpuHourlyUsd() : BigDecimal.ZERO;
    var mediaCost = media ? rate.mediaHourlyUsd() : BigDecimal.ZERO;
    var total =
        rate.baseHourlyUsd()
            .add(cpu)
            .add(memory)
            .add(desktopCost)
            .add(gpuCost)
            .add(mediaCost)
            .setScale(6, RoundingMode.HALF_UP);
    return new SessionCostExplanationView(
        sessionId,
        (String) placement.get("node_id"),
        (String) placement.get("region"),
        rate.resourceTemplate(),
        rate.pricingVersion(),
        cpuMillis,
        memoryMib,
        desktop,
        gpu,
        media,
        rate.baseHourlyUsd(),
        cpu,
        memory,
        desktopCost,
        gpuCost,
        mediaCost,
        total,
        Instant.now());
  }

  @Transactional
  public ErrorBudgetView upsertSlo(
      String tenantId, UpsertSloPolicyRequest request, String actorId) {
    var freezePolicy = resolveReleaseFreezePolicy(tenantId, request);
    jdbc.update(
        """
        INSERT INTO enterprise_slo_policies(
          tenant_id, availability_target, latency_p95_target_ms,
          window_minutes, release_freeze_enabled,
          release_freeze_burn_rate_threshold,
          release_recovery_burn_rate_threshold,
          release_freeze_window_minutes,
          release_recovery_stable_minutes,
          updated_by, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (tenant_id) DO UPDATE SET
          availability_target = EXCLUDED.availability_target,
          latency_p95_target_ms = EXCLUDED.latency_p95_target_ms,
          window_minutes = EXCLUDED.window_minutes,
          release_freeze_enabled = EXCLUDED.release_freeze_enabled,
          release_freeze_burn_rate_threshold = EXCLUDED.release_freeze_burn_rate_threshold,
          release_recovery_burn_rate_threshold = EXCLUDED.release_recovery_burn_rate_threshold,
          release_freeze_window_minutes = EXCLUDED.release_freeze_window_minutes,
          release_recovery_stable_minutes = EXCLUDED.release_recovery_stable_minutes,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        tenantId,
        request.availabilityTarget(),
        request.latencyP95TargetMs(),
        request.windowMinutes(),
        freezePolicy.enabled(),
        freezePolicy.freezeThreshold(),
        freezePolicy.recoveryThreshold(),
        freezePolicy.evaluationWindowMinutes(),
        freezePolicy.recoveryStableMinutes(),
        actorId);
    releaseFreezeService.evaluateTenant(tenantId, Instant.now());
    return errorBudget(tenantId);
  }

  @Transactional
  public ErrorBudgetView recordServiceLevelEvent(
      String tenantId, RecordServiceLevelEventRequest request) {
    requireSlo(tenantId);
    boolean excluded = request.exclusionCode() != null;
    if (excluded
        && count(
                """
                SELECT count(*) FROM enterprise_sla_exclusions
                WHERE tenant_id = ? AND exclusion_code = ? AND enabled = TRUE
                """,
                tenantId,
                request.exclusionCode())
            == 0) {
      throw new GovernanceRejectedException("SLA_EXCLUSION_NOT_CONFIGURED");
    }
    jdbc.update(
        """
        INSERT INTO enterprise_service_level_events(
          event_id, tenant_id, event_type, duration_seconds,
          latency_p95_ms, source, occurred_at, recorded_at,
          excluded_from_sla, exclusion_code
        ) VALUES (?, ?, ?, ?, ?, ?, ?, now(), ?, ?)
        """,
        id("sle_"),
        tenantId,
        request.eventType(),
        request.durationSeconds(),
        request.latencyP95Ms(),
        request.source(),
        sqlTime(request.occurredAt()),
        excluded,
        request.exclusionCode());
    releaseFreezeService.evaluateTenant(tenantId, Instant.now());
    return errorBudget(tenantId);
  }

  @Transactional(readOnly = true)
  public ReleaseFreezeView releaseFreeze(String tenantId) {
    return releaseFreezeService
        .current(tenantId)
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Release Freeze State"));
  }

  @Transactional
  public SlaExclusionView upsertSlaExclusion(
      String tenantId, String exclusionCode, UpsertSlaExclusionRequest request, String actorId) {
    jdbc.update(
        """
        INSERT INTO enterprise_sla_exclusions(
          tenant_id, exclusion_code, description, enabled, updated_by, updated_at
        ) VALUES (?, ?, ?, ?, ?, now())
        ON CONFLICT (tenant_id, exclusion_code) DO UPDATE SET
          description = EXCLUDED.description,
          enabled = EXCLUDED.enabled,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        tenantId,
        exclusionCode,
        request.description(),
        request.enabled(),
        actorId);
    return jdbc.query(
            """
            SELECT * FROM enterprise_sla_exclusions
            WHERE tenant_id = ? AND exclusion_code = ?
            """,
            this::slaExclusion,
            tenantId,
            exclusionCode)
        .getFirst();
  }

  @Transactional(readOnly = true)
  public List<SlaExclusionView> listSlaExclusions(String tenantId) {
    return jdbc.query(
        """
        SELECT * FROM enterprise_sla_exclusions
        WHERE tenant_id = ? ORDER BY exclusion_code
        """,
        this::slaExclusion,
        tenantId);
  }

  @Transactional(readOnly = true)
  public ErrorBudgetView errorBudget(String tenantId) {
    var policy = requireSlo(tenantId);
    var target = (BigDecimal) policy.get("availability_target");
    int windowMinutes = ((Number) policy.get("window_minutes")).intValue();
    int latencyTarget = ((Number) policy.get("latency_p95_target_ms")).intValue();
    var windowStart = Instant.now().minusSeconds(windowMinutes * 60L);
    long consumed =
        Optional.ofNullable(
                jdbc.queryForObject(
                    """
                    SELECT COALESCE(sum(duration_seconds), 0)
                    FROM enterprise_service_level_events
                    WHERE tenant_id = ? AND event_type = 'UNAVAILABLE'
                      AND excluded_from_sla = FALSE
                      AND occurred_at >= ?
                    """,
                    Long.class,
                    tenantId,
                    sqlTime(windowStart)))
            .orElse(0L);
    long allowed =
        BigDecimal.valueOf(windowMinutes * 60L)
            .multiply(BigDecimal.ONE.subtract(target))
            .setScale(0, RoundingMode.FLOOR)
            .longValue();
    long remaining = Math.max(0, allowed - consumed);
    var burn =
        allowed == 0
            ? (consumed == 0 ? BigDecimal.ZERO : new BigDecimal("999.000000"))
            : BigDecimal.valueOf(consumed)
                .divide(BigDecimal.valueOf(allowed), 6, RoundingMode.HALF_UP);
    return new ErrorBudgetView(
        tenantId,
        target,
        latencyTarget,
        windowMinutes,
        allowed,
        consumed,
        remaining,
        burn,
        consumed <= allowed ? "HEALTHY" : "EXHAUSTED",
        windowStart,
        Instant.now());
  }

  @Transactional
  public RetentionPolicyView upsertRetention(
      String tenantId, UpsertRetentionPolicyRequest request, String actorId) {
    requireExists(
        "SELECT count(*) FROM enterprise_regions WHERE region_id = ?",
        request.residencyRegion(),
        "Residency Region");
    jdbc.update(
        """
        INSERT INTO enterprise_retention_policies(
          tenant_id, data_class, retention_days, legal_hold,
          residency_region, updated_by, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (tenant_id, data_class) DO UPDATE SET
          retention_days = EXCLUDED.retention_days,
          legal_hold = EXCLUDED.legal_hold,
          residency_region = EXCLUDED.residency_region,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        tenantId,
        request.dataClass(),
        request.retentionDays(),
        request.legalHold(),
        request.residencyRegion(),
        actorId);
    return jdbc.query(
            """
            SELECT * FROM enterprise_retention_policies
            WHERE tenant_id = ? AND data_class = ?
            """,
            this::retention,
            tenantId,
            request.dataClass())
        .getFirst();
  }

  @Transactional(readOnly = true)
  public List<RetentionPolicyView> listRetention(String tenantId) {
    return jdbc.query(
        """
        SELECT * FROM enterprise_retention_policies
        WHERE tenant_id = ? ORDER BY data_class
        """,
        this::retention,
        tenantId);
  }

  @Transactional
  public DeletionReceiptView createDeletionReceipt(
      String tenantId, CreateDeletionReceiptRequest request, String actorId) {
    var policy =
        jdbc
            .queryForList(
                """
                SELECT * FROM enterprise_retention_policies
                WHERE tenant_id = ? AND data_class = ? FOR UPDATE
                """,
                tenantId,
                request.dataClass())
            .stream()
            .findFirst()
            .orElseThrow(() -> new EnterpriseResourceNotFoundException("Retention Policy"));
    if ((Boolean) policy.get("legal_hold")) {
      throw new GovernanceRejectedException("LEGAL_HOLD_ACTIVE");
    }
    var id = id("del_");
    var now = Instant.now();
    var policyUpdatedAt = ((java.sql.Timestamp) policy.get("updated_at")).toInstant();
    var receiptHash =
        hash(
            Map.of(
                "receiptId",
                id,
                "tenantId",
                tenantId,
                "dataClass",
                request.dataClass(),
                "objectId",
                request.objectId(),
                "contentDigest",
                request.contentDigest(),
                "policyUpdatedAt",
                policyUpdatedAt.toString(),
                "deletedBy",
                actorId,
                "deletedAt",
                now.toString()));
    jdbc.update(
        """
        INSERT INTO enterprise_retention_deletion_receipts(
          receipt_id, tenant_id, data_class, object_id, content_digest,
          policy_updated_at, receipt_hash, deleted_by, deleted_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id,
        tenantId,
        request.dataClass(),
        request.objectId(),
        request.contentDigest(),
        sqlTime(policyUpdatedAt),
        receiptHash,
        actorId,
        sqlTime(now));
    audit(
        tenantId,
        actorId,
        "RETENTION_DELETION",
        id,
        "DELETE_WITH_RECEIPT",
        "SUCCEEDED",
        Map.of("dataClass", request.dataClass(), "receiptHash", receiptHash));
    return requireDeletionReceipt(id, tenantId);
  }

  @Transactional
  public LicenseInventoryView upsertLicense(
      String componentId, UpsertLicenseInventoryRequest request, String actorId) {
    var evidenceHash =
        hash(
            Map.of(
                "componentId",
                componentId,
                "componentType",
                request.componentType(),
                "componentName",
                request.componentName(),
                "componentVersion",
                request.componentVersion(),
                "licenseId",
                request.licenseId(),
                "sourceUrl",
                request.sourceUrl(),
                "approved",
                request.approved()));
    jdbc.update(
        """
        INSERT INTO enterprise_license_inventory(
          component_id, component_type, component_name, component_version,
          license_id, source_url, approved, evidence_hash, updated_by, updated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, now())
        ON CONFLICT (component_id) DO UPDATE SET
          component_type = EXCLUDED.component_type,
          component_name = EXCLUDED.component_name,
          component_version = EXCLUDED.component_version,
          license_id = EXCLUDED.license_id,
          source_url = EXCLUDED.source_url,
          approved = EXCLUDED.approved,
          evidence_hash = EXCLUDED.evidence_hash,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        componentId,
        request.componentType(),
        request.componentName(),
        request.componentVersion(),
        request.licenseId(),
        request.sourceUrl(),
        request.approved(),
        evidenceHash,
        actorId);
    return requireLicense(componentId);
  }

  @Transactional(readOnly = true)
  public List<LicenseInventoryView> listLicenses() {
    return jdbc.query(
        "SELECT * FROM enterprise_license_inventory ORDER BY component_type, component_id",
        this::license);
  }

  @Transactional
  public AuditExportManifestView generateAuditExport(
      String tenantId, Long fromSequence, Long toSequence, String actorId) {
    long from = fromSequence == null ? 1 : fromSequence;
    long to = toSequence == null ? Long.MAX_VALUE : toSequence;
    if (from <= 0 || to < from) {
      throw new IllegalArgumentException("invalid audit export sequence range");
    }
    var events =
        jdbc.queryForList(
            """
            SELECT sequence_no, event_hash FROM audit_events
            WHERE tenant_id = ? AND sequence_no BETWEEN ? AND ?
              AND event_hash IS NOT NULL
            ORDER BY sequence_no
            """,
            tenantId,
            from,
            to);
    if (events.isEmpty()) {
      throw new EnterpriseResourceNotFoundException("Audit Export Range");
    }
    long actualFrom = ((Number) events.getFirst().get("sequence_no")).longValue();
    long actualTo = ((Number) events.getLast().get("sequence_no")).longValue();
    String firstHash = (String) events.getFirst().get("event_hash");
    String lastHash = (String) events.getLast().get("event_hash");
    var id = id("aex_");
    var now = Instant.now();
    var manifestHash =
        hash(
            Map.of(
                "exportId",
                id,
                "tenantId",
                tenantId,
                "fromSequence",
                actualFrom,
                "toSequence",
                actualTo,
                "eventCount",
                events.size(),
                "firstEventHash",
                firstHash,
                "lastEventHash",
                lastHash,
                "generatedAt",
                now.toString()));
    var signature = hmac(manifestHash);
    jdbc.update(
        """
        INSERT INTO enterprise_audit_export_manifests(
          export_id, tenant_id, from_sequence, to_sequence, event_count,
          first_event_hash, last_event_hash, manifest_hash, signature_algorithm,
          signing_key_id, signature, generated_by, generated_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'HMAC-SHA256', ?, ?, ?, ?)
        """,
        id,
        tenantId,
        actualFrom,
        actualTo,
        events.size(),
        firstHash,
        lastHash,
        manifestHash,
        auditExportSigningKeyId,
        signature,
        actorId,
        sqlTime(now));
    return requireAuditExport(id, tenantId);
  }

  @Transactional
  public RegionView upsertRegion(String regionId, UpsertRegionRequest request, String actorId) {
    var existingPrimary =
        Optional.ofNullable(
                jdbc.queryForObject(
                    "SELECT count(*) FROM enterprise_regions WHERE role = 'PRIMARY' AND region_id <> ?",
                    Integer.class,
                    regionId))
            .orElse(0);
    if (request.role().equals("PRIMARY") && existingPrimary > 0) {
      throw new IllegalStateException("a primary region already exists");
    }
    jdbc.update(
        """
        INSERT INTO enterprise_regions(
          region_id, role, admission_state, replication_lag_seconds,
          last_verified_at, updated_by
        ) VALUES (?, ?, ?, ?, now(), ?)
        ON CONFLICT (region_id) DO UPDATE SET
          role = EXCLUDED.role,
          admission_state = EXCLUDED.admission_state,
          replication_lag_seconds = EXCLUDED.replication_lag_seconds,
          last_verified_at = EXCLUDED.last_verified_at,
          updated_by = EXCLUDED.updated_by
        """,
        regionId,
        request.role(),
        request.admissionState(),
        request.replicationLagSeconds(),
        actorId);
    return requireRegion(regionId);
  }

  @Transactional(readOnly = true)
  public List<RegionView> listRegions() {
    return jdbc.query("SELECT * FROM enterprise_regions ORDER BY region_id", this::region);
  }

  @Transactional
  public RecoveryGameDayView startGameDay(StartRecoveryGameDayRequest request, String actorId) {
    if (request.sourceRegion().equals(request.targetRegion())) {
      throw new IllegalArgumentException("source and target regions must differ");
    }
    requireRegion(request.sourceRegion());
    requireRegion(request.targetRegion());
    var executionMode = valueOrDefault(request.executionMode(), "MANUAL");
    var environment = valueOrDefault(request.environment(), "TEST");
    var blastRadius =
        request.blastRadius() == null
            ? new RecoveryGameDayBlastRadiusRequest("TEST_FIXTURE", 1, List.of())
            : request.blastRadius();
    var maximumDurationSeconds =
        request.maximumDurationSeconds() == null ? 900 : request.maximumDurationSeconds();
    var maximumAttempts = request.maximumAttempts() == null ? 3 : request.maximumAttempts();
    var requiredCapabilities =
        request.requiredWorkerCapabilities() == null
            ? Map.of("faultInjection", true, "recovery", true, "measurement", true)
            : Map.copyOf(request.requiredWorkerCapabilities());
    if (blastRadius.targetIds().size() > blastRadius.maximumTargets()) {
      throw new IllegalArgumentException("GameDay target count exceeds the declared blast radius");
    }
    if ("TEST".equals(environment) && !"TEST_FIXTURE".equals(blastRadius.scope())) {
      throw new IllegalArgumentException("TEST GameDay blast radius must be TEST_FIXTURE");
    }
    if ("AUTO".equals(executionMode)) {
      if (blastRadius.targetIds().isEmpty()) {
        throw new IllegalArgumentException(
            "automated GameDay requires at least one bounded target");
      }
      if (!AUTOMATED_GAMEDAY_SCENARIOS.contains(request.scenario())) {
        throw new IllegalArgumentException("unsupported automated GameDay scenario");
      }
      if (requiredCapabilities.values().stream().anyMatch(value -> !Boolean.TRUE.equals(value))) {
        throw new IllegalArgumentException("GameDay required capabilities must be true");
      }
      if ("PRODUCTION".equals(environment)) {
        requireProductionGameDayApproval(
            request.approvalRequestId(), request.targetRegion(), actorId);
      }
    }
    var id = id("gameday_");
    var now = Instant.now();
    var initialState = "AUTO".equals(executionMode) ? "QUEUED" : "RUNNING";
    var initialStage = "AUTO".equals(executionMode) ? "QUEUED" : "MANUAL";
    jdbc.update(
        """
        INSERT INTO enterprise_recovery_gamedays(
          gameday_id, scenario, source_region, target_region, state,
          rto_target_seconds, rpo_target_seconds, started_by, started_at,
          execution_mode, environment, blast_radius, maximum_duration_seconds,
          approval_request_id, current_stage
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)
        """,
        id,
        request.scenario(),
        request.sourceRegion(),
        request.targetRegion(),
        initialState,
        request.rtoTargetSeconds(),
        request.rpoTargetSeconds(),
        actorId,
        sqlTime(now),
        executionMode,
        environment,
        json(blastRadius),
        maximumDurationSeconds,
        request.approvalRequestId(),
        initialStage);
    if ("AUTO".equals(executionMode)) {
      jdbc.update(
          """
          INSERT INTO recovery_gameday_jobs(
            gameday_id, scenario_code, environment, required_worker_capabilities,
            state, current_stage, maximum_attempts, available_at, abort_deadline,
            created_at, updated_at
          ) VALUES (?, ?, ?, CAST(? AS jsonb), 'QUEUED', 'QUEUED', ?, ?, ?, ?, ?)
          """,
          id,
          request.scenario(),
          environment,
          json(requiredCapabilities),
          maximumAttempts,
          sqlTime(now),
          sqlTime(now.plusSeconds(maximumDurationSeconds)),
          sqlTime(now),
          sqlTime(now));
      appendGameDayJobEvent(id, "ENQUEUED", null, "QUEUED", "QUEUED", actorId, 0, 0, null, now);
    }
    audit(
        "platform-control",
        actorId,
        "RECOVERY_GAMEDAY",
        id,
        "START",
        initialState,
        Map.of(
            "executionMode",
            executionMode,
            "environment",
            environment,
            "blastRadius",
            blastRadius.scope(),
            "maximumTargets",
            blastRadius.maximumTargets()));
    return requireGameDay(id);
  }

  @Transactional
  public RecoveryGameDayView completeGameDay(
      String gameDayId, CompleteRecoveryGameDayRequest request, String actorId) {
    var existing = requireGameDay(gameDayId);
    if (!"MANUAL".equals(existing.executionMode())) {
      throw new IllegalArgumentException(
          "automated Recovery GameDay results must be submitted by a GameDay Worker");
    }
    return completeGameDayInternal(gameDayId, request, actorId);
  }

  @Transactional
  public RecoveryGameDayView completeGameDayExecution(
      String gameDayId, CompleteRecoveryGameDayRequest request, String actorId) {
    if (!Boolean.TRUE.equals(request.recoveryConfirmed())) {
      throw new IllegalArgumentException("GameDay recovery must be confirmed before completion");
    }
    return completeGameDayInternal(gameDayId, request, actorId);
  }

  private RecoveryGameDayView completeGameDayInternal(
      String gameDayId, CompleteRecoveryGameDayRequest request, String actorId) {
    var run =
        jdbc
            .query(
                GAMEDAY_VIEW_SELECT + " WHERE g.gameday_id = ? FOR UPDATE OF g",
                this::gameDay,
                gameDayId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new EnterpriseResourceNotFoundException("Recovery GameDay"));
    if (!run.state().equals("RUNNING")) {
      return run;
    }
    var target = requireRegion(run.targetRegion());
    boolean passed =
        request.observedRtoSeconds() <= run.rtoTargetSeconds()
            && request.observedRpoSeconds() <= run.rpoTargetSeconds()
            && request.dataLossRecords() == 0
            && (request.staleOperationCount() == null || request.staleOperationCount() == 0)
            && target.replicationLagSeconds() <= run.rpoTargetSeconds();
    var evidence = new LinkedHashMap<String, Object>();
    evidence.put("gameDayId", gameDayId);
    evidence.put("scenario", run.scenario());
    evidence.put("sourceRegion", run.sourceRegion());
    evidence.put("targetRegion", run.targetRegion());
    evidence.put("environment", run.environment());
    evidence.put("blastRadius", run.blastRadius());
    evidence.put("observedRtoSeconds", request.observedRtoSeconds());
    evidence.put("observedRpoSeconds", request.observedRpoSeconds());
    evidence.put("dataLossRecords", request.dataLossRecords());
    evidence.put("replicationLagSeconds", target.replicationLagSeconds());
    evidence.put("detectionTimeSeconds", request.detectionTimeSeconds());
    evidence.put("failoverTimeSeconds", request.failoverTimeSeconds());
    evidence.put("staleOperationCount", request.staleOperationCount());
    evidence.put("userImpactCount", request.userImpactCount());
    evidence.put("manualSteps", request.manualSteps());
    evidence.put("runbookAccuracyPercent", request.runbookAccuracyPercent());
    evidence.put("runnerEvidenceHash", request.runnerEvidenceHash());
    evidence.put("recoveryConfirmed", request.recoveryConfirmed());
    evidence.put("passed", passed);
    var evidenceHash = hash(evidence);
    jdbc.update(
        """
        UPDATE enterprise_recovery_gamedays
        SET state = ?, observed_rto_seconds = ?, observed_rpo_seconds = ?,
            data_loss_records = ?, evidence_hash = ?, completed_at = now(),
            current_stage = ?, recovery_confirmed = TRUE,
            failure_code = CASE WHEN ? THEN NULL ELSE 'RECOVERY_OBJECTIVES_MISSED' END
        WHERE gameday_id = ?
        """,
        passed ? "PASSED" : "FAILED",
        request.observedRtoSeconds(),
        request.observedRpoSeconds(),
        request.dataLossRecords(),
        evidenceHash,
        passed ? "COMMITTED" : "FAILED",
        passed,
        gameDayId);
    if (!passed) {
      gameDayGovernance.ensureRemediation(
          gameDayId, run.scenario(), run.environment(), "RECOVERY_OBJECTIVES_MISSED", actorId);
    }
    audit(
        "platform-control",
        actorId,
        "RECOVERY_GAMEDAY",
        gameDayId,
        "COMPLETE",
        passed ? "PASSED" : "FAILED",
        Map.of("evidenceHash", evidenceHash));
    return requireGameDay(gameDayId);
  }

  @Transactional
  public RecoveryGameDayView failGameDayExecution(
      String gameDayId,
      String failureCode,
      boolean recoveryConfirmed,
      boolean aborted,
      String actorId) {
    var run = requireGameDay(gameDayId);
    if (List.of("PASSED", "FAILED", "ABORTED").contains(run.state())) {
      return run;
    }
    jdbc.update(
        """
        UPDATE enterprise_recovery_gamedays
           SET state = ?, current_stage = ?, failure_code = ?,
               recovery_confirmed = ?, completed_at = now(), abort_requested = ?
         WHERE gameday_id = ?
        """,
        aborted && recoveryConfirmed ? "ABORTED" : "FAILED",
        aborted && recoveryConfirmed ? "ABORTED" : "FAILED",
        failureCode,
        recoveryConfirmed,
        aborted,
        gameDayId);
    gameDayGovernance.ensureRemediation(
        gameDayId, run.scenario(), run.environment(), failureCode, actorId);
    audit(
        "platform-control",
        actorId,
        "RECOVERY_GAMEDAY",
        gameDayId,
        aborted ? "ABORT" : "FAIL",
        recoveryConfirmed ? "RECOVERY_CONFIRMED" : "RECOVERY_UNKNOWN",
        Map.of("failureCode", failureCode));
    return requireGameDay(gameDayId);
  }

  @Transactional(readOnly = true)
  public RecoveryGameDayView getGameDay(String gameDayId) {
    return requireGameDay(gameDayId);
  }

  @Transactional(readOnly = true)
  public List<RecoveryGameDayView> listGameDays() {
    return jdbc.query(GAMEDAY_VIEW_SELECT + " ORDER BY g.started_at DESC LIMIT 100", this::gameDay);
  }

  @Transactional
  public ComplianceSnapshotView generateCompliance(
      String tenantId, String framework, String actorId) {
    if (framework == null || !framework.matches("^[A-Za-z0-9_.-]{1,64}$")) {
      throw new IllegalArgumentException("invalid compliance framework");
    }
    var audit = auditService.verify(tenantId);
    var controls = new LinkedHashMap<String, Boolean>();
    controls.put("auditChainValid", audit.valid());
    controls.put(
        "runtimeValidationPassed",
        count(
                """
                SELECT count(*) FROM runtime_validation_runs
                WHERE state IN ('PASSED', 'DEGRADED')
                """)
            > 0);
    controls.put(
        "retentionPolicyConfigured",
        count("SELECT count(*) FROM enterprise_retention_policies WHERE tenant_id = ?", tenantId)
            > 0);
    controls.put(
        "keyRotationCompleted",
        count("SELECT count(*) FROM key_rotation_requests WHERE state = 'COMPLETED'") > 0);
    controls.put(
        "recoveryGameDayPassed",
        count("SELECT count(*) FROM enterprise_recovery_gamedays WHERE state = 'PASSED'") > 0);
    controls.put(
        "drRegionReady",
        count(
                """
                SELECT count(*) FROM enterprise_regions
                WHERE role IN ('SECONDARY', 'DR') AND admission_state = 'FAILOVER_READY'
                """)
            > 0);
    controls.put(
        "licenseInventoryApproved",
        count("SELECT count(*) FROM enterprise_license_inventory WHERE approved = FALSE") == 0
            && count("SELECT count(*) FROM enterprise_license_inventory") > 0);
    controls.put(
        "signedAuditExport",
        count(
                """
                SELECT count(*) FROM enterprise_audit_export_manifests
                WHERE tenant_id = ? AND signature_algorithm = 'HMAC-SHA256'
                """,
                tenantId)
            > 0);
    int passing = (int) controls.values().stream().filter(Boolean::booleanValue).count();
    var id = id("cmp_");
    var now = Instant.now();
    var evidenceHash =
        hash(
            Map.of(
                "snapshotId",
                id,
                "tenantId",
                tenantId,
                "framework",
                framework,
                "controls",
                controls,
                "auditHead",
                audit.headHash() == null ? "" : audit.headHash()));
    jdbc.update(
        """
        INSERT INTO enterprise_compliance_snapshots(
          snapshot_id, tenant_id, framework, control_count, passing_controls,
          evidence_hash, evidence, generated_by, generated_at
        ) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
        """,
        id,
        tenantId,
        framework,
        controls.size(),
        passing,
        evidenceHash,
        json(controls),
        actorId,
        sqlTime(now));
    audit(
        tenantId,
        actorId,
        "COMPLIANCE",
        id,
        "GENERATE_SNAPSHOT",
        passing == controls.size() ? "PASSED" : "PARTIAL",
        Map.of("framework", framework, "evidenceHash", evidenceHash));
    return requireCompliance(id);
  }

  @Transactional(readOnly = true)
  public EnterpriseOverviewResponse overview(String tenantId) {
    return new EnterpriseOverviewResponse(
        listValidations(),
        listCostRates(),
        optionalMediaQuota(tenantId).orElse(null),
        optionalErrorBudget(tenantId).orElse(null),
        releaseFreezeService.current(tenantId).orElse(null),
        listSlaExclusions(tenantId),
        listRetention(tenantId),
        listLicenses(),
        listRegions(),
        listGameDays(),
        gameDayGovernance.trends(90),
        gameDayGovernance.listRemediations(null),
        latestCompliance(tenantId).orElse(null),
        Instant.now());
  }

  /** 隔离/能力 Gate 先执行后才加入成本分，成本永远不能使不合格 Node 变为可选。 */
  @Transactional(readOnly = true)
  public int placementCostScore(String region, String resourceClass) {
    var resourceTemplate = ResourceTemplate.from(ResourceClass.valueOf(resourceClass)).id();
    return jdbc
        .query(
            """
            SELECT base_hourly_usd + cpu_core_hourly_usd + memory_gib_hourly_usd AS score
            FROM enterprise_cost_rates
            WHERE region = ? AND resource_template = ? AND effective_at <= now()
            ORDER BY effective_at DESC LIMIT 1
            """,
            (result, row) ->
                result.getBigDecimal("score").multiply(BigDecimal.valueOf(1000)).intValue(),
            region,
            resourceTemplate)
        .stream()
        .findFirst()
        .orElse(0);
  }

  /** 锁定租户配额行并验证独立 Encoder Stream/Bitrate Admission。 */
  @Transactional
  public void requireMediaQuota(String tenantId, int streams, int bitrateKbps) {
    if (streams == 0 && bitrateKbps == 0) {
      return;
    }
    var quota =
        jdbc
            .queryForList(
                "SELECT * FROM tenant_media_quotas WHERE tenant_id = ? FOR UPDATE", tenantId)
            .stream()
            .findFirst()
            .orElseThrow(() -> new MediaQuotaRejectedException("MEDIA_QUOTA_NOT_CONFIGURED"));
    var usage =
        jdbc.queryForMap(
            """
            SELECT COALESCE(sum(media_slots), 0) AS streams,
                   COALESCE(sum(media_bitrate_kbps), 0) AS bitrate
            FROM browser_placements
            WHERE tenant_id = ? AND state IN ('RESERVED', 'ACTIVE', 'EVICTING')
            """,
            tenantId);
    long totalStreams = ((Number) usage.get("streams")).longValue() + streams;
    long totalBitrate = ((Number) usage.get("bitrate")).longValue() + bitrateKbps;
    if (totalStreams > ((Number) quota.get("max_concurrent_streams")).longValue()
        || totalBitrate > ((Number) quota.get("max_bitrate_kbps")).longValue()) {
      throw new MediaQuotaRejectedException("MEDIA_QUOTA_EXCEEDED");
    }
  }

  @Transactional(readOnly = true)
  public void requireResidency(String tenantId, String region) {
    var mismatches =
        count(
            """
            SELECT count(*) FROM enterprise_retention_policies
            WHERE tenant_id = ? AND residency_region <> ?
            """,
            tenantId,
            region);
    if (mismatches > 0) {
      throw new GovernanceRejectedException("RESIDENCY_REGION_MISMATCH");
    }
  }

  @Transactional
  public MediaQuotaView upsertMediaQuota(
      String tenantId, UpsertMediaQuotaRequest request, String actorId) {
    var currentUsage = mediaUsage(tenantId);
    if (((Number) currentUsage.get("streams")).longValue() > request.maxConcurrentStreams()
        || ((Number) currentUsage.get("bitrate")).longValue() > request.maxBitrateKbps()) {
      throw new MediaQuotaRejectedException("MEDIA_QUOTA_BELOW_ACTIVE_USAGE");
    }
    jdbc.update(
        """
        INSERT INTO tenant_media_quotas(
          tenant_id, max_concurrent_streams, max_bitrate_kbps, updated_by, updated_at
        ) VALUES (?, ?, ?, ?, now())
        ON CONFLICT (tenant_id) DO UPDATE SET
          max_concurrent_streams = EXCLUDED.max_concurrent_streams,
          max_bitrate_kbps = EXCLUDED.max_bitrate_kbps,
          updated_by = EXCLUDED.updated_by,
          updated_at = EXCLUDED.updated_at
        """,
        tenantId,
        request.maxConcurrentStreams(),
        request.maxBitrateKbps(),
        actorId);
    audit(
        tenantId,
        actorId,
        "MEDIA_QUOTA",
        tenantId,
        "UPSERT",
        "SUCCEEDED",
        Map.of(
            "maxConcurrentStreams",
            request.maxConcurrentStreams(),
            "maxBitrateKbps",
            request.maxBitrateKbps()));
    return requireMediaQuotaView(tenantId);
  }

  @Transactional(readOnly = true)
  public MediaQuotaView mediaQuota(String tenantId) {
    return requireMediaQuotaView(tenantId);
  }

  private Optional<MediaQuotaView> optionalMediaQuota(String tenantId) {
    return jdbc
        .query(
            "SELECT * FROM tenant_media_quotas WHERE tenant_id = ?",
            (result, row) -> mediaQuota(result, tenantId),
            tenantId)
        .stream()
        .findFirst();
  }

  private MediaQuotaView requireMediaQuotaView(String tenantId) {
    return optionalMediaQuota(tenantId)
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Media Quota"));
  }

  private MediaQuotaView mediaQuota(ResultSet result, String tenantId) throws SQLException {
    var usage = mediaUsage(tenantId);
    return new MediaQuotaView(
        tenantId,
        result.getInt("max_concurrent_streams"),
        result.getInt("max_bitrate_kbps"),
        ((Number) usage.get("streams")).longValue(),
        ((Number) usage.get("bitrate")).longValue(),
        result.getString("updated_by"),
        result.getTimestamp("updated_at").toInstant());
  }

  private Map<String, Object> mediaUsage(String tenantId) {
    return jdbc.queryForMap(
        """
        SELECT COALESCE(sum(media_slots), 0) AS streams,
               COALESCE(sum(media_bitrate_kbps), 0) AS bitrate
        FROM browser_placements
        WHERE tenant_id = ? AND state IN ('RESERVED', 'ACTIVE', 'EVICTING')
        """,
        tenantId);
  }

  private RuntimeValidationView requireValidation(String id) {
    return jdbc
        .query(validationSelect() + " WHERE run.validation_id = ?", this::validation, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Runtime Validation"));
  }

  private RuntimeValidationView requireValidationForUpdate(String id) {
    return jdbc
        .query(
            validationSelect() + " WHERE run.validation_id = ? FOR UPDATE OF run",
            this::validation,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Runtime Validation"));
  }

  private static String validationSelect() {
    return """
        SELECT run.*,
               job.browser_engine AS job_browser_engine,
               job.browser_version AS job_browser_version,
               job.operating_system AS job_operating_system,
               job.architecture AS job_architecture,
               job.required_worker_capabilities AS job_required_worker_capabilities,
               job.state AS job_state,
               job.attempt AS job_attempt,
               job.maximum_attempts AS job_maximum_attempts,
               job.claim_owner AS job_claim_owner,
               job.claim_epoch AS job_claim_epoch,
               job.available_at AS job_available_at,
               job.lease_expires_at AS job_lease_expires_at,
               job.last_heartbeat_at AS job_last_heartbeat_at,
               job.failure_code AS job_failure_code,
               job.result_hash AS job_result_hash,
               job.updated_at AS job_updated_at
          FROM runtime_validation_runs run
          LEFT JOIN runtime_validation_jobs job ON job.validation_id = run.validation_id
        """;
  }

  private CostRateView requireCostRate(String id) {
    return jdbc
        .query("SELECT * FROM enterprise_cost_rates WHERE pricing_version = ?", this::costRate, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Cost Rate"));
  }

  private RegionView requireRegion(String id) {
    return jdbc
        .query("SELECT * FROM enterprise_regions WHERE region_id = ?", this::region, id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Region"));
  }

  private RecoveryGameDayView requireGameDay(String id) {
    return jdbc.query(GAMEDAY_VIEW_SELECT + " WHERE g.gameday_id = ?", this::gameDay, id).stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Recovery GameDay"));
  }

  private ComplianceSnapshotView requireCompliance(String id) {
    return jdbc
        .query(
            "SELECT * FROM enterprise_compliance_snapshots WHERE snapshot_id = ?",
            this::compliance,
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Compliance Snapshot"));
  }

  private Optional<ComplianceSnapshotView> latestCompliance(String tenantId) {
    return jdbc
        .query(
            """
            SELECT * FROM enterprise_compliance_snapshots
            WHERE tenant_id = ? ORDER BY generated_at DESC LIMIT 1
            """,
            this::compliance,
            tenantId)
        .stream()
        .findFirst();
  }

  private Optional<ErrorBudgetView> optionalErrorBudget(String tenantId) {
    if (count("SELECT count(*) FROM enterprise_slo_policies WHERE tenant_id = ?", tenantId) == 0) {
      return Optional.empty();
    }
    return Optional.of(errorBudget(tenantId));
  }

  private Map<String, Object> requireSlo(String tenantId) {
    return jdbc
        .queryForList("SELECT * FROM enterprise_slo_policies WHERE tenant_id = ?", tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("SLO Policy"));
  }

  private ReleaseFreezePolicy resolveReleaseFreezePolicy(
      String tenantId, UpsertSloPolicyRequest request) {
    var existing =
        jdbc
            .queryForList("SELECT * FROM enterprise_slo_policies WHERE tenant_id = ?", tenantId)
            .stream()
            .findFirst()
            .orElse(Map.of());
    var enabled =
        request.releaseFreezeEnabled() != null
            ? request.releaseFreezeEnabled()
            : existing.isEmpty() ? false : (Boolean) existing.get("release_freeze_enabled");
    var freezeThreshold =
        request.releaseFreezeBurnRateThreshold() != null
            ? request.releaseFreezeBurnRateThreshold()
            : existing.isEmpty()
                ? BigDecimal.ONE
                : (BigDecimal) existing.get("release_freeze_burn_rate_threshold");
    var recoveryThreshold =
        request.releaseRecoveryBurnRateThreshold() != null
            ? request.releaseRecoveryBurnRateThreshold()
            : existing.isEmpty()
                ? new BigDecimal("0.500000")
                : (BigDecimal) existing.get("release_recovery_burn_rate_threshold");
    var evaluationWindowMinutes =
        request.releaseFreezeWindowMinutes() != null
            ? request.releaseFreezeWindowMinutes()
            : existing.isEmpty()
                ? 60
                : ((Number) existing.get("release_freeze_window_minutes")).intValue();
    var recoveryStableMinutes =
        request.releaseRecoveryStableMinutes() != null
            ? request.releaseRecoveryStableMinutes()
            : existing.isEmpty()
                ? 30
                : ((Number) existing.get("release_recovery_stable_minutes")).intValue();
    if (recoveryThreshold.compareTo(freezeThreshold) >= 0) {
      throw new IllegalArgumentException(
          "release recovery burn-rate threshold must be lower than freeze threshold");
    }
    return new ReleaseFreezePolicy(
        enabled,
        freezeThreshold,
        recoveryThreshold,
        evaluationWindowMinutes,
        recoveryStableMinutes);
  }

  private RuntimeValidationView validation(ResultSet result, int row) throws SQLException {
    RuntimeValidationJobView job = null;
    var jobState = result.getString("job_state");
    if (jobState != null) {
      job =
          new RuntimeValidationJobView(
              result.getString("validation_id"),
              result.getString("job_browser_engine"),
              result.getString("job_browser_version"),
              result.getString("job_operating_system"),
              result.getString("job_architecture"),
              read(
                  result.getString("job_required_worker_capabilities"),
                  new TypeReference<Map<String, Boolean>>() {}),
              jobState,
              result.getInt("job_attempt"),
              result.getInt("job_maximum_attempts"),
              result.getString("job_claim_owner"),
              result.getLong("job_claim_epoch"),
              result.getTimestamp("job_available_at").toInstant(),
              instant(result, "job_lease_expires_at"),
              instant(result, "job_last_heartbeat_at"),
              result.getString("job_failure_code"),
              result.getString("job_result_hash"),
              result.getTimestamp("job_updated_at").toInstant());
    }
    return new RuntimeValidationView(
        result.getString("validation_id"),
        result.getString("build_id"),
        result.getString("suite_version"),
        result.getString("environment_digest"),
        result.getString("replay_dataset_id"),
        result.getString("persona"),
        result.getString("state"),
        result.getInt("required_tests"),
        result.getInt("required_failures"),
        result.getInt("optional_tests"),
        result.getInt("optional_failures"),
        read(
            result.getString("declared_capabilities"),
            new TypeReference<Map<String, Boolean>>() {}),
        read(
            result.getString("observed_capabilities"),
            new TypeReference<Map<String, Boolean>>() {}),
        read(result.getString("optional_failure_codes"), new TypeReference<List<String>>() {}),
        result.getString("evidence_hash"),
        result.getString("requested_by"),
        result.getTimestamp("started_at").toInstant(),
        instant(result, "completed_at"),
        job);
  }

  private CostRateView costRate(ResultSet result, int row) throws SQLException {
    return new CostRateView(
        result.getString("pricing_version"),
        result.getString("region"),
        result.getString("resource_template"),
        result.getBigDecimal("base_hourly_usd"),
        result.getBigDecimal("cpu_core_hourly_usd"),
        result.getBigDecimal("memory_gib_hourly_usd"),
        result.getBigDecimal("desktop_hourly_usd"),
        result.getBigDecimal("remote_desktop_egress_gib_usd"),
        result.getBigDecimal("gpu_hourly_usd"),
        result.getBigDecimal("media_hourly_usd"),
        result.getTimestamp("effective_at").toInstant(),
        result.getString("created_by"),
        result.getTimestamp("created_at").toInstant());
  }

  private RetentionPolicyView retention(ResultSet result, int row) throws SQLException {
    return new RetentionPolicyView(
        result.getString("tenant_id"),
        result.getString("data_class"),
        result.getInt("retention_days"),
        result.getBoolean("legal_hold"),
        result.getString("residency_region"),
        result.getString("updated_by"),
        result.getTimestamp("updated_at").toInstant());
  }

  private SlaExclusionView slaExclusion(ResultSet result, int row) throws SQLException {
    return new SlaExclusionView(
        result.getString("tenant_id"),
        result.getString("exclusion_code"),
        result.getString("description"),
        result.getBoolean("enabled"),
        result.getString("updated_by"),
        result.getTimestamp("updated_at").toInstant());
  }

  private DeletionReceiptView deletionReceipt(ResultSet result, int row) throws SQLException {
    return new DeletionReceiptView(
        result.getString("receipt_id"),
        result.getString("tenant_id"),
        result.getString("data_class"),
        result.getString("object_id"),
        result.getString("content_digest"),
        result.getTimestamp("policy_updated_at").toInstant(),
        result.getString("receipt_hash"),
        result.getString("deleted_by"),
        result.getTimestamp("deleted_at").toInstant());
  }

  private LicenseInventoryView license(ResultSet result, int row) throws SQLException {
    return new LicenseInventoryView(
        result.getString("component_id"),
        result.getString("component_type"),
        result.getString("component_name"),
        result.getString("component_version"),
        result.getString("license_id"),
        result.getString("source_url"),
        result.getBoolean("approved"),
        result.getString("evidence_hash"),
        result.getString("updated_by"),
        result.getTimestamp("updated_at").toInstant());
  }

  private AuditExportManifestView auditExport(ResultSet result, int row) throws SQLException {
    return new AuditExportManifestView(
        result.getString("export_id"),
        result.getString("tenant_id"),
        result.getLong("from_sequence"),
        result.getLong("to_sequence"),
        result.getLong("event_count"),
        result.getString("first_event_hash"),
        result.getString("last_event_hash"),
        result.getString("manifest_hash"),
        result.getString("signature_algorithm"),
        result.getString("signing_key_id"),
        result.getString("signature"),
        result.getString("generated_by"),
        result.getTimestamp("generated_at").toInstant());
  }

  private RegionView region(ResultSet result, int row) throws SQLException {
    return new RegionView(
        result.getString("region_id"),
        result.getString("role"),
        result.getString("admission_state"),
        result.getInt("replication_lag_seconds"),
        result.getTimestamp("last_verified_at").toInstant(),
        result.getString("updated_by"));
  }

  private RecoveryGameDayView gameDay(ResultSet result, int row) throws SQLException {
    RecoveryGameDayJobView job = null;
    if (result.getString("job_gameday_id") != null) {
      job =
          new RecoveryGameDayJobView(
              result.getString("job_gameday_id"),
              result.getString("job_scenario_code"),
              result.getString("job_environment"),
              read(
                  result.getString("job_required_worker_capabilities"),
                  new TypeReference<Map<String, Boolean>>() {}),
              result.getString("job_state"),
              result.getString("job_current_stage"),
              result.getInt("job_attempt"),
              result.getInt("job_maximum_attempts"),
              result.getInt("job_recovery_attempt"),
              result.getInt("job_maximum_recovery_attempts"),
              result.getString("job_claim_owner"),
              result.getLong("job_claim_epoch"),
              result.getTimestamp("job_available_at").toInstant(),
              instant(result, "job_lease_expires_at"),
              instant(result, "job_last_heartbeat_at"),
              result.getTimestamp("job_abort_deadline").toInstant(),
              result.getBoolean("job_abort_requested"),
              result.getBoolean("job_fault_injected"),
              nullableBoolean(result, "job_recovery_confirmed"),
              result.getString("job_failure_code"),
              result.getString("job_result_hash"),
              result.getTimestamp("job_updated_at").toInstant());
    }
    return new RecoveryGameDayView(
        result.getString("gameday_id"),
        result.getString("scenario"),
        result.getString("source_region"),
        result.getString("target_region"),
        result.getString("state"),
        result.getInt("rto_target_seconds"),
        result.getInt("rpo_target_seconds"),
        nullableInt(result, "observed_rto_seconds"),
        nullableInt(result, "observed_rpo_seconds"),
        nullableInt(result, "data_loss_records"),
        result.getString("evidence_hash"),
        result.getString("started_by"),
        result.getTimestamp("started_at").toInstant(),
        instant(result, "completed_at"),
        result.getString("execution_mode"),
        result.getString("environment"),
        read(
            result.getString("blast_radius"),
            new TypeReference<RecoveryGameDayBlastRadiusRequest>() {}),
        result.getInt("maximum_duration_seconds"),
        result.getString("approval_request_id"),
        result.getString("current_stage"),
        result.getBoolean("abort_requested"),
        nullableBoolean(result, "recovery_confirmed"),
        result.getString("failure_code"),
        job);
  }

  private ComplianceSnapshotView compliance(ResultSet result, int row) throws SQLException {
    return new ComplianceSnapshotView(
        result.getString("snapshot_id"),
        result.getString("tenant_id"),
        result.getString("framework"),
        result.getInt("control_count"),
        result.getInt("passing_controls"),
        result.getString("evidence_hash"),
        read(result.getString("evidence"), new TypeReference<Map<String, Boolean>>() {}),
        result.getString("generated_by"),
        result.getTimestamp("generated_at").toInstant());
  }

  private void requireExists(String sql, String id, String resource) {
    if (count(sql, id) == 0) {
      throw new EnterpriseResourceNotFoundException(resource);
    }
  }

  private DeletionReceiptView requireDeletionReceipt(String id, String tenantId) {
    return jdbc
        .query(
            """
            SELECT * FROM enterprise_retention_deletion_receipts
            WHERE receipt_id = ? AND tenant_id = ?
            """,
            this::deletionReceipt,
            id,
            tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Deletion Receipt"));
  }

  private LicenseInventoryView requireLicense(String componentId) {
    return jdbc
        .query(
            "SELECT * FROM enterprise_license_inventory WHERE component_id = ?",
            this::license,
            componentId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("License Inventory"));
  }

  private AuditExportManifestView requireAuditExport(String id, String tenantId) {
    return jdbc
        .query(
            """
            SELECT * FROM enterprise_audit_export_manifests
            WHERE export_id = ? AND tenant_id = ?
            """,
            this::auditExport,
            id,
            tenantId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new EnterpriseResourceNotFoundException("Audit Export"));
  }

  private void commitValidationJobIfPresent(
      String validationId, String actorId, String evidenceHash, Instant now) {
    var jobs =
        jdbc.queryForList(
            """
            SELECT state, claim_owner, claim_epoch, attempt
              FROM runtime_validation_jobs
             WHERE validation_id = ?
             FOR UPDATE
            """,
            validationId);
    if (jobs.isEmpty()) {
      return;
    }
    var job = jobs.getFirst();
    var fromState = String.valueOf(job.get("state"));
    if (fromState.equals("COMMITTED") || fromState.equals("FAILED")) {
      return;
    }
    var workerId =
        job.get("claim_owner") == null ? actorId : String.valueOf(job.get("claim_owner"));
    var claimEpoch = ((Number) job.get("claim_epoch")).longValue();
    var attempt = ((Number) job.get("attempt")).intValue();
    var changed =
        jdbc.update(
            """
            UPDATE runtime_validation_jobs
               SET state = 'COMMITTED', result_hash = ?, failure_code = NULL,
                   claim_token_hash = NULL, lease_expires_at = NULL,
                   updated_at = ?
             WHERE validation_id = ? AND state = ?
            """,
            evidenceHash,
            sqlTime(now),
            validationId,
            fromState);
    if (changed != 1) {
      throw new IllegalStateException("runtime validation commit was fenced");
    }
    appendValidationJobEvent(
        validationId,
        fromState.equals("ACKED") ? "RESULT_COMMITTED" : "MANUAL_OVERRIDE_COMMITTED",
        fromState,
        "COMMITTED",
        workerId,
        claimEpoch,
        attempt,
        fromState.equals("ACKED") ? null : "PLATFORM_ADMIN_OVERRIDE",
        now);
    jdbc.update(
        """
        UPDATE runtime_validation_workers
           SET state = 'ONLINE', active_validation_id = NULL, last_seen_at = ?
         WHERE worker_id = ? AND active_validation_id = ?
        """,
        sqlTime(now),
        workerId,
        validationId);
  }

  private void lockValidationJobIfPresent(String validationId) {
    jdbc.queryForList(
        "SELECT validation_id FROM runtime_validation_jobs WHERE validation_id = ? FOR UPDATE",
        validationId);
  }

  private void appendValidationJobEvent(
      String validationId,
      String eventType,
      String fromState,
      String toState,
      String workerId,
      long claimEpoch,
      int attempt,
      String reasonCode,
      Instant occurredAt) {
    jdbc.update(
        """
        INSERT INTO runtime_validation_job_events(
          event_id, validation_id, event_type, from_state, to_state,
          worker_id, claim_epoch, attempt, reason_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id("vev_"),
        validationId,
        eventType,
        fromState,
        toState,
        workerId,
        claimEpoch,
        attempt,
        reasonCode,
        sqlTime(occurredAt));
  }

  private void requireProductionGameDayApproval(
      String approvalRequestId, String targetRegion, String actorId) {
    if (!gameDayProductionEnabled) {
      throw new IllegalArgumentException(
          "automated production GameDays are disabled by platform policy");
    }
    if (approvalRequestId == null) {
      throw new IllegalArgumentException("production GameDay requires dual-control approval");
    }
    var approval =
        jdbc.queryForList(
            """
            SELECT requested_by, approved_by
              FROM break_glass_requests
             WHERE request_id = ?
               AND tenant_id = 'platform-control'
               AND resource_type = 'REGION'
               AND resource_id = ?
               AND requested_scope = 'RECOVERY_GAMEDAY'
               AND state = 'ACTIVE'
               AND expires_at > now()
             FOR UPDATE
            """,
            approvalRequestId,
            targetRegion);
    if (approval.size() != 1 || approval.getFirst().get("approved_by") == null) {
      throw new IllegalArgumentException(
          "production GameDay approval is missing, expired, or has the wrong scope");
    }
    if (actorId.equals(approval.getFirst().get("approved_by"))) {
      throw new IllegalArgumentException(
          "production GameDay approver cannot also start the execution");
    }
  }

  private void appendGameDayJobEvent(
      String gameDayId,
      String eventType,
      String fromState,
      String toState,
      String stage,
      String workerId,
      long claimEpoch,
      int attempt,
      String reasonCode,
      Instant occurredAt) {
    jdbc.update(
        """
        INSERT INTO recovery_gameday_job_events(
          event_id, gameday_id, event_type, from_state, to_state, stage,
          worker_id, claim_epoch, attempt, reason_code, occurred_at
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        id("gev_"),
        gameDayId,
        eventType,
        fromState,
        toState,
        stage,
        workerId,
        claimEpoch,
        attempt,
        reasonCode,
        sqlTime(occurredAt));
  }

  private long count(String sql, Object... arguments) {
    return Optional.ofNullable(jdbc.queryForObject(sql, Long.class, arguments)).orElse(0L);
  }

  private void audit(
      String tenantId,
      String actorId,
      String eventType,
      String resourceId,
      String action,
      String result,
      Map<String, Object> details) {
    auditService.append(
        new AuditRecord(
            tenantId,
            null,
            eventType,
            "USER",
            actorId,
            eventType,
            resourceId,
            action,
            result,
            details,
            resourceId));
  }

  private String json(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("enterprise data cannot be serialized", exception);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return objectMapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("enterprise JSON is invalid", exception);
    }
  }

  private String hash(Object value) {
    try {
      var bytes =
          objectMapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsBytes(value);
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
      throw new IllegalStateException("enterprise evidence cannot be hashed", exception);
    }
  }

  private String hmac(String value) {
    try {
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(
          new SecretKeySpec(auditExportSigningKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return java.util.HexFormat.of()
          .formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("audit export manifest cannot be signed", exception);
    }
  }

  private static String id(String prefix) {
    return prefix + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
  }

  private static String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Instant instant(ResultSet result, String column) throws SQLException {
    var value = result.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }

  private static java.sql.Timestamp sqlTime(Instant value) {
    return java.sql.Timestamp.from(value);
  }

  private static Integer nullableInt(ResultSet result, String column) throws SQLException {
    var value = result.getObject(column);
    return value == null ? null : ((Number) value).intValue();
  }

  private static Boolean nullableBoolean(ResultSet result, String column) throws SQLException {
    var value = result.getObject(column);
    return value == null ? null : (Boolean) value;
  }

  private record ReleaseFreezePolicy(
      boolean enabled,
      BigDecimal freezeThreshold,
      BigDecimal recoveryThreshold,
      int evaluationWindowMinutes,
      int recoveryStableMinutes) {}

  public static class EnterpriseResourceNotFoundException extends RuntimeException {
    public EnterpriseResourceNotFoundException(String resource) {
      super(resource + " not found");
    }
  }

  public static class MediaQuotaRejectedException extends RuntimeException {
    public MediaQuotaRejectedException(String reason) {
      super(reason);
    }
  }

  public static class GovernanceRejectedException extends RuntimeException {
    public GovernanceRejectedException(String reason) {
      super(reason);
    }
  }
}
