package io.browsercloud.capacity;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.browsercloud.application.CapacityAdmissionService;
import io.browsercloud.coordinator.CoordinatorShardRouter;
import io.browsercloud.coordinator.VirtualActorMailbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Build-bound Stage A Coordinator capacity workload.
 *
 * <p>This is intentionally a local, single-process certificate. It validates bounded routing,
 * emergency mailbox priority and admission hysteresis without claiming production cluster capacity.
 */
public final class CoordinatorCapacityCertificateRunner {

  private static final int SHARD_COUNT = 64;
  private static final int TENANT_COUNT = 1_000;
  private static final int ROUTES_PER_ACTOR = 5;
  private static final int MAILBOX_COUNT = 10_000;
  private static final int MAILBOX_ENTRIES = 64;
  private static final long ROUTE_P99_LIMIT_NANOS = 1_000_000;
  private static final long EMERGENCY_P99_LIMIT_NANOS = 1_000_000;
  private static final long MAXIMUM_SHARD_LOAD_BASIS_POINTS = 20_000;

  private CoordinatorCapacityCertificateRunner() {}

  public static void main(String[] args) throws Exception {
    var options = Options.parse(args);
    if (options.actors() < 10_000) {
      throw new IllegalArgumentException("capacity actors must be at least 10000");
    }

    var mapper =
        JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .build();

    var routes = runRouting(options.actors());
    var mailboxes = runMailboxes();
    var admission = runAdmission(options.actors(), options.buildId());

    var gates = new LinkedHashMap<String, Object>();
    gates.put("routeP99WithinLimit", routes.p99Nanos() <= ROUTE_P99_LIMIT_NANOS);
    gates.put("emergencyP99WithinLimit", mailboxes.p99Nanos() <= EMERGENCY_P99_LIMIT_NANOS);
    gates.put("allEmergencyMessagesPreempted", mailboxes.preempted() == MAILBOX_COUNT);
    gates.put("allShardsUsed", routes.shardsUsed() == SHARD_COUNT);
    gates.put(
        "maximumShardLoadWithinLimit",
        routes.maximumShardLoadBasisPoints() <= MAXIMUM_SHARD_LOAD_BASIS_POINTS);
    gates.put("admissionHysteresisPassed", admission);
    var passed = gates.values().stream().allMatch(Boolean.TRUE::equals);

    var certificate = new LinkedHashMap<String, Object>();
    certificate.put("schemaVersion", 1);
    certificate.put("scope", "COORDINATOR_STAGE_A_SINGLE_PROCESS");
    certificate.put("buildId", options.buildId());
    certificate.put("generatedAt", Instant.now().toString());
    certificate.put(
        "environment",
        Map.of(
            "javaVersion", System.getProperty("java.version"),
            "osArch", System.getProperty("os.arch"),
            "osName", System.getProperty("os.name"),
            "availableProcessors", Runtime.getRuntime().availableProcessors()));
    certificate.put(
        "loadModel",
        Map.of(
            "actors", options.actors(),
            "routesPerActor", ROUTES_PER_ACTOR,
            "tenantCount", TENANT_COUNT,
            "shardCount", SHARD_COUNT,
            "mailboxes", MAILBOX_COUNT,
            "mailboxEntries", MAILBOX_ENTRIES));
    certificate.put(
        "limitsNanos",
        Map.of(
            "routeP99",
            ROUTE_P99_LIMIT_NANOS,
            "emergencyOfferAndPollP99",
            EMERGENCY_P99_LIMIT_NANOS,
            "maximumShardLoadBasisPoints",
            MAXIMUM_SHARD_LOAD_BASIS_POINTS));
    certificate.put("routing", routes.toMap());
    certificate.put("emergencyMailbox", mailboxes.toMap());
    certificate.put("gates", gates);
    certificate.put("passed", passed);
    certificate.put("certificateHash", sha256(mapper.writeValueAsBytes(certificate)));

    Files.createDirectories(options.output().toAbsolutePath().getParent());
    mapper.writeValue(options.output().toFile(), certificate);
    System.out.printf(
        "COORDINATOR_CAPACITY_CERTIFICATE_%s actors=%d route_p99_ns=%d emergency_p99_ns=%d output=%s%n",
        passed ? "OK" : "FAILED",
        options.actors(),
        routes.p99Nanos(),
        mailboxes.p99Nanos(),
        options.output().toAbsolutePath());
    if (!passed) {
      System.exit(1);
    }
  }

  private static RoutingResult runRouting(int actors) {
    var router = new CoordinatorShardRouter(SHARD_COUNT);
    router.repartitionTenant("tenant-hot", 32, true);
    for (var index = 0; index < 20_000; index++) {
      router.route("tenant-" + (index % TENANT_COUNT), "ses-warm-" + index);
    }

    var samples = new long[Math.multiplyExact(actors, ROUTES_PER_ACTOR)];
    var shardCounts = new int[SHARD_COUNT];
    var started = System.nanoTime();
    var sample = 0;
    for (var actor = 0; actor < actors; actor++) {
      var tenant = actor % 10 == 0 ? "tenant-hot" : "tenant-" + (actor % TENANT_COUNT);
      var session = "ses-capacity-" + actor;
      for (var route = 0; route < ROUTES_PER_ACTOR; route++) {
        var before = System.nanoTime();
        var resolved = router.route(tenant, session);
        samples[sample++] = System.nanoTime() - before;
        shardCounts[resolved.shardId()]++;
      }
    }
    var elapsed = System.nanoTime() - started;
    Arrays.sort(samples);
    var shardsUsed = (int) Arrays.stream(shardCounts).filter(count -> count > 0).count();
    var minimum = Arrays.stream(shardCounts).min().orElse(0);
    var maximum = Arrays.stream(shardCounts).max().orElse(0);
    var average = samples.length / SHARD_COUNT;
    return new RoutingResult(
        percentile(samples, 0.95),
        percentile(samples, 0.99),
        samples.length * 1_000_000_000L / Math.max(1, elapsed),
        shardsUsed,
        minimum,
        maximum,
        maximum * 10_000L / Math.max(1, average),
        router.route("tenant-hot", "ses-capacity-0").routeEpoch());
  }

  private static MailboxResult runMailboxes() {
    var samples = new long[MAILBOX_COUNT];
    var preempted = 0;
    for (var mailboxIndex = 0; mailboxIndex < MAILBOX_COUNT; mailboxIndex++) {
      var mailbox = new VirtualActorMailbox<String>(MAILBOX_ENTRIES);
      for (var entry = 0; entry < MAILBOX_ENTRIES; entry++) {
        if (!mailbox.offer("telemetry-" + entry, 1)) {
          throw new IllegalStateException("telemetry did not fill the mailbox");
        }
      }
      var before = System.nanoTime();
      var accepted = mailbox.offer("emergency-stop", 100);
      var first = mailbox.poll().orElse("");
      samples[mailboxIndex] = System.nanoTime() - before;
      if (accepted && "emergency-stop".equals(first)) {
        preempted++;
      }
    }
    Arrays.sort(samples);
    return new MailboxResult(percentile(samples, 0.95), percentile(samples, 0.99), preempted);
  }

  private static boolean runAdmission(int actors, String buildId) {
    var admission = new CapacityAdmissionService(buildId, actors, 85, 70);
    var closed = !admission.update((actors * 86) / 100, false).admissionOpen();
    var stayedClosed = !admission.update((actors * 80) / 100, false).admissionOpen();
    var reopened = admission.update((actors * 70) / 100, false).admissionOpen();
    var pressureClosed = !admission.update((actors * 10) / 100, true).admissionOpen();
    return closed && stayedClosed && reopened && pressureClosed;
  }

  private static long percentile(long[] sorted, double percentile) {
    var index = (int) Math.ceil(percentile * sorted.length) - 1;
    return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
  }

  private static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private record RoutingResult(
      long p95Nanos,
      long p99Nanos,
      long operationsPerSecond,
      int shardsUsed,
      int minimumShardRoutes,
      int maximumShardRoutes,
      long maximumShardLoadBasisPoints,
      long routeEpoch) {

    Map<String, Object> toMap() {
      return Map.of(
          "p95Nanos", p95Nanos,
          "p99Nanos", p99Nanos,
          "operationsPerSecond", operationsPerSecond,
          "shardsUsed", shardsUsed,
          "minimumShardRoutes", minimumShardRoutes,
          "maximumShardRoutes", maximumShardRoutes,
          "maximumShardLoadBasisPoints", maximumShardLoadBasisPoints,
          "routeEpoch", routeEpoch);
    }
  }

  private record MailboxResult(long p95Nanos, long p99Nanos, int preempted) {

    Map<String, Object> toMap() {
      return Map.of("p95Nanos", p95Nanos, "p99Nanos", p99Nanos, "preempted", preempted);
    }
  }

  private record Options(Path output, int actors, String buildId) {

    static Options parse(String[] args) {
      Path output = null;
      var actors = 50_000;
      var buildId = "control-plane-local";
      for (var index = 0; index < args.length; index += 2) {
        if (index + 1 >= args.length) {
          throw new IllegalArgumentException("missing value for " + args[index]);
        }
        switch (args[index]) {
          case "--output" -> output = Path.of(args[index + 1]);
          case "--actors" -> actors = Integer.parseInt(args[index + 1]);
          case "--build-id" -> buildId = args[index + 1];
          default -> throw new IllegalArgumentException("unknown option " + args[index]);
        }
      }
      if (output == null) {
        throw new IllegalArgumentException("--output is required");
      }
      if (buildId.isBlank() || buildId.length() > 128) {
        throw new IllegalArgumentException("build id must contain 1 to 128 characters");
      }
      return new Options(output, actors, buildId);
    }
  }
}
