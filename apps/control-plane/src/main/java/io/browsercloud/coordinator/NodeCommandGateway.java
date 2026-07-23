package io.browsercloud.coordinator;

/**
 * Node 命令网关接口。
 *
 * <p>负责向 Browser Node 发送命令。
 */
public interface NodeCommandGateway {

  /**
   * 发送命令到 Browser Node。
   *
   * @param command Node 命令
   */
  void send(NodeCommand command);
}
