package io.browsercloud.application;

/** Internal mTLS Browser Node gateway for credential-free cold Proxy Binding probes. */
public interface ProxyBindingProbeNodeGateway {

  ProbeResult probe(ProbeRequest request);

  record ProbeRequest(
      String probeId,
      String tenantId,
      String bindingProfileId,
      String providerId,
      String region,
      String expectedExitIp,
      String credentialRef) {}

  record ProbeResult(
      String probeId,
      String bindingProfileId,
      String nodeId,
      boolean succeeded,
      int latencyMs,
      String observedExitIp,
      String failureCode) {}

  final class NoProbeNodeAvailableException extends RuntimeException {
    public NoProbeNodeAvailableException(String reason) {
      super(reason);
    }

    public NoProbeNodeAvailableException(String reason, Throwable cause) {
      super(reason, cause);
    }
  }

  final class ProbeNodeRejectedException extends RuntimeException {
    public ProbeNodeRejectedException(String reason) {
      super(reason);
    }
  }
}
