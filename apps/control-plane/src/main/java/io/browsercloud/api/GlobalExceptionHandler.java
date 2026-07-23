package io.browsercloud.api;

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

  @ExceptionHandler(TenantAccessDeniedException.class)
  ResponseEntity<ApiError> forbidden(
      TenantAccessDeniedException exception, HttpServletRequest request) {
    return response(
        HttpStatus.FORBIDDEN, "CAPABILITY_DENIED", "Session is not accessible", Map.of(), request);
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
