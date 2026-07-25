package io.browsercloud.api;

import io.browsercloud.domain.session.ResourceClass;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 创建 Session 请求。
 *
 * @param tenantId 租户 ID
 * @param profileId Profile ID
 * @param region 部署区域
 * @param resourceClass 资源等级
 * @param metadata 扩展元数据
 */
public record CreateSessionRequest(
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String tenantId,
    @NotBlank @Pattern(regexp = "^[a-zA-Z0-9_-]{1,128}$") String profileId,
    @Pattern(regexp = "^[a-z0-9-]{1,32}$") String region,
    ResourceClass resourceClass,
    @Size(max = 32)
        Map<@NotBlank @Size(max = 128) String, @NotNull @Size(max = 1024) String> metadata) {}
