package io.browsercloud.coordinator;

/**
 * Coordinator 处理结果。
 *
 * @param status 状态
 * @param reason 原因（仅 REJECTED 时有值）
 * @param operationId 操作 ID（仅 ACCEPTED 时有值）
 */
public record CoordinatorResult(Status status, String reason, String operationId) {
  public enum Status {
    /** 已接受 */
    ACCEPTED,

    /** 已完成 */
    COMPLETED,

    /** 已拒绝 */
    REJECTED
  }

  /**
   * 创建已接受的结果。
   *
   * @param operationId 操作 ID
   * @return 已接受的结果
   */
  public static CoordinatorResult accepted(String operationId) {
    return new CoordinatorResult(Status.ACCEPTED, null, operationId);
  }

  /**
   * 创建已完成的结果。
   *
   * @return 已完成的结果
   */
  public static CoordinatorResult completed() {
    return new CoordinatorResult(Status.COMPLETED, null, null);
  }

  /**
   * 创建已拒绝的结果。
   *
   * @param reason 拒绝原因
   * @return 已拒绝的结果
   */
  public static CoordinatorResult rejected(String reason) {
    return new CoordinatorResult(Status.REJECTED, reason, null);
  }
}
