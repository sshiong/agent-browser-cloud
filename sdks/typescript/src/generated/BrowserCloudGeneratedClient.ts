/* generated using openapi-typescript-codegen -- do not edit */
/* istanbul ignore file */
/* tslint:disable */
/* eslint-disable */
import type { BaseHttpRequest } from './core/BaseHttpRequest.js';
import type { OpenAPIConfig } from './core/OpenAPI.js';
import { FetchHttpRequest } from './core/FetchHttpRequest.js';
import { AgentService } from './services/AgentService.js';
import { AgentSafetyService } from './services/AgentSafetyService.js';
import { AgentToolService } from './services/AgentToolService.js';
import { AuditService } from './services/AuditService.js';
import { BusinessRecoveryService } from './services/BusinessRecoveryService.js';
import { CapacityService } from './services/CapacityService.js';
import { ChallengeService } from './services/ChallengeService.js';
import { ComplianceService } from './services/ComplianceService.js';
import { CoordinatorService } from './services/CoordinatorService.js';
import { CostService } from './services/CostService.js';
import { DrService } from './services/DrService.js';
import { EnterpriseService } from './services/EnterpriseService.js';
import { EnvironmentImportService } from './services/EnvironmentImportService.js';
import { EvidenceService } from './services/EvidenceService.js';
import { GroupsService } from './services/GroupsService.js';
import { HumanAssistService } from './services/HumanAssistService.js';
import { HumanTakeoverService } from './services/HumanTakeoverService.js';
import { NotificationService } from './services/NotificationService.js';
import { OperationService } from './services/OperationService.js';
import { ProfileService } from './services/ProfileService.js';
import { ProxyService } from './services/ProxyService.js';
import { RecordingService } from './services/RecordingService.js';
import { RemoteDesktopService } from './services/RemoteDesktopService.js';
import { ResourceService } from './services/ResourceService.js';
import { RuntimeService } from './services/RuntimeService.js';
import { SavedViewService } from './services/SavedViewService.js';
import { SearchService } from './services/SearchService.js';
import { SecurityService } from './services/SecurityService.js';
import { SessionService } from './services/SessionService.js';
import { SettingsService } from './services/SettingsService.js';
import { SloService } from './services/SloService.js';
import { StateService } from './services/StateService.js';
import { TagsService } from './services/TagsService.js';
import { UserPreferenceService } from './services/UserPreferenceService.js';
import { WorkspaceOverviewService } from './services/WorkspaceOverviewService.js';
type HttpRequestConstructor = new (config: OpenAPIConfig) => BaseHttpRequest;
export class BrowserCloudGeneratedClient {
    public readonly agent: AgentService;
    public readonly agentSafety: AgentSafetyService;
    public readonly agentTool: AgentToolService;
    public readonly audit: AuditService;
    public readonly businessRecovery: BusinessRecoveryService;
    public readonly capacity: CapacityService;
    public readonly challenge: ChallengeService;
    public readonly compliance: ComplianceService;
    public readonly coordinator: CoordinatorService;
    public readonly cost: CostService;
    public readonly dr: DrService;
    public readonly enterprise: EnterpriseService;
    public readonly environmentImport: EnvironmentImportService;
    public readonly evidence: EvidenceService;
    public readonly groups: GroupsService;
    public readonly humanAssist: HumanAssistService;
    public readonly humanTakeover: HumanTakeoverService;
    public readonly notification: NotificationService;
    public readonly operation: OperationService;
    public readonly profile: ProfileService;
    public readonly proxy: ProxyService;
    public readonly recording: RecordingService;
    public readonly remoteDesktop: RemoteDesktopService;
    public readonly resource: ResourceService;
    public readonly runtime: RuntimeService;
    public readonly savedView: SavedViewService;
    public readonly search: SearchService;
    public readonly security: SecurityService;
    public readonly session: SessionService;
    public readonly settings: SettingsService;
    public readonly slo: SloService;
    public readonly state: StateService;
    public readonly tags: TagsService;
    public readonly userPreference: UserPreferenceService;
    public readonly workspaceOverview: WorkspaceOverviewService;
    public readonly request: BaseHttpRequest;
    constructor(config?: Partial<OpenAPIConfig>, HttpRequest: HttpRequestConstructor = FetchHttpRequest) {
        this.request = new HttpRequest({
            BASE: config?.BASE ?? '',
            VERSION: config?.VERSION ?? '1.0.0',
            WITH_CREDENTIALS: config?.WITH_CREDENTIALS ?? false,
            CREDENTIALS: config?.CREDENTIALS ?? 'include',
            TOKEN: config?.TOKEN,
            USERNAME: config?.USERNAME,
            PASSWORD: config?.PASSWORD,
            HEADERS: config?.HEADERS,
            ENCODE_PATH: config?.ENCODE_PATH,
            FETCH: config?.FETCH,
        });
        this.agent = new AgentService(this.request);
        this.agentSafety = new AgentSafetyService(this.request);
        this.agentTool = new AgentToolService(this.request);
        this.audit = new AuditService(this.request);
        this.businessRecovery = new BusinessRecoveryService(this.request);
        this.capacity = new CapacityService(this.request);
        this.challenge = new ChallengeService(this.request);
        this.compliance = new ComplianceService(this.request);
        this.coordinator = new CoordinatorService(this.request);
        this.cost = new CostService(this.request);
        this.dr = new DrService(this.request);
        this.enterprise = new EnterpriseService(this.request);
        this.environmentImport = new EnvironmentImportService(this.request);
        this.evidence = new EvidenceService(this.request);
        this.groups = new GroupsService(this.request);
        this.humanAssist = new HumanAssistService(this.request);
        this.humanTakeover = new HumanTakeoverService(this.request);
        this.notification = new NotificationService(this.request);
        this.operation = new OperationService(this.request);
        this.profile = new ProfileService(this.request);
        this.proxy = new ProxyService(this.request);
        this.recording = new RecordingService(this.request);
        this.remoteDesktop = new RemoteDesktopService(this.request);
        this.resource = new ResourceService(this.request);
        this.runtime = new RuntimeService(this.request);
        this.savedView = new SavedViewService(this.request);
        this.search = new SearchService(this.request);
        this.security = new SecurityService(this.request);
        this.session = new SessionService(this.request);
        this.settings = new SettingsService(this.request);
        this.slo = new SloService(this.request);
        this.state = new StateService(this.request);
        this.tags = new TagsService(this.request);
        this.userPreference = new UserPreferenceService(this.request);
        this.workspaceOverview = new WorkspaceOverviewService(this.request);
    }
}
