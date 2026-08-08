package io.browsercloud.infrastructure;

import io.browsercloud.application.ProxyBindingProbeNodeGateway;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.ProbeProxyBindingRequest;
import io.grpc.StatusRuntimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/** Routes cold Binding probes only to fresh Nodes advertising the isolated Helper capability. */
@Component
public class GrpcProxyBindingProbeNodeGateway implements ProxyBindingProbeNodeGateway {

  private static final Duration NODE_HEARTBEAT_TTL = Duration.ofSeconds(45);
  private static final Set<String> FAILURE_CODES =
      Set.of("TIMEOUT", "CIRCUIT_OPEN", "EXIT_MISMATCH", "HELPER_UNAVAILABLE", "PROBE_FAILED");

  private final BrowserNodeJpaRepository nodes;
  private final GrpcTransportFactory transportFactory;

  public GrpcProxyBindingProbeNodeGateway(
      BrowserNodeJpaRepository nodes, GrpcTransportFactory transportFactory) {
    this.nodes = nodes;
    this.transportFactory = transportFactory;
  }

  @Override
  public ProbeResult probe(ProbeRequest request) {
    var candidates =
        nodes.findProxyColdProbeCandidates(
            blankToNull(request.region()), Instant.now().minus(NODE_HEARTBEAT_TTL));
    if (candidates.isEmpty()) {
      throw new NoProbeNodeAvailableException("NO_PROXY_COLD_PROBE_NODE");
    }
    RuntimeException lastFailure = null;
    for (var node : candidates) {
      var channel = transportFactory.nodeChannel(node.getGrpcTarget());
      try {
        var response =
            NodeControlServiceGrpc.newBlockingStub(channel)
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .probeProxyBinding(
                    ProbeProxyBindingRequest.newBuilder()
                        .setProbeId(request.probeId())
                        .setTenantId(request.tenantId())
                        .setBindingProfileId(request.bindingProfileId())
                        .setProviderId(request.providerId())
                        .setExpectedExitIp(request.expectedExitIp())
                        .setCredentialRef(request.credentialRef())
                        .build());
        var observedExit = response.hasObservedExitIp() ? response.getObservedExitIp() : null;
        if (!request.probeId().equals(response.getProbeId())
            || !request.bindingProfileId().equals(response.getBindingProfileId())
            || !node.getNodeId().equals(response.getNodeId())
            || response.getLatencyMs() > 30000
            || response.getSucceeded()
                != (observedExit != null
                    && request.expectedExitIp().equals(observedExit)
                    && response.getErrorCode().isBlank())
            || (!response.getSucceeded() && !FAILURE_CODES.contains(response.getErrorCode()))) {
          throw new ProbeNodeRejectedException("PROXY_COLD_PROBE_RESPONSE_INVALID");
        }
        return new ProbeResult(
            response.getProbeId(),
            response.getBindingProfileId(),
            response.getNodeId(),
            response.getSucceeded(),
            Math.toIntExact(Integer.toUnsignedLong(response.getLatencyMs())),
            observedExit,
            response.getErrorCode());
      } catch (StatusRuntimeException exception) {
        lastFailure = new NoProbeNodeAvailableException("PROXY_COLD_PROBE_NODE_FAILED", exception);
      } finally {
        channel.shutdown();
      }
    }
    throw lastFailure == null
        ? new NoProbeNodeAvailableException("NO_PROXY_COLD_PROBE_NODE")
        : lastFailure;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
