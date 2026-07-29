package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import io.browsercloud.infrastructure.GrpcProfileImportNodeGateway.ProfileImportNodeRejectedException;
import io.browsercloud.infrastructure.GrpcProfileImportNodeGateway.ProfileImportNodeUnavailableException;
import io.grpc.Status;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class GrpcProfileImportNodeGatewayTest {

  @Test
  void mapsStorageValidationFailureToRejectedArchive() {
    var failure =
        GrpcProfileImportNodeGateway.mapNodeFailure(
            new ExecutionException(
                Status.FAILED_PRECONDITION
                    .withDescription("sensitive helper detail")
                    .asRuntimeException()));

    assertThat(failure)
        .isInstanceOf(ProfileImportNodeRejectedException.class)
        .hasMessage("PROFILE_IMPORT_ARCHIVE_REJECTED");
  }

  @Test
  void mapsTransportFailureToUnavailableDataPlane() {
    var failure =
        GrpcProfileImportNodeGateway.mapNodeFailure(
            new ExecutionException(Status.UNAVAILABLE.asRuntimeException()));

    assertThat(failure)
        .isInstanceOf(ProfileImportNodeUnavailableException.class)
        .hasMessage("PROFILE_IMPORT_NODE_FAILED");
  }
}
