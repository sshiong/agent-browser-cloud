package io.browsercloud.coordinator;

import java.util.List;

/** Exact-version Browser transaction Site Policy carried on Runtime lifecycle commands. */
public record BrowserTransactionPolicy(
    long version,
    List<String> expectedOrigins,
    List<String> paymentSecurityRoutePrefixes,
    List<String> criticalTransactionRoutePrefixes,
    String policyHash) {

  public BrowserTransactionPolicy {
    expectedOrigins = List.copyOf(expectedOrigins);
    paymentSecurityRoutePrefixes = List.copyOf(paymentSecurityRoutePrefixes);
    criticalTransactionRoutePrefixes = List.copyOf(criticalTransactionRoutePrefixes);
  }

  public static BrowserTransactionPolicy empty() {
    return new BrowserTransactionPolicy(0, List.of(), List.of(), List.of(), "");
  }
}
