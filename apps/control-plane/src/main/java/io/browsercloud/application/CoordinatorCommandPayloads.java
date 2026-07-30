package io.browsercloud.application;

import java.time.Instant;

/** Version-one payloads stored in the routed Coordinator command inbox. */
public final class CoordinatorCommandPayloads {
  private CoordinatorCommandPayloads() {}

  public static final String SESSION_START = "SESSION_START_V1";
  public static final String SESSION_TERMINATE = "SESSION_TERMINATE_V1";
  public static final String SESSION_TAKEOVER = "SESSION_TAKEOVER_V1";
  public static final String SESSION_RELEASE_TAKEOVER = "SESSION_RELEASE_TAKEOVER_V1";
  public static final String AGENT_EXECUTE = "AGENT_EXECUTE_V1";
  public static final String AGENT_ACCEPT_HANDOFF = "AGENT_ACCEPT_HANDOFF_V1";
  public static final String OPERATION_TIMEOUT = "OPERATION_TIMEOUT_V1";
  public static final String WORKFLOW_TIMEOUT = "WORKFLOW_TIMEOUT_V1";
  public static final String AGENT_RECOVER = "AGENT_RECOVER_V1";
  public static final String RESOURCE_POLICY_EVALUATE = "RESOURCE_POLICY_EVALUATE_V1";
  public static final String MIGRATION_RECONCILE = "MIGRATION_RECONCILE_V1";

  public record SessionActor(String tenantId, String actorId) {}

  public record AgentExecute(String tenantId, String taskId, String idempotencyKey) {}

  public record AgentHandoff(String tenantId, String taskId, String actorId) {}

  public record OperationTimeout(String operationId) {}

  public record WorkflowTimeout(String workflowId) {}

  public record AgentRecover(String taskId, Instant observedAt) {}

  public record ResourceEvaluation(String tenantId) {}

  public record MigrationReconcile(String migrationId, Instant observedAt) {}

  public record CommandAck(String state) {
    public static CommandAck committed() {
      return new CommandAck("COMMITTED");
    }
  }
}
