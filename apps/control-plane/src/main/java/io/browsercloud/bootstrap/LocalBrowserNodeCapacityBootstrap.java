package io.browsercloud.bootstrap;

import io.browsercloud.api.RecordNodePressureRequest;
import io.browsercloud.api.RegisterBrowserNodeRequest;
import io.browsercloud.application.BrowserCapacityApplicationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅用于非生产单 Node 开发闭环。生产环境必须由认证后的 Browser Node 心跳注册， 不会自动创建虚构容量。 */
@Component
@ConditionalOnExpression(
    "'${app.environment:local}' != 'production' and '${browser-density.bootstrap-local-node.enabled:true}' == 'true'")
public class LocalBrowserNodeCapacityBootstrap implements ApplicationRunner {

  private final BrowserCapacityApplicationService service;
  private final String nodeId;
  private final String grpcTarget;

  public LocalBrowserNodeCapacityBootstrap(
      BrowserCapacityApplicationService service,
      @Value("${browser-density.bootstrap-local-node.node-id:node_local}") String nodeId,
      @Value("${browser-node.grpc-target:localhost:9090}") String grpcTarget) {
    this.service = service;
    this.nodeId = nodeId;
    this.grpcTarget = grpcTarget;
  }

  @Override
  public void run(ApplicationArguments args) {
    service.registerNode(
        nodeId,
        new RegisterBrowserNodeRequest(
            "local",
            grpcTarget,
            10_000,
            16_384,
            4096,
            0,
            20,
            10,
            true,
            false,
            false,
            true,
            Map.of("environment", "local", "capacitySource", "development-bootstrap")),
        Instant.now());
  }

  @Scheduled(fixedDelayString = "${browser-density.bootstrap-local-node.heartbeat-ms:20000}")
  public void heartbeat() {
    service.recordPressure(
        nodeId,
        new RecordNodePressureRequest(
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null),
        Instant.now());
  }
}
