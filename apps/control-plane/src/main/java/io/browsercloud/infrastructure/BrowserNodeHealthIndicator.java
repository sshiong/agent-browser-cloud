package io.browsercloud.infrastructure;

import io.browsercloud.proto.node.v1.NodeControlServiceGrpc;
import io.browsercloud.proto.node.v1.PingRequest;
import io.grpc.ManagedChannel;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 通过正式 NodeControl/Ping 契约验证 Control Plane 到 Browser Node 的连接。 */
@Component
public class BrowserNodeHealthIndicator implements HealthIndicator {

  private final ManagedChannel channel;

  BrowserNodeHealthIndicator(
      GrpcTransportFactory transportFactory,
      @Value("${browser-node.grpc-target:localhost:9090}") String grpcTarget) {
    this.channel = transportFactory.nodeChannel(grpcTarget);
  }

  @Override
  public Health health() {
    try {
      var response =
          NodeControlServiceGrpc.newBlockingStub(channel)
              .withDeadlineAfter(1, TimeUnit.SECONDS)
              .ping(PingRequest.newBuilder().setCallerId("control-plane").build());
      return Health.up()
          .withDetail("nodeId", response.getNodeId())
          .withDetail("serviceVersion", response.getServiceVersion())
          .build();
    } catch (Exception exception) {
      return Health.down().withDetail("reason", "Browser Node is unavailable").build();
    }
  }

  @PreDestroy
  void closeChannel() {
    channel.shutdown();
  }
}
