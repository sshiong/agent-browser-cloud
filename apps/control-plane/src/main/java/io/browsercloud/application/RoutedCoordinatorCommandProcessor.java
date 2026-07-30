package io.browsercloud.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.browsercloud.coordinator.CoordinatorRouteAuthority;
import io.browsercloud.infrastructure.CoordinatorCommandQueue;
import io.browsercloud.infrastructure.NodeCommandDispatchClaimService;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Executes and commits one claimed routed command in the same database transaction. */
@Service
public class RoutedCoordinatorCommandProcessor {

  private final CoordinatorCommandQueue queue;
  private final CoordinatorRouteAuthority routes;
  private final NodeCommandDispatchClaimService membership;
  private final RoutedCoordinatorCommandExecutor executor;
  private final ObjectMapper mapper;

  public RoutedCoordinatorCommandProcessor(
      CoordinatorCommandQueue queue,
      CoordinatorRouteAuthority routes,
      NodeCommandDispatchClaimService membership,
      RoutedCoordinatorCommandExecutor executor,
      ObjectMapper mapper) {
    this.queue = queue;
    this.routes = routes;
    this.membership = membership;
    this.executor = executor;
    this.mapper = mapper;
  }

  @Transactional
  public void process(String commandId) {
    var command = queue.requireClaimedForUpdate(commandId);
    var route = routes.resolve(command.sessionId());
    if (route.routeEpoch() != command.routeEpoch()
        || route.shardId() != command.coordinatorShardId()) {
      throw new RoutedCommandFenceException("COORDINATOR_COMMAND_ROUTE_MOVED");
    }
    if (!membership.ownsShard(route.shardId(), Instant.now())) {
      throw new RoutedCommandFenceException("COORDINATOR_COMMAND_WORKER_REBALANCED");
    }
    var result = executor.execute(command.commandType(), command.sessionId(), command.payload());
    queue.commit(command.commandId(), write(result), Instant.now());
  }

  private String write(Object result) {
    try {
      return mapper.writeValueAsString(result);
    } catch (JsonProcessingException exception) {
      throw new RoutedCommandFenceException("COORDINATOR_COMMAND_RESULT_INVALID");
    }
  }

  public static final class RoutedCommandFenceException extends RuntimeException {
    public RoutedCommandFenceException(String reason) {
      super(reason);
    }
  }
}
