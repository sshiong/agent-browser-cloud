package io.browsercloud.coordinator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic virtual-actor shard routing with hot-tenant virtual partitions and route epochs.
 */
@Component
public class CoordinatorShardRouter {

  private final int shardCount;
  private final Map<String, Integer> tenantPartitions = new ConcurrentHashMap<>();
  private final AtomicLong routeEpoch = new AtomicLong(1);

  public CoordinatorShardRouter(@Value("${coordinator.shard-count:16}") int shardCount) {
    if (shardCount < 1 || shardCount > 4096) {
      throw new IllegalArgumentException("coordinator.shard-count must be between 1 and 4096");
    }
    this.shardCount = shardCount;
  }

  public Route route(String tenantId, String sessionId) {
    var partitions = tenantPartitions.getOrDefault(tenantId, 1);
    var virtualPartition = positiveHash(sessionId) % partitions;
    var shard = positiveHash(tenantId + "\u0000" + virtualPartition) % shardCount;
    return new Route(shard, virtualPartition, routeEpoch.get());
  }

  /** Changes routing only at an externally verified safe point; epoch fences old owners. */
  public long repartitionTenant(String tenantId, int partitions, boolean safePoint) {
    if (!safePoint) {
      throw new IllegalStateException("Hot tenant repartition requires a coordinator safe point");
    }
    if (partitions < 1 || partitions > 256) {
      throw new IllegalArgumentException("virtual partitions must be between 1 and 256");
    }
    tenantPartitions.put(tenantId, partitions);
    return routeEpoch.incrementAndGet();
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
