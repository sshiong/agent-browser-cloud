package io.browsercloud.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.application.StateResyncAdmissionService.StateResyncBudgetExceededException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerTest {

  private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

  @Test
  void mapsAuthoritativeDatabaseOutagesToAStableServiceUnavailableEnvelope() {
    var request = new MockHttpServletRequest();
    request.setAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE, "req_database_outage");

    var response =
        handler.internal(
            new IllegalStateException(
                "transaction failed",
                new DataAccessResourceFailureException(
                    "connection details that must not reach the client")),
            request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("DATABASE_UNAVAILABLE");
    assertThat(response.getBody().message())
        .isEqualTo("The authoritative database is temporarily unavailable");
    assertThat(response.getBody().details()).isEmpty();
    assertThat(response.getBody().requestId()).isEqualTo("req_database_outage");
  }

  @Test
  void keepsUnrelatedFailuresOnTheGenericInternalErrorContract() {
    var request = new MockHttpServletRequest();
    request.setAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE, "req_internal");

    var response = handler.internal(new IllegalStateException("unexpected"), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
  }

  @Test
  void mapsStateResyncBudgetExhaustionToRetryableTooManyRequests() {
    var request = new MockHttpServletRequest();
    request.setAttribute(ApiRequestContextFilter.REQUEST_ID_ATTRIBUTE, "req_resync_budget");

    var response =
        handler.stateResyncBudgetExceeded(
            new StateResyncBudgetExceededException("AUTOMATIC_CIRCUIT", 60), request);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("60");
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("STATE_RESYNC_BUDGET_EXHAUSTED");
    assertThat(response.getBody().details())
        .containsEntry("scope", "AUTOMATIC_CIRCUIT")
        .containsEntry("retryAfterSeconds", 60);
    assertThat(response.getBody().requestId()).isEqualTo("req_resync_budget");
  }
}
