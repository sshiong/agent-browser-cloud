package io.browsercloud.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.browsercloud.application.ProxyBindingProbeNodeGateway.NoProbeNodeAvailableException;
import io.browsercloud.application.ProxyBindingProbeNodeGateway.ProbeNodeRejectedException;
import io.browsercloud.application.ProxyBindingProbeNodeGateway.ProbeRequest;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.ProbeProxyBindingRequest;
import io.browsercloud.proto.node.v1.ProbeProxyBindingResponse;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GrpcProxyBindingProbeNodeGatewayTest {

  private Server server;

  @AfterEach
  void stopServer() {
    if (server != null) {
      server.shutdownNow();
    }
  }

  @Test
  void validatesAndReturnsTheCredentialFreeNodeObservation() throws Exception {
    server = startServer(false);
    var nodes = mock(BrowserNodeJpaRepository.class);
    when(nodes.findProxyColdProbeCandidates(any(), any()))
        .thenReturn(List.of(node(server.getPort())));
    var gateway =
        new GrpcProxyBindingProbeNodeGateway(
            nodes, new GrpcTransportFactory("local", false, "", "", "", "browser-node.internal"));

    var result = gateway.probe(request());

    assertThat(result.succeeded()).isTrue();
    assertThat(result.nodeId()).isEqualTo("node_probe");
    assertThat(result.observedExitIp()).isEqualTo("203.0.113.10");
    assertThat(result.failureCode()).isEmpty();
  }

  @Test
  void rejectsAMismatchedNodeIdentity() throws Exception {
    server = startServer(true);
    var nodes = mock(BrowserNodeJpaRepository.class);
    when(nodes.findProxyColdProbeCandidates(any(), any()))
        .thenReturn(List.of(node(server.getPort())));
    var gateway =
        new GrpcProxyBindingProbeNodeGateway(
            nodes, new GrpcTransportFactory("local", false, "", "", "", "browser-node.internal"));

    assertThatThrownBy(() -> gateway.probe(request()))
        .isInstanceOf(ProbeNodeRejectedException.class)
        .hasMessage("PROXY_COLD_PROBE_RESPONSE_INVALID");
  }

  @Test
  void failsClosedWhenNoCapableFreshNodeExists() {
    var nodes = mock(BrowserNodeJpaRepository.class);
    when(nodes.findProxyColdProbeCandidates(any(), any())).thenReturn(List.of());
    var gateway =
        new GrpcProxyBindingProbeNodeGateway(
            nodes, new GrpcTransportFactory("local", false, "", "", "", "browser-node.internal"));

    assertThatThrownBy(() -> gateway.probe(request()))
        .isInstanceOf(NoProbeNodeAvailableException.class)
        .hasMessage("NO_PROXY_COLD_PROBE_NODE");
  }

  private Server startServer(boolean mismatchedNode) throws Exception {
    return NettyServerBuilder.forPort(0)
        .addService(
            new NodeControlServiceGrpc.NodeControlServiceImplBase() {
              @Override
              public void probeProxyBinding(
                  ProbeProxyBindingRequest request,
                  StreamObserver<ProbeProxyBindingResponse> observer) {
                assertThat(request.getCredentialRef())
                    .isEqualTo("vault://tenant-test/proxy/primary");
                observer.onNext(
                    ProbeProxyBindingResponse.newBuilder()
                        .setProbeId(request.getProbeId())
                        .setBindingProfileId(request.getBindingProfileId())
                        .setNodeId(mismatchedNode ? "node_wrong" : "node_probe")
                        .setSucceeded(true)
                        .setLatencyMs(42)
                        .setObservedExitIp("203.0.113.10")
                        .build());
                observer.onCompleted();
              }
            })
        .build()
        .start();
  }

  private static ProbeRequest request() {
    return new ProbeRequest(
        "prb_1234567890abcdef",
        "tenant-test",
        "pbind_1234567890123456",
        "static-test",
        "local",
        "203.0.113.10",
        "vault://tenant-test/proxy/primary");
  }

  private static BrowserNodeEntity node(int port) {
    return new BrowserNodeEntity(
        "node_probe",
        "local",
        "127.0.0.1:" + port,
        4_000,
        8_192,
        2_048,
        0,
        0,
        20,
        8,
        true,
        false,
        false,
        false,
        true,
        "{\"proxyColdProbe\":\"network-helper-v1\"}",
        Instant.now());
  }
}
