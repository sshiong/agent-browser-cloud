package io.browsercloud.api;

import io.browsercloud.application.AgentApplicationService.AgentTaskNotFoundException;
import io.browsercloud.application.AgentApplicationService.InvalidAgentTaskException;
import io.browsercloud.application.AgentExecutionService.AgentExecutionRejectedException;
import io.browsercloud.application.AgentHumanGovernanceService.HumanGovernanceException;
import io.browsercloud.application.BreakGlassApplicationService.BreakGlassNotFoundException;
import io.browsercloud.application.BreakGlassApplicationService.BreakGlassRejectedException;
import io.browsercloud.application.KeyRotationApplicationService.KeyRotationNotFoundException;
import io.browsercloud.application.KeyRotationApplicationService.KeyRotationRejectedException;
import io.browsercloud.application.ProfileApplicationService.ProfileAlreadyExistsException;
import io.browsercloud.application.ProfileApplicationService.ProfileNotFoundException;
import io.browsercloud.application.RuntimeBuildPolicy.RuntimeBuildRejectedException;
import io.browsercloud.application.RuntimeReleaseApplicationService.RuntimeReleaseNotFoundException;
import io.browsercloud.application.RuntimeReleaseApplicationService.RuntimeReleaseRejectedException;
import io.browsercloud.application.SecureDebugApplicationService.SecureDebugNotFoundException;
import io.browsercloud.application.SecureDebugApplicationService.SecureDebugRejectedException;
import io.browsercloud.application.SessionApplicationService.CapacityUnavailableException;
import io.browsercloud.application.StateGatewayApplicationService.InvalidStateResyncRequestException;
import io.browsercloud.application.StaticProxyApplicationService.ProxyUnavailableException;
import io.browsercloud.coordinator.exceptions.ActiveOperationExistsException;
import io.browsercloud.coordinator.exceptions.IdempotencyConflictException;
import io.browsercloud.coordinator.exceptions.InvalidSessionStateException;
import io.browsercloud.coordinator.exceptions.SessionNotFoundException;
import io.browsercloud.coordinator.exceptions.StaleContextEpochException;
import io.browsercloud.coordinator.exceptions.StaleOperationException;
import io.browsercloud.coordinator.exceptions.TenantAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 将领域异常转换为正式契约中的 Error Envelope，避免向客户端泄露堆栈。 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(SessionNotFoundException.class)
  ResponseEntity<ApiError> notFound(
      SessionNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "SESSION_NOT_FOUND", "Session not found", Map.of(), request);
  }

  @ExceptionHandler(ProfileNotFoundException.class)
  ResponseEntity<ApiError> profileNotFound(
      ProfileNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "PROFILE_NOT_FOUND", "Profile not found", Map.of(), request);
  }

  @ExceptionHandler(AgentTaskNotFoundException.class)
  ResponseEntity<ApiError> agentTaskNotFound(
      AgentTaskNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND, "AGENT_TASK_NOT_FOUND", "Agent task not found", Map.of(), request);
  }

  @ExceptionHandler(BreakGlassNotFoundException.class)
  ResponseEntity<ApiError> breakGlassNotFound(
      BreakGlassNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "BREAK_GLASS_NOT_FOUND",
        "Break-glass request not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(BreakGlassRejectedException.class)
  ResponseEntity<ApiError> breakGlassRejected(
      BreakGlassRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "BREAK_GLASS_REJECTED",
        "Break-glass transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(SecureDebugNotFoundException.class)
  ResponseEntity<ApiError> secureDebugNotFound(
      SecureDebugNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "SECURE_DEBUG_NOT_FOUND",
        "Secure Debug session was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(SecureDebugRejectedException.class)
  ResponseEntity<ApiError> secureDebugRejected(
      SecureDebugRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SECURE_DEBUG_REJECTED",
        "Secure Debug access was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(KeyRotationNotFoundException.class)
  ResponseEntity<ApiError> keyRotationNotFound(
      KeyRotationNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "KEY_ROTATION_NOT_FOUND",
        "Key rotation request was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(KeyRotationRejectedException.class)
  ResponseEntity<ApiError> keyRotationRejected(
      KeyRotationRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "KEY_ROTATION_REJECTED",
        "Key rotation transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(InvalidAgentTaskException.class)
  ResponseEntity<ApiError> invalidAgentTask(
      InvalidAgentTaskException exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST,
        "AGENT_TASK_INVALID",
        "Agent task validation failed",
        Map.of(),
        request);
  }

  @ExceptionHandler(AgentExecutionRejectedException.class)
  ResponseEntity<ApiError> agentExecutionRejected(
      AgentExecutionRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "AGENT_EXECUTION_REJECTED",
        "Agent task cannot be executed in its current state",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(HumanGovernanceException.class)
  ResponseEntity<ApiError> humanGovernance(
      HumanGovernanceException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "HUMAN_GOVERNANCE_REJECTED",
        "Human confirmation or takeover handoff cannot be completed",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(ProfileAlreadyExistsException.class)
  ResponseEntity<ApiError> profileConflict(
      ProfileAlreadyExistsException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT, "PROFILE_ALREADY_EXISTS", "Profile already exists", Map.of(), request);
  }

  @ExceptionHandler(ProxyUnavailableException.class)
  ResponseEntity<ApiError> proxyUnavailable(
      ProxyUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "PROXY_UNAVAILABLE",
        "A verified network exit is unavailable",
        Map.of(),
        request);
  }

  @ExceptionHandler(RuntimeBuildRejectedException.class)
  ResponseEntity<ApiError> runtimeBuildRejected(
      RuntimeBuildRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "RUNTIME_BUILD_REJECTED",
        "The configured Runtime Build is not approved for release",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(RuntimeReleaseNotFoundException.class)
  ResponseEntity<ApiError> runtimeReleaseNotFound(
      RuntimeReleaseNotFoundException exception, HttpServletRequest request) {
    return response(
        HttpStatus.NOT_FOUND,
        "RUNTIME_RELEASE_NOT_FOUND",
        "Runtime release request was not found",
        Map.of(),
        request);
  }

  @ExceptionHandler(RuntimeReleaseRejectedException.class)
  ResponseEntity<ApiError> runtimeReleaseRejected(
      RuntimeReleaseRejectedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "RUNTIME_RELEASE_REJECTED",
        "Runtime release transition was rejected",
        Map.of("reason", exception.getMessage()),
        request);
  }

  @ExceptionHandler(CapacityUnavailableException.class)
  ResponseEntity<ApiError> capacityUnavailable(
      CapacityUnavailableException exception, HttpServletRequest request) {
    return response(
        HttpStatus.SERVICE_UNAVAILABLE,
        "CAPACITY_UNAVAILABLE",
        "Admission is temporarily closed by online capacity feedback",
        Map.of(),
        request);
  }

  @ExceptionHandler(TenantAccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(
      TenantAccessDeniedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN, "CAPABILITY_DENIED", "Session is not accessible", Map.of(), request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiError> roleForbidden(
      AccessDeniedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN,
        "ROLE_FORBIDDEN",
        "The authenticated identity lacks the required role",
        Map.of(),
        request);
  }

  @ExceptionHandler(ActiveOperationExistsException.class)
  ResponseEntity<ApiError> activeOperation(
      ActiveOperationExistsException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "OPERATION_ACTIVE",
        "An active operation already exists",
        Map.of(),
        request);
  }

  @ExceptionHandler(IdempotencyConflictException.class)
  ResponseEntity<ApiError> idempotencyConflict(
      IdempotencyConflictException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "IDEMPOTENCY_KEY_REUSED",
        "Idempotency key was already used for a different request",
        Map.of(),
        request);
  }

  @ExceptionHandler(InvalidSessionStateException.class)
  ResponseEntity<ApiError> invalidState(
      InvalidSessionStateException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "SESSION_INVALID_STATE",
        "Session state does not allow this operation",
        Map.of("state", exception.state().name()),
        request);
  }

  @ExceptionHandler(InvalidStateResyncRequestException.class)
  ResponseEntity<ApiError> invalidStateResync(
      InvalidStateResyncRequestException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "STATE_RESYNC_REJECTED",
        "State Resync request is not valid for the current Session",
        Map.of(),
        request);
  }

  @ExceptionHandler(StaleContextEpochException.class)
  ResponseEntity<ApiError> staleContext(
      StaleContextEpochException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "CONTEXT_EPOCH_MISMATCH",
        "Session context changed; retry with fresh state",
        Map.of(),
        request);
  }

  @ExceptionHandler(StaleOperationException.class)
  ResponseEntity<ApiError> staleOperation(
      StaleOperationException exception, HttpServletRequest request) {
    return response(
        HttpStatus.CONFLICT,
        "OPERATION_STATE_MISMATCH",
        "Operation state changed; retry with fresh state",
        Map.of(),
        request);
  }

  @ExceptionHandler({
    MethodArgumentNotValidException.class,
    ConstraintViolationException.class,
    MissingRequestHeaderException.class,
    HttpMessageNotReadableException.class
  })
  ResponseEntity<ApiError> invalidRequest(Exception exception, HttpServletRequest request) {
    return response(
        HttpStatus.BAD_REQUEST, "REQUEST_INVALID", "Request validation failed", Map.of(), request);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<ApiError> internal(Exception exception, HttpServletRequest request) {
    log.error("Unhandled request failure", exception);
    return response(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_ERROR",
        "The request could not be completed",
        Map.of(),
        request);
  }

  private ResponseEntity<ApiError> response(
      HttpStatus status,
      String code,
      String message,
      Map<String, Object> details,
      HttpServletRequest request) {
    String requestId = (String) request.getAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE);
    return ResponseEntity.status(status)
        .body(new ApiError(code, message, details, requestId, Instant.now()));
  }
}
