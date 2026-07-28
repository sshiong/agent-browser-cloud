package io.browsercloud.domain.agent;

import static io.browsercloud.domain.agent.AgentModels.ToolId;

import java.util.EnumSet;
import java.util.Set;

/** Immutable Session-scoped Agent capability and budget policy. */
public enum AgentPolicy {
  DISABLED(1, 1, 0, 0, EnumSet.noneOf(ToolId.class)),
  RESTRICTED(
      5,
      6,
      0,
      0,
      EnumSet.of(
          ToolId.GET_CURRENT_STATE,
          ToolId.GET_URL,
          ToolId.GET_PAGE_SUMMARY,
          ToolId.WAIT_FOR,
          ToolId.REQUEST_HUMAN_TAKEOVER)),
  BALANCED(8, 12, 1, 1, EnumSet.allOf(ToolId.class)),
  INTERACTIVE(12, 20, 2, 3, EnumSet.allOf(ToolId.class));

  private final int defaultMaxActions;
  private final int maximumMaxActions;
  private final int defaultReplanBudget;
  private final int maximumReplanBudget;
  private final Set<ToolId> allowedTools;

  AgentPolicy(
      int defaultMaxActions,
      int maximumMaxActions,
      int defaultReplanBudget,
      int maximumReplanBudget,
      Set<ToolId> allowedTools) {
    this.defaultMaxActions = defaultMaxActions;
    this.maximumMaxActions = maximumMaxActions;
    this.defaultReplanBudget = defaultReplanBudget;
    this.maximumReplanBudget = maximumReplanBudget;
    this.allowedTools = Set.copyOf(allowedTools);
  }

  public int defaultMaxActions() {
    return defaultMaxActions;
  }

  public int maximumMaxActions() {
    return maximumMaxActions;
  }

  public int defaultReplanBudget() {
    return defaultReplanBudget;
  }

  public int maximumReplanBudget() {
    return maximumReplanBudget;
  }

  public boolean allows(ToolId toolId) {
    return allowedTools.contains(toolId);
  }
}
