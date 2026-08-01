package io.browsercloud.application;

import static io.browsercloud.application.CoordinatorCommandPayloads.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

/**
 * Dispatches a claimed, physically routed command to the existing transactional application API.
 */
@Service
public class RoutedCoordinatorCommandExecutor {

  private final SessionApplicationService sessions;
  private final AgentExecutionService agentExecution;
  private final AgentHumanGovernanceService governance;
  private final CoordinatorDeadlineCommandExecutor deadlines;
  private final SessionResourceDecisionExecutor resourceDecisions;
  private final SessionResourceApplicationService resources;
  private final SessionMigrationApplicationService migrations;
  private final ObjectMapper mapper;

  public RoutedCoordinatorCommandExecutor(
      SessionApplicationService sessions,
      AgentExecutionService agentExecution,
      AgentHumanGovernanceService governance,
      CoordinatorDeadlineCommandExecutor deadlines,
      SessionResourceDecisionExecutor resourceDecisions,
      SessionResourceApplicationService resources,
      SessionMigrationApplicationService migrations,
      ObjectMapper mapper) {
    this.sessions = sessions;
    this.agentExecution = agentExecution;
    this.governance = governance;
    this.deadlines = deadlines;
    this.resourceDecisions = resourceDecisions;
    this.resources = resources;
    this.migrations = migrations;
    this.mapper = mapper;
  }

  public Object execute(String commandType, String sessionId, String payload) {
    return switch (commandType) {
      case SESSION_START -> {
        var command = read(payload, SessionActor.class);
        yield sessions.start(sessionId, command.tenantId(), command.actorId());
      }
      case SESSION_TERMINATE -> {
        var command = read(payload, SessionActor.class);
        yield sessions.terminate(sessionId, command.tenantId(), command.actorId());
      }
      case SESSION_TAKEOVER -> {
        var command = read(payload, SessionActor.class);
        yield sessions.requestTakeover(sessionId, command.tenantId(), command.actorId());
      }
      case SESSION_RELEASE_TAKEOVER -> {
        var command = read(payload, SessionActor.class);
        yield sessions.releaseTakeover(sessionId, command.tenantId(), command.actorId());
      }
      case AGENT_EXECUTE -> {
        var command = read(payload, AgentExecute.class);
        yield agentExecution.execute(
            command.taskId(), command.tenantId(), command.idempotencyKey());
      }
      case AGENT_ACCEPT_HANDOFF -> {
        var command = read(payload, AgentHandoff.class);
        yield governance.acceptHandoff(command.taskId(), command.tenantId(), command.actorId());
      }
      case OPERATION_TIMEOUT -> {
        deadlines.operationTimeout(sessionId, read(payload, OperationTimeout.class).operationId());
        yield CommandAck.committed();
      }
      case WORKFLOW_TIMEOUT -> {
        deadlines.workflowTimeout(sessionId, read(payload, WorkflowTimeout.class).workflowId());
        yield CommandAck.committed();
      }
      case AGENT_RECOVER -> {
        var command = read(payload, AgentRecover.class);
        agentExecution.recover(command.taskId(), command.observedAt());
        yield CommandAck.committed();
      }
      case RESOURCE_POLICY_EVALUATE -> {
        resourceDecisions.evaluate(sessionId);
        yield CommandAck.committed();
      }
      case MIGRATION_RECONCILE -> {
        var command = read(payload, MigrationReconcile.class);
        migrations.reconcileRouted(command.migrationId(), command.observedAt());
        yield CommandAck.committed();
      }
      case PROXY_REBIND_REQUEST -> {
        var command = read(payload, ProxyRebindRequestCommand.class);
        yield migrations.requestProxyRebind(
            sessionId,
            command.tenantId(),
            command.actorId(),
            command.targetBindingProfileId(),
            command.reason(),
            command.idempotencyKey(),
            command.requestId());
      }
      case WORKSPACE_BATCH_PAUSE_AGENT -> {
        var command = read(payload, WorkspaceBatchSessionAction.class);
        yield resources.pauseAgentForBatch(
            sessionId, command.tenantId(), command.batchOperationId());
      }
      case WORKSPACE_BATCH_MIGRATE -> {
        var command = read(payload, WorkspaceBatchSessionAction.class);
        yield new BatchMigrationAccepted(
            migrations.request(
                sessionId, command.tenantId(), command.actorId(), command.batchOperationId()));
      }
      case WORKSPACE_BATCH_HIBERNATE -> {
        var command = read(payload, WorkspaceBatchSessionAction.class);
        yield migrations.hibernateAtSafePoint(
            sessionId, command.tenantId(), command.actorId(), command.batchOperationId());
      }
      default ->
          throw new RoutedCommandExecutionException("UNSUPPORTED_ROUTED_COORDINATOR_COMMAND");
    };
  }

  private <T> T read(String payload, Class<T> type) {
    try {
      return mapper.readValue(payload, type);
    } catch (JsonProcessingException exception) {
      throw new RoutedCommandExecutionException("COORDINATOR_COMMAND_PAYLOAD_INVALID");
    }
  }

  public static final class RoutedCommandExecutionException extends RuntimeException {
    public RoutedCommandExecutionException(String reason) {
      super(reason);
    }
  }
}
