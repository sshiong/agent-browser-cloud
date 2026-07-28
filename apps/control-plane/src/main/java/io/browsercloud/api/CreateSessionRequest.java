package io.browsercloud.api;

import io.browsercloud.domain.session.ResourceClass;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 创建 Session 请求。
 *
 * @param tenantId 租户 ID
 * @param profileId Profile ID
 * @param runtimeBuildId 可选的已验证 Runtime；省略时绑定 Workspace 默认值
 * @param applicationId 可选的 Tenant Application Recovery Contract ID
 * @param groupId 可选的 Workspace Group；未显式提交资源策略时继承其默认策略
 * @param tagIds 可选的租户 Workspace Tag 集合
 * @param region 部署区域
 * @param resourcePolicy 用户可见的自动资源策略
 * @param resourceClass 仅供旧版 SDK 兼容的内部资源等级；新客户端不得提交
 * @param requestedTabs 预期最大 Tab 数；省略时为 1
 * @param agentActionsPerMinute Agent 动作速率预算
 * @param remoteDesktop 是否要求常驻 Remote Desktop
 * @param humanTakeoverEnabled 是否允许该 Session 进入 HumanTakeover；省略时绑定 Workspace 默认值
 * @param web3Workload 是否为 Web3 工作负载
 * @param mediaWorkload 是否申请独立 Media/Encoder 资源
 * @param requestedMediaStreams 同时编码流数量
 * @param mediaBitrateKbps 聚合码率预算
 * @param extensionIds Extension ID 集合；未知 ID 自动进入 Probation
 * @param metadata 扩展元数据
 */
public record CreateSessionRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String tenantId,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
    @Pattern(regexp = "^[A-Za-z0-9_-]{1,128}$") String runtimeBuildId,
    @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String applicationId,
    @Pattern(regexp = "^grp_[a-zA-Z0-9]{16,32}$") String groupId,
    @Size(max = 16) List<@NotBlank @Pattern(regexp = "^tag_[a-zA-Z0-9]{16,32}$") String> tagIds,
    @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
    @Valid ResourcePolicyRequest resourcePolicy,
    ResourceClass resourceClass,
    @Min(0) @Max(64) int requestedTabs,
    @Min(0) @Max(600) int agentActionsPerMinute,
    boolean remoteDesktop,
    Boolean humanTakeoverEnabled,
    boolean web3Workload,
    boolean mediaWorkload,
    @Min(0) @Max(32) int requestedMediaStreams,
    @Min(0) @Max(1_000_000) int mediaBitrateKbps,
    @Size(max = 32)
        List<@NotBlank @Pattern(regexp = "^[a-zA-Z0-9_.-]{1,128}$") String> extensionIds,
    @Size(max = 32)
        Map<@NotBlank @Size(max = 128) String, @NotNull @Size(max = 1024) String> metadata) {}
