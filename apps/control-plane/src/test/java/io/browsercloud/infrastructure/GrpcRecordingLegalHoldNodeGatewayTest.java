package io.browsercloud.infrastructure;

import static io.browsercloud.application.RecordingLegalHoldNodeGateway.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.browsercloud.application.BrowserCapacityApplicationService;
import io.browsercloud.persistence.BrowserNodeEntity;
import io.browsercloud.persistence.BrowserNodeJpaRepository;
import io.browsercloud.proto.node.v1.ApplyRecordingLegalHoldRequest;
import io.browsercloud.proto.node.v1.ApplyRecordingLegalHoldResponse;
import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import io.grpc.stub.StreamObserver;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GrpcRecordingLegalHoldNodeGatewayTest {
  @TempDir static Path certificates;
  private Server server;
  private final AtomicInteger calls = new AtomicInteger();
  private final AtomicReference<String> reply = new AtomicReference<>("valid");

  @BeforeAll
  static void certificates() throws Exception {
    openssl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-days",
        "1",
        "-subj",
        "/CN=Owned Hold CA",
        "-keyout",
        "ca.key",
        "-out",
        "ca.crt");
    for (var name : new String[] {"node", "client"}) {
      openssl(
          "req",
          "-new",
          "-newkey",
          "rsa:2048",
          "-nodes",
          "-subj",
          "/CN=browser-node.internal",
          "-addext",
          "subjectAltName=DNS:browser-node.internal",
          "-keyout",
          name + ".key",
          "-out",
          name + ".csr");
      openssl(
          "x509",
          "-req",
          "-days",
          "1",
          "-in",
          name + ".csr",
          "-CA",
          "ca.crt",
          "-CAkey",
          "ca.key",
          "-CAcreateserial",
          "-copy_extensions",
          "copy",
          "-out",
          name + ".crt");
    }
    openssl(
        "req",
        "-x509",
        "-newkey",
        "rsa:2048",
        "-nodes",
        "-days",
        "1",
        "-subj",
        "/CN=Untrusted Client",
        "-keyout",
        "untrusted.key",
        "-out",
        "untrusted.crt");
  }

  @AfterEach
  void stop() throws Exception {
    if (server != null) server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
  }

  @Test
  void exactOnAndOffRequestsCrossRealMutualTls() throws Exception {
    var gateway = start("client");
    var on = gateway.apply(request(HoldMode.ON, null));
    assertThat(on.mode()).isEqualTo(HoldMode.ON);
    assertThat(on.proofHash()).isEqualTo("b".repeat(64));
    assertThat(on.objectVersionCount()).isEqualTo(4); // Includes a historical version.
    var off = gateway.apply(request(HoldMode.OFF, on.proofHash()));
    assertThat(off.mode()).isEqualTo(HoldMode.OFF);
    assertThat(off.authorityEpoch()).isEqualTo(2);
    assertThat(calls.get()).isEqualTo(2);
  }

  @Test
  void everyUnboundOrIncompleteReceiptRemainsUnknown() throws Exception {
    var gateway = start("client");
    for (var mismatch :
        new String[] {
          "operation",
          "epoch",
          "node",
          "recording",
          "mode",
          "proof",
          "inventory",
          "few-versions",
          "many-versions",
          "zero-time",
          "future-time",
          "unsigned-count"
        }) {
      reply.set(mismatch);
      assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
          .as(mismatch)
          .isInstanceOf(HoldResultUnknownException.class)
          .hasMessage("RECORDING_HOLD_RESPONSE_INVALID");
    }
    assertThat(calls.get()).isEqualTo(12);
  }

  @Test
  void rejectionAfterDispatchIsUnknownAndNeverRetriedOrLeaked() throws Exception {
    var gateway = start("client");
    reply.set("rejected");
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
        .isInstanceOf(HoldResultUnknownException.class)
        .hasMessage("RECORDING_HOLD_REMOTE_RESULT_UNKNOWN")
        .hasNoCause();
    assertThat(calls.get()).isEqualTo(1);
  }

  @Test
  void untrustedClientCertificateNeverReachesTheService() throws Exception {
    var gateway = start("untrusted");
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
        .isInstanceOf(HoldResultUnknownException.class)
        .hasNoCause();
    assertThat(calls.get()).isZero();
  }

  @Test
  void invalidRequestsAndMissingOrStaleCapabilitiesDoNotDispatch() {
    var nodes = mock(BrowserNodeJpaRepository.class);
    var capacity = mock(BrowserCapacityApplicationService.class);
    var transport = mock(GrpcTransportFactory.class);
    var gateway = new GrpcRecordingLegalHoldNodeGateway(nodes, capacity, transport);
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.OFF, null)))
        .isInstanceOf(HoldNotDispatchedException.class)
        .hasMessage("RECORDING_HOLD_REQUEST_INVALID");
    verifyNoInteractions(nodes, capacity, transport);
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
        .isInstanceOf(HoldNotDispatchedException.class)
        .hasMessage("RECORDING_HOLD_CAPABILITY_UNAVAILABLE");
    when(capacity.nodeHasCapability(anyString(), anyString(), anyString())).thenReturn(true);
    when(nodes.findById("node_owned")).thenReturn(Optional.empty());
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
        .isInstanceOf(HoldNotDispatchedException.class)
        .hasMessage("RECORDING_HOLD_NODE_UNAVAILABLE");
    var stale = mock(BrowserNodeEntity.class);
    when(stale.isReadyForDispatch()).thenReturn(true);
    when(stale.getLastHeartbeatAt()).thenReturn(Instant.now().minusSeconds(60));
    when(nodes.findById("node_owned")).thenReturn(Optional.of(stale));
    assertThatThrownBy(() -> gateway.apply(request(HoldMode.ON, null)))
        .isInstanceOf(HoldNotDispatchedException.class)
        .hasMessage("RECORDING_HOLD_NODE_UNAVAILABLE");
    verifyNoInteractions(transport);
  }

  private GrpcRecordingLegalHoldNodeGateway start(String client) throws Exception {
    var ssl =
        GrpcSslContexts.forServer(file("node.crt"), file("node.key"))
            .trustManager(file("ca.crt"))
            .clientAuth(ClientAuth.REQUIRE)
            .build();
    server =
        NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
            .sslContext(ssl)
            .addService(
                new NodeControlServiceGrpc.NodeControlServiceImplBase() {
                  @Override
                  public void applyRecordingLegalHold(
                      ApplyRecordingLegalHoldRequest request,
                      StreamObserver<ApplyRecordingLegalHoldResponse> observer) {
                    calls.incrementAndGet();
                    assertThat(request.getTenantId()).isEqualTo("tenant-owned");
                    assertThat(request.getSessionId()).isEqualTo("ses_owned");
                    assertThat(request.getRecordingId()).isEqualTo("rec_owned");
                    assertThat(request.getOperationId())
                        .isEqualTo(
                            request.getAuthorityEpoch() == 1 ? "rhj_owned_on" : "rhj_owned_off");
                    assertThat(request.getModeValue())
                        .isEqualTo(request.getAuthorityEpoch() == 1 ? 1 : 2);
                    assertThat(request.getProfileId()).isEqualTo("profile-owned");
                    assertThat(request.getManifestSha256()).isEqualTo("a".repeat(64));
                    assertThat(request.getManifestBytes()).isEqualTo(100);
                    assertThat(request.getSegmentCount()).isEqualTo(1);
                    assertThat(request.getExecutionBudgetMillis()).isEqualTo(1000);
                    assertThat(request.getPreviousProofHash())
                        .isEqualTo(request.getAuthorityEpoch() == 1 ? "" : "b".repeat(64));
                    if (reply.get().equals("rejected")) {
                      observer.onError(
                          Status.FAILED_PRECONDITION
                              .withDescription("private object and credential detail")
                              .asRuntimeException());
                      return;
                    }
                    var result =
                        ApplyRecordingLegalHoldResponse.newBuilder()
                            .setOperationId(request.getOperationId())
                            .setAuthorityEpoch(request.getAuthorityEpoch())
                            .setNodeId("node_owned")
                            .setRecordingId(request.getRecordingId())
                            .setMode(request.getMode())
                            .setProofHash("b".repeat(64))
                            .setInventorySha256("c".repeat(64))
                            .setObjectVersionCount(4)
                            .setCompletedAtMs(Instant.now().toEpochMilli());
                    switch (reply.get()) {
                      case "operation" -> result.setOperationId("rhj_wrong");
                      case "epoch" -> result.setAuthorityEpoch(99);
                      case "node" -> result.setNodeId("node_wrong");
                      case "recording" -> result.setRecordingId("rec_wrong");
                      case "mode" -> result.setModeValue(0);
                      case "proof" -> result.setProofHash("invalid");
                      case "inventory" -> result.setInventorySha256("invalid");
                      case "few-versions" -> result.setObjectVersionCount(2);
                      case "many-versions" -> result.setObjectVersionCount(7);
                      case "unsigned-count" -> result.setObjectVersionCount(-1);
                      case "zero-time" -> result.setCompletedAtMs(0);
                      case "future-time" ->
                          result.setCompletedAtMs(Instant.now().plusSeconds(60).toEpochMilli());
                      default -> {}
                    }
                    observer.onNext(result.build());
                    observer.onCompleted();
                  }
                })
            .build()
            .start();
    var nodes = mock(BrowserNodeJpaRepository.class);
    var node = mock(BrowserNodeEntity.class);
    when(node.isReadyForDispatch()).thenReturn(true);
    when(node.getLastHeartbeatAt()).thenAnswer(ignored -> Instant.now());
    when(node.getGrpcTarget()).thenReturn("127.0.0.1:" + server.getPort());
    when(nodes.findById("node_owned")).thenReturn(Optional.of(node));
    var capacity = mock(BrowserCapacityApplicationService.class);
    when(capacity.nodeHasCapability("node_owned", "recordingLegalHold", "versioned-cas-journal-v1"))
        .thenReturn(true);
    return new GrpcRecordingLegalHoldNodeGateway(
        nodes,
        capacity,
        new GrpcTransportFactory(
            "production",
            true,
            file("ca.crt").getPath(),
            file(client + ".crt").getPath(),
            file(client + ".key").getPath(),
            "browser-node.internal"));
  }

  private static HoldRequest request(HoldMode mode, String proof) {
    return new HoldRequest(
        mode == HoldMode.ON ? "rhj_owned_on" : "rhj_owned_off",
        mode == HoldMode.ON ? 1 : 2,
        "node_owned",
        "tenant-owned",
        "profile-owned",
        "ses_owned",
        "rec_owned",
        "a".repeat(64),
        100,
        1,
        mode,
        proof,
        1000);
  }

  private static java.io.File file(String name) {
    return certificates.resolve(name).toFile();
  }

  private static void openssl(String... arguments) throws Exception {
    var command = new java.util.ArrayList<String>();
    command.add("openssl");
    command.addAll(java.util.List.of(arguments));
    var process =
        new ProcessBuilder(command)
            .directory(certificates.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    if (!process.waitFor(10, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("Owned certificate generation timed out");
    }
    assertThat(process.exitValue()).isZero();
  }
}
