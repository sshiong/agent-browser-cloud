package io.browsercloud.api;

import static org.assertj.core.api.Assertions.assertThat;

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
}
