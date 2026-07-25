package io.browsercloud.application;

import java.util.regex.Pattern;

/** Agent Context / debug evidence 的最小化处理；原始外部正文不得落库。 */
final class AgentDataMinimizer {

  private static final Pattern EMAIL =
      Pattern.compile("(?i)([a-z0-9._%+-])[a-z0-9._%+-]*@([a-z0-9-])[a-z0-9.-]*(\\.[a-z]{2,})");
  private static final Pattern PHONE =
      Pattern.compile("(?<!\\d)(?:\\+?\\d[\\s().-]*){7,15}(?!\\d)");
  private static final Pattern SECRET_ASSIGNMENT =
      Pattern.compile(
          "(?i)\\b(password|passwd|pwd|cookie|otp|one[-_ ]?time[-_ ]?code|authorization|bearer)\\b\\s*[:=]\\s*\\S+");

  private AgentDataMinimizer() {}

  static String redact(String value) {
    if (value == null) {
      return "";
    }
    var secrets = SECRET_ASSIGNMENT.matcher(value).replaceAll("$1=[REDACTED]");
    var emails = EMAIL.matcher(secrets).replaceAll("$1***@$2***$3");
    return PHONE.matcher(emails).replaceAll("[PHONE_REDACTED]");
  }
}
