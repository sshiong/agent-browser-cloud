package io.browsercloud.application;

import static io.browsercloud.api.AgentExpectedOutcomeModels.*;

import io.browsercloud.coordinator.NodeEvent.StateUpdated;
import java.net.IDN;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Normalizes declarations once, then evaluates only hashes against an exact final state. */
final class AgentExpectedOutcomePolicy {

  private AgentExpectedOutcomePolicy() {}

  static List<ExpectedOutcomeDefinition> normalize(
      List<ExpectedOutcomeRequest> requests, List<String> allowedDomains) {
    if (requests == null || requests.isEmpty()) return List.of();
    var definitions = new ArrayList<ExpectedOutcomeDefinition>();
    var ids = new HashSet<String>();
    for (var request : requests) {
      var id = request.outcomeId().trim();
      if (!ids.add(id)) throw invalid("EXPECTED_OUTCOME_ID_DUPLICATE");
      var target = isTarget(request.type());
      var role = normalizeRole(request.role());
      if (target && role == null) throw invalid("EXPECTED_OUTCOME_ROLE_REQUIRED");
      if (!target && role != null) throw invalid("EXPECTED_OUTCOME_ROLE_FORBIDDEN");
      var value =
          request.type() == ExpectedOutcomeType.FINAL_URL_EQUALS
              ? normalizedUrl(request.matchValue(), allowedDomains)
              : normalizedText(request.matchValue());
      if (value.isBlank()) throw invalid("EXPECTED_OUTCOME_VALUE_INVALID");
      definitions.add(new ExpectedOutcomeDefinition(id, request.type(), role, sha256(value)));
    }
    return List.copyOf(definitions);
  }

  static List<ExpectedOutcomeEvaluation> evaluate(
      List<ExpectedOutcomeDefinition> definitions, StateUpdated state) {
    return definitions.stream().map(definition -> evaluate(definition, state)).toList();
  }

  private static ExpectedOutcomeEvaluation evaluate(
      ExpectedOutcomeDefinition definition, StateUpdated state) {
    if (definition.type() == ExpectedOutcomeType.FINAL_URL_EQUALS) {
      var observed = sha256(normalizedObservedUrl(state.url()));
      return result(
          definition,
          observed.equals(definition.expectedValueHash()),
          "EXPECTED_FINAL_URL_MATCHED",
          "EXPECTED_FINAL_URL_MISMATCH",
          observed);
    }
    if (definition.type() == ExpectedOutcomeType.PAGE_TITLE_EQUALS) {
      var observed = sha256(normalizedText(state.title()));
      return result(
          definition,
          observed.equals(definition.expectedValueHash()),
          "EXPECTED_PAGE_TITLE_MATCHED",
          "EXPECTED_PAGE_TITLE_MISMATCH",
          observed);
    }
    var matching =
        state.targets().stream()
            .filter(target -> target.visible() && !target.sensitive())
            .filter(target -> definition.role().equals(normalizeRole(target.role())))
            .filter(
                target ->
                    sha256(normalizedText(target.name())).equals(definition.expectedValueHash()))
            .toList();
    if (definition.type() == ExpectedOutcomeType.TARGET_ABSENT) {
      if (!matching.isEmpty()) {
        return result(definition, false, "EXPECTED_TARGET_ABSENT", "EXPECTED_TARGET_PRESENT", null);
      }
      if (!"COMPLETE".equals(state.stateQuality())) return indeterminate(definition);
      return result(definition, true, "EXPECTED_TARGET_ABSENT", "EXPECTED_TARGET_PRESENT", null);
    }
    if (matching.isEmpty()) {
      if (!"COMPLETE".equals(state.stateQuality())) return indeterminate(definition);
      return result(definition, false, "EXPECTED_TARGET_PRESENT", "EXPECTED_TARGET_MISSING", null);
    }
    if (definition.type() == ExpectedOutcomeType.TARGET_PRESENT) {
      return result(definition, true, "EXPECTED_TARGET_PRESENT", "EXPECTED_TARGET_MISSING", null);
    }
    var propertySatisfied =
        matching.stream()
            .anyMatch(
                target ->
                    switch (definition.type()) {
                      case TARGET_CHECKED -> Boolean.TRUE.equals(target.checked());
                      case TARGET_UNCHECKED -> Boolean.FALSE.equals(target.checked());
                      case TARGET_SELECTED -> Boolean.TRUE.equals(target.selected());
                      case TARGET_UNSELECTED -> Boolean.FALSE.equals(target.selected());
                      default -> false;
                    });
    return result(
        definition,
        propertySatisfied,
        "EXPECTED_TARGET_STATE_MATCHED",
        "EXPECTED_TARGET_STATE_MISMATCH",
        null);
  }

  private static ExpectedOutcomeEvaluation result(
      ExpectedOutcomeDefinition definition,
      boolean satisfied,
      String satisfiedReason,
      String failedReason,
      String observedHash) {
    return new ExpectedOutcomeEvaluation(
        definition.outcomeId(),
        definition.type(),
        satisfied ? ExpectedOutcomeStatus.SATISFIED : ExpectedOutcomeStatus.NOT_SATISFIED,
        satisfied ? satisfiedReason : failedReason,
        observedHash);
  }

  private static ExpectedOutcomeEvaluation indeterminate(ExpectedOutcomeDefinition definition) {
    return new ExpectedOutcomeEvaluation(
        definition.outcomeId(),
        definition.type(),
        ExpectedOutcomeStatus.INDETERMINATE,
        "EXPECTED_OUTCOME_STATE_DEPTH_LIMITED",
        null);
  }

  private static boolean isTarget(ExpectedOutcomeType type) {
    return type != ExpectedOutcomeType.FINAL_URL_EQUALS
        && type != ExpectedOutcomeType.PAGE_TITLE_EQUALS;
  }

  private static String normalizeRole(String role) {
    if (role == null || role.isBlank()) return null;
    return role.trim().toLowerCase(Locale.ROOT);
  }

  private static String normalizedUrl(String value, List<String> allowedDomains) {
    try {
      var uri = URI.create(value.trim());
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null
          || uri.getUserInfo() != null) {
        throw invalid("EXPECTED_OUTCOME_URL_INVALID");
      }
      var host = IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      var allowed =
          allowedDomains.stream()
              .map(item -> item.toLowerCase(Locale.ROOT))
              .anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
      if (!allowed) throw invalid("EXPECTED_OUTCOME_URL_DOMAIN_NOT_ALLOWED");
      return new URI(
              uri.getScheme().toLowerCase(Locale.ROOT),
              null,
              host,
              uri.getPort(),
              uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
              null,
              null)
          .toASCIIString();
    } catch (AgentApplicationService.InvalidAgentTaskException exception) {
      throw exception;
    } catch (Exception exception) {
      throw invalid("EXPECTED_OUTCOME_URL_INVALID");
    }
  }

  private static String normalizedObservedUrl(String value) {
    try {
      var uri = URI.create(value);
      return new URI(
              uri.getScheme().toLowerCase(Locale.ROOT),
              null,
              IDN.toASCII(uri.getHost(), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT),
              uri.getPort(),
              uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/" : uri.getRawPath(),
              null,
              null)
          .toASCIIString();
    } catch (Exception exception) {
      return "invalid-url";
    }
  }

  private static String normalizedText(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFKC)
        .trim()
        .replaceAll("\\s+", " ")
        .toLowerCase(Locale.ROOT);
  }

  private static String sha256(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static AgentApplicationService.InvalidAgentTaskException invalid(String code) {
    return new AgentApplicationService.InvalidAgentTaskException(code);
  }
}
