package io.browsercloud.coordinator.exceptions;

/** 同一幂等键被用于语义不同的请求。 */
public class IdempotencyConflictException extends RuntimeException {

  public IdempotencyConflictException() {
    super("Idempotency key was already used for a different request");
  }
}
