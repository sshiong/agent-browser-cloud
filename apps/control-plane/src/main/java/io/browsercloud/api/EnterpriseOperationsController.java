package io.browsercloud.api;

import static io.browsercloud.api.EnterpriseOperationsModels.*;

import io.browsercloud.application.EnterpriseOperationsApplicationService;
import io.browsercloud.security.PlatformIdentity;
import io.browsercloud.security.PlatformRoles;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/enterprise")
@Validated
public class EnterpriseOperationsController {

  private final EnterpriseOperationsApplicationService service;
  private final PlatformIdentity identity;

  public EnterpriseOperationsController(
      EnterpriseOperationsApplicationService service, PlatformIdentity identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/overview")
  @PreAuthorize(PlatformRoles.ADMIN)
  public EnterpriseOverviewResponse overview() {
    return service.overview(identity.current().tenantId());
  }

  @GetMapping("/runtime-validations")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<RuntimeValidationView> validations() {
    return service.listValidations();
  }

  @PostMapping("/runtime-validations")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RuntimeValidationView startValidation(
      @Valid @RequestBody StartRuntimeValidationRequest request) {
    return service.startValidation(request, identity.current().actorId());
  }

  @PostMapping("/runtime-validations/{validationId}:complete")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RuntimeValidationView completeValidation(
      @PathVariable @Pattern(regexp = "^val_[a-zA-Z0-9]{20}$") String validationId,
      @Valid @RequestBody CompleteRuntimeValidationRequest request) {
    return service.completeValidation(validationId, request, identity.current().actorId());
  }

  @GetMapping("/cost-rates")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<CostRateView> costRates() {
    return service.listCostRates();
  }

  @PostMapping("/cost-rates")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public CostRateView createCostRate(@Valid @RequestBody CreateCostRateRequest request) {
    return service.createCostRate(request, identity.current().actorId());
  }

  @GetMapping("/sessions/{sessionId}/cost-explanation")
  @PreAuthorize(PlatformRoles.READ)
  public SessionCostExplanationView costExplanation(
      @PathVariable @Pattern(regexp = "^ses_[a-zA-Z0-9]{16,}$") String sessionId) {
    return service.explainSessionCost(sessionId, identity.current().tenantId());
  }

  @GetMapping("/media-quota")
  @PreAuthorize(PlatformRoles.ADMIN)
  public MediaQuotaView mediaQuota() {
    return service.mediaQuota(identity.current().tenantId());
  }

  @PutMapping("/media-quota")
  @PreAuthorize(PlatformRoles.ADMIN)
  public MediaQuotaView upsertMediaQuota(@Valid @RequestBody UpsertMediaQuotaRequest request) {
    var principal = identity.current();
    return service.upsertMediaQuota(principal.tenantId(), request, principal.actorId());
  }

  @PutMapping("/slo-policy")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ErrorBudgetView upsertSlo(@Valid @RequestBody UpsertSloPolicyRequest request) {
    var principal = identity.current();
    return service.upsertSlo(principal.tenantId(), request, principal.actorId());
  }

  @GetMapping("/error-budget")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ErrorBudgetView errorBudget() {
    return service.errorBudget(identity.current().tenantId());
  }

  @GetMapping("/release-freeze")
  @PreAuthorize(PlatformRoles.ADMIN)
  public ReleaseFreezeView releaseFreeze() {
    return service.releaseFreeze(identity.current().tenantId());
  }

  @PostMapping("/service-level-events")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public ErrorBudgetView recordServiceLevelEvent(
      @Valid @RequestBody RecordServiceLevelEventRequest request) {
    return service.recordServiceLevelEvent(identity.current().tenantId(), request);
  }

  @GetMapping("/sla-exclusions")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<SlaExclusionView> slaExclusions() {
    return service.listSlaExclusions(identity.current().tenantId());
  }

  @PutMapping("/sla-exclusions/{exclusionCode}")
  @PreAuthorize(PlatformRoles.ADMIN)
  public SlaExclusionView upsertSlaExclusion(
      @PathVariable @Pattern(regexp = "^[A-Z0-9_]{1,64}$") String exclusionCode,
      @Valid @RequestBody UpsertSlaExclusionRequest request) {
    var principal = identity.current();
    return service.upsertSlaExclusion(
        principal.tenantId(), exclusionCode, request, principal.actorId());
  }

  @GetMapping("/retention-policies")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<RetentionPolicyView> retentionPolicies() {
    return service.listRetention(identity.current().tenantId());
  }

  @PutMapping("/retention-policies")
  @PreAuthorize(PlatformRoles.ADMIN)
  public RetentionPolicyView upsertRetention(
      @Valid @RequestBody UpsertRetentionPolicyRequest request) {
    var principal = identity.current();
    return service.upsertRetention(principal.tenantId(), request, principal.actorId());
  }

  @PostMapping("/retention-deletion-receipts")
  @PreAuthorize(PlatformRoles.SECURITY_ADMIN)
  public DeletionReceiptView createDeletionReceipt(
      @Valid @RequestBody CreateDeletionReceiptRequest request) {
    var principal = identity.current();
    return service.createDeletionReceipt(principal.tenantId(), request, principal.actorId());
  }

  @GetMapping("/license-inventory")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<LicenseInventoryView> licenseInventory() {
    return service.listLicenses();
  }

  @PutMapping("/license-inventory/{componentId}")
  @PreAuthorize(PlatformRoles.SECURITY_ADMIN)
  public LicenseInventoryView upsertLicense(
      @PathVariable @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String componentId,
      @Valid @RequestBody UpsertLicenseInventoryRequest request) {
    return service.upsertLicense(componentId, request, identity.current().actorId());
  }

  @PostMapping("/audit-exports")
  @PreAuthorize(PlatformRoles.SECURITY_ADMIN)
  public AuditExportManifestView generateAuditExport(
      @RequestParam(required = false) Long fromSequence,
      @RequestParam(required = false) Long toSequence) {
    var principal = identity.current();
    return service.generateAuditExport(
        principal.tenantId(), fromSequence, toSequence, principal.actorId());
  }

  @GetMapping("/regions")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<RegionView> regions() {
    return service.listRegions();
  }

  @PutMapping("/regions/{regionId}")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RegionView upsertRegion(
      @PathVariable @Pattern(regexp = "^[a-z0-9-]{1,32}$") String regionId,
      @Valid @RequestBody UpsertRegionRequest request) {
    return service.upsertRegion(regionId, request, identity.current().actorId());
  }

  @GetMapping("/recovery-gamedays")
  @PreAuthorize(PlatformRoles.ADMIN)
  public List<RecoveryGameDayView> gameDays() {
    return service.listGameDays();
  }

  @PostMapping("/recovery-gamedays")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RecoveryGameDayView startGameDay(@Valid @RequestBody StartRecoveryGameDayRequest request) {
    return service.startGameDay(request, identity.current().actorId());
  }

  @PostMapping("/recovery-gamedays/{gameDayId}:complete")
  @PreAuthorize(PlatformRoles.PLATFORM_ADMIN)
  public RecoveryGameDayView completeGameDay(
      @PathVariable @Pattern(regexp = "^gameday_[a-zA-Z0-9]{20}$") String gameDayId,
      @Valid @RequestBody CompleteRecoveryGameDayRequest request) {
    return service.completeGameDay(gameDayId, request, identity.current().actorId());
  }

  @PostMapping("/compliance-snapshots")
  @PreAuthorize(PlatformRoles.SECURITY_ADMIN)
  public ComplianceSnapshotView generateCompliance(
      @RequestParam(defaultValue = "SOC2") @Pattern(regexp = "^[A-Za-z0-9_.-]{1,64}$")
          String framework) {
    var principal = identity.current();
    return service.generateCompliance(principal.tenantId(), framework, principal.actorId());
  }
}
