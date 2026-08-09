package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorOwnershipService;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import io.browsercloud.infrastructure.NodeCommandDispatchClaimService;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Routes API and background commands to the physical Control Plane worker owning a Session shard.
 *
 * <p>Local commands execute directly before a controller enters an application transaction. Remote
 * commands use the durable PostgreSQL inbox and wait for its committed result; no end-user token or
 * command payload crosses the Pod network.
 */
@Service
public class CoordinatorCommandRoutingService {

  private final CoordinatorRouteAuthority routes;
  private final CoordinatorOwnershipService ownership;
  private final NodeCommandDispatchClaimService workerMembership;
  private final CoordinatorCommandQueue queue;
  private final ObjectMapper mapper;
  private final Duration apiDeadline;
  private final Duration apiWaitTimeout;

  public CoordinatorCommandRoutingService(
      CoordinatorRouteAuthority routes,
      CoordinatorOwnershipService ownership,
      NodeCommandDispatchClaimService workerMembership,
      CoordinatorCommandQueue queue,
      ObjectMapper mapper,
      @Value("${coordinator.command-api-deadline-seconds:30}") long apiDeadlineSeconds,
      @Value("${coordinator.command-api-wait-seconds:20}") long apiWaitSeconds) {
    if (apiDeadlineSeconds < 5 || apiDeadlineSeconds > 300) {
      throw new IllegalArgumentException(
          "coordinator.command-api-deadline-seconds must be between 5 and 300");
    }
    if (apiWaitSeconds < 1 || apiWaitSeconds >= apiDeadlineSeconds) {
      throw new IllegalArgumentException(
          "coordinator.command-api-wait-seconds must be positive and below the deadline");
    }
    this.routes = routes;
    this.ownership = ownership;
    this.workerMembership = workerMembership;
    this.queue = queue;
    this.mapper = mapper;
    this.apiDeadline = Duration.ofSeconds(apiDeadlineSeconds);
    this.apiWaitTimeout = Duration.ofSeconds(apiWaitSeconds);
  }

  public <T> T execute(
      String sessionId,
      String tenantId,
      String commandType,
      String deduplicationKey,
      Object payload,
      Class<T> resultType,
      Supplier<T> localExecution) {
    var route = routes.resolve(sessionId);
    if (!route.tenantId().equals(tenantId)) {
      throw new TenantAccessDeniedException(sessionId);
    }
    var now = Instant.now();
    if (workerMembership.ownsShard(route.shardId(), now)
        && ownership.isCurrentOwnerOrUnowned(sessionId, route.routeEpoch())) {
      return localExecution.get();
    }
    var serializedPayload = write(payload);
    var command =
        queue.enqueue(
            route,
            commandType,
            normalizeDeduplication(commandType, deduplicationKey),
            serializedPayload,
            now.plus(apiDeadline));
    assertReplayMatches(command, route.sessionId(), commandType, serializedPayload);
    var waitDeadline = now.plus(apiWaitTimeout);
    while (Instant.now().isBefore(waitDeadline)) {
      var current = queue.require(command.commandId());
      if ("COMMITTED".equals(current.state())) {
        return read(current.result(), resultType);
      }
      if ("FAILED".equals(current.state())) {
        throw new RoutedCoordinatorCommandException(current.failureCode(), current.commandId());
      }
      try {
        Thread.sleep(25);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new RoutedCoordinatorCommandException(
            "COORDINATOR_COMMAND_WAIT_INTERRUPTED", current.commandId());
      }
    }
    throw new RoutedCoordinatorCommandException(
        "COORDINATOR_COMMAND_RESULT_TIMEOUT", command.commandId());
  }

  public String enqueueAsync(
      String sessionId,
      String commandType,
      String deduplicationKey,
      Object payload,
      Duration deadline) {
    var route = routes.resolve(sessionId);
    var serializedPayload = write(payload);
    var command =
        queue.enqueue(
            route,
            commandType,
            normalizeDeduplication(commandType, deduplicationKey),
            serializedPayload,
            Instant.now().plus(deadline));
    assertReplayMatches(command, route.sessionId(), commandType, serializedPayload);
    return command.commandId();
  }

  public static String randomDeduplicationKey(String commandType) {
    return commandType + ":" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
  }

  private void assertReplayMatches(
      CoordinatorCommandQueue.CommandRecord command,
      String sessionId,
      String commandType,
      String payload) {
    try {
      if (!command.sessionId().equals(sessionId)
          || !command.commandType().equals(commandType)
          || !mapper.readTree(command.payload()).equals(mapper.readTree(payload))) {
        throw new RoutedCoordinatorCommandException(
            "COORDINATOR_COMMAND_IDEMPOTENCY_CONFLICT", command.commandId());
      }
    } catch (JsonProcessingException exception) {
      throw new RoutedCoordinatorCommandException(
          "COORDINATOR_COMMAND_PAYLOAD_INVALID", command.commandId());
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new RoutedCoordinatorCommandException("COORDINATOR_COMMAND_PAYLOAD_INVALID", null);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JsonProcessingException exception) {
      throw new RoutedCoordinatorCommandException("COORDINATOR_COMMAND_RESULT_INVALID", null);
    }
  }

  private static String normalizeDeduplication(String commandType, String value) {
    var key =
        value == null || value.isBlank()
            ? randomDeduplicationKey(commandType)
            : commandType + ":" + value;
    if (key.length() > 240) {
      throw new RoutedCoordinatorCommandException(
          "COORDINATOR_COMMAND_IDEMPOTENCY_KEY_TOO_LONG", null);
    }
    return key;
  }

  public static final class RoutedCoordinatorCommandException extends RuntimeException {
    private final String commandId;

    public RoutedCoordinatorCommandException(String reason, String commandId) {
      super(reason == null || reason.isBlank() ? "COORDINATOR_COMMAND_FAILED" : reason);
      this.commandId = commandId;
    }

    public String commandId() {
      return commandId;
    }
  }
}
