package io.browsercloud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.browsercloud.application.ProxyProviderAdapter.ErrorCode;
import io.browsercloud.application.ProxyProviderAdapter.HealthState;
import io.browsercloud.application.ProxyProviderAdapter.IpFamily;
import io.browsercloud.application.ProxyProviderAdapter.ProductType;
import io.browsercloud.application.ProxyProviderAdapter.Protocol;
import io.browsercloud.application.ProxyProviderAdapter.ProxyAllocationRequest;
import io.browsercloud.application.ProxyProviderAdapter.ProxyProviderException;
import io.browsercloud.application.ProxyProviderAdapter.RotationPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfiguredHttpProxyProviderAdapterTest {

  private final ConfiguredHttpProxyProviderAdapter adapter =
      new ConfiguredHttpProxyProviderAdapter(
          "commercial-a",
          "http://proxy.internal:8080",
          "203.0.113.10",
          "vault://tenant/proxy/commercial-a",
          List.of("singapore"));

  @Test
  void declaresOnlyCapabilitiesThatTheConfiguredDataPlaneActuallyProvides() {
    var capabilities = adapter.capabilities();

    assertThat(capabilities.protocols())
        .containsExactlyInAnyOrder(Protocol.HTTP, Protocol.HTTPS_CONNECT);
    assertThat(capabilities.productTypes()).containsExactly(ProductType.DATACENTER);
    assertThat(capabilities.ipFamilies()).containsExactly(IpFamily.IPV4);
    assertThat(capabilities.stickySession()).isTrue();
    assertThat(capabilities.countrySelection()).isFalse();
    assertThat(capabilities.citySelection()).isFalse();
    assertThat(capabilities.asnSelection()).isFalse();
    assertThat(capabilities.rotation()).isFalse();
    assertThat(capabilities.bandwidthMetering()).isFalse();
    assertThat(capabilities.providerWebhook()).isFalse();
  }

  @Test
  void allocatesOpaqueCredentialReferenceWithoutCredentialMaterial() {
    var endpoint =
        adapter.allocate(
            new ProxyAllocationRequest(
                "pxy_test",
                "tenant-test",
                "ses-test",
                "singapore",
                null,
                null,
                null,
                IpFamily.IPV4,
                true));

    assertThat(endpoint.endpointId()).isEqualTo("pxy_test");
    assertThat(endpoint.providerId()).isEqualTo("commercial-a");
    assertThat(endpoint.endpoint()).isEqualTo("http://proxy.internal:8080");
    assertThat(endpoint.expectedExitIp()).isEqualTo("203.0.113.10");
    assertThat(endpoint.credentialRef()).isEqualTo("vault://tenant/proxy/commercial-a");
    assertThat(endpoint.credentialRef()).doesNotContain("username", "password");
  }

  @Test
  void rejectsUnsupportedGeographyAndRotationWithNormalizedErrors() {
    assertThatThrownBy(
            () ->
                adapter.allocate(
                    new ProxyAllocationRequest(
                        "pxy_test",
                        "tenant-test",
                        "ses-test",
                        "frankfurt",
                        null,
                        null,
                        null,
                        IpFamily.IPV4,
                        true)))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> {
              assertThat(error.code()).isEqualTo(ErrorCode.GEO_UNAVAILABLE);
              assertThat(error.retryable()).isFalse();
            });

    assertThatThrownBy(() -> adapter.rotate("pbind-test", new RotationPolicy(true, "challenge")))
        .isInstanceOfSatisfying(
            ProxyProviderException.class,
            error -> assertThat(error.code()).isEqualTo(ErrorCode.ROTATION_UNSUPPORTED));
  }

  @Test
  void requiresNodeEvidenceForHealthAndDoesNotInventUsage() {
    assertThat(adapter.health("pxy_test").state()).isEqualTo(HealthState.UNKNOWN);
    assertThat(adapter.health("pxy_test").reason()).contains("Browser Node probe");
    assertThat(adapter.usage("pxy_test").metered()).isFalse();
    assertThat(adapter.usage("pxy_test").ingressBytes()).isZero();
    assertThat(adapter.usage("pxy_test").egressBytes()).isZero();
  }
}
