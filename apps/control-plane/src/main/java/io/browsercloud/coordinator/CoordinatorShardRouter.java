package io.browsercloud.coordinator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic virtual-actor shard routing with hot-tenant virtual partitions and route epochs.
 */
@Component
public class CoordinatorShardRouter {

  private final int shardCount;

  public CoordinatorShardRouter(@Value("${coordinator.shard-count:16}") int shardCount) {
    if (shardCount < 1 || shardCount > 4096) {
      throw new IllegalArgumentException("coordinator.shard-count must be between 1 and 4096");
    }
    this.shardCount = shardCount;
  }

  /** Default route used before a tenant has an explicit PostgreSQL route row. */
  public Route route(String tenantId, String sessionId) {
    return route(tenantId, sessionId, 1, 1);
  }

  /**
   * Pure deterministic calculation; partition count and epoch always come from persistent state.
   */
  public Route route(String tenantId, String sessionId, int partitions, long routeEpoch) {
    if (partitions < 1 || partitions > 256) {
      throw new IllegalArgumentException("virtual partitions must be between 1 and 256");
    }
    if (routeEpoch < 1) {
      throw new IllegalArgumentException("route epoch must be positive");
    }
    var virtualPartition = positiveHash(sessionId) % partitions;
    var shard = positiveHash(tenantId + "\u0000" + virtualPartition) % shardCount;
    return new Route(shard, virtualPartition, routeEpoch);
  }

  private static int positiveHash(String value) {
    try {
      var digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return Integer.parseUnsignedInt(HexFormat.of().formatHex(digest, 0, 4), 16)
          & Integer.MAX_VALUE;
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public record Route(int shardId, int virtualPartition, long routeEpoch) {}
}
