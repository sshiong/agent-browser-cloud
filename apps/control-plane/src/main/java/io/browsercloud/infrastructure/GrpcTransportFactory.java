package io.browsercloud.infrastructure;

import io.grpc.ManagedChannel;
import io.grpc.ServerBuilder;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.ClientAuth;
import java.io.File;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Internal gRPC transport policy: production is mTLS-only; local plaintext is explicit. */
@Component
public class GrpcTransportFactory {

  private final boolean tlsEnabled;
  private final String caCertificate;
  private final String certificate;
  private final String privateKey;
  private final String nodeServerName;

  public GrpcTransportFactory(
      @Value("${app.environment:local}") String environment,
      @Value("${grpc.tls.enabled:false}") boolean tlsEnabled,
      @Value("${grpc.tls.ca-certificate:}") String caCertificate,
      @Value("${grpc.tls.certificate:}") String certificate,
      @Value("${grpc.tls.private-key:}") String privateKey,
      @Value("${grpc.tls.node-server-name:browser-node.internal}") String nodeServerName) {
    if ("production".equalsIgnoreCase(environment) && !tlsEnabled) {
      throw new IllegalStateException("Internal gRPC mTLS is mandatory in production");
    }
    this.tlsEnabled = tlsEnabled;
    this.caCertificate = caCertificate;
    this.certificate = certificate;
    this.privateKey = privateKey;
    this.nodeServerName = nodeServerName;
    if (tlsEnabled) {
      requireReadable(caCertificate, "CA certificate");
      requireReadable(certificate, "service certificate");
      requireReadable(privateKey, "service private key");
    }
  }

  public ManagedChannel nodeChannel(String target) {
    var builder = NettyChannelBuilder.forTarget(target);
    if (!tlsEnabled) {
      return builder.usePlaintext().build();
    }
    try {
      var sslContext =
          GrpcSslContexts.forClient()
              .trustManager(new File(caCertificate))
              .keyManager(new File(certificate), new File(privateKey))
              .build();
      return builder.sslContext(sslContext).overrideAuthority(nodeServerName).build();
    } catch (javax.net.ssl.SSLException exception) {
      throw new IllegalStateException("Failed to configure Browser Node mTLS client", exception);
    }
  }

  public ServerBuilder<?> nodeEventServer(int port) {
    if (!tlsEnabled) {
      return NettyServerBuilder.forPort(port);
    }
    try {
      var sslContext =
          GrpcSslContexts.forServer(new File(certificate), new File(privateKey))
              .trustManager(new File(caCertificate))
              .clientAuth(ClientAuth.REQUIRE)
              .build();
      return NettyServerBuilder.forPort(port).sslContext(sslContext);
    } catch (javax.net.ssl.SSLException exception) {
      throw new IllegalStateException("Failed to configure Node Event mTLS server", exception);
    }
  }

  public boolean tlsEnabled() {
    return tlsEnabled;
  }

  private static void requireReadable(String path, String description) {
    if (path == null || path.isBlank() || !new File(path).isFile() || !new File(path).canRead()) {
      throw new IllegalStateException("Readable gRPC " + description + " is required");
    }
  }
}
