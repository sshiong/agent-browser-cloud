package io.browsercloud.coordinator;

/** Physical worker ownership check for a logical Coordinator Shard. */
@FunctionalInterface
public interface CoordinatorShardLocality {
  boolean owns(int shardId);
}
