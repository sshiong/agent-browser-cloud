package io.browsercloud.application;

import java.io.InputStream;

/** Streams an archive to a capability-advertising Browser Node over authenticated gRPC. */
public interface ProfileImportNodeGateway {

  ProfileImportNodeResult upload(ProfileImportNodeRequest request, InputStream archive);

  record ProfileImportNodeRequest(
      String importId,
      String tenantId,
      String profileId,
      String checkpointId,
      String runtimeBuildId,
      String archiveSha256,
      long archiveSizeBytes) {}

  record ProfileImportNodeResult(
      String importId,
      String nodeId,
      String profileId,
      String checkpointId,
      long checkpointEpoch,
      long profileWriteEpoch,
      long coreSizeBytes,
      long checkpointFileCount,
      String archiveSha256,
      long archiveSizeBytes) {}
}
