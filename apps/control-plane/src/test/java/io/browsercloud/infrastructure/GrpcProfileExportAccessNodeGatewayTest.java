package io.browsercloud.infrastructure;

import static io.browsercloud.application.ProfileExportAccessNodeGateway.*;
import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.Status;
import org.junit.jupiter.api.Test;

class GrpcProfileExportAccessNodeGatewayTest {

  @Test
  void mapsStorageValidationFailureToRejectedArchive() {
    var failure =
        GrpcProfileExportAccessNodeGateway.mapNodeFailure(
            Status.FAILED_PRECONDITION
                .withDescription("sensitive helper detail")
                .asRuntimeException());

    assertThat(failure)
        .isInstanceOf(ProfileExportNodeRejectedException.class)
        .hasMessage("PROFILE_EXPORT_ARCHIVE_REJECTED");
  }

  @Test
  void mapsTransportFailureToUnavailableDataPlane() {
    var failure =
        GrpcProfileExportAccessNodeGateway.mapNodeFailure(Status.UNAVAILABLE.asRuntimeException());

    assertThat(failure)
        .isInstanceOf(ProfileExportNodeUnavailableException.class)
        .hasMessage("PROFILE_EXPORT_NODE_FAILED");
  }
}
