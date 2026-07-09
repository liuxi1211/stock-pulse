package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新策略配置请求（生成新版本）。
 * <p>
 * {@code expectedVersion} 为乐观锁：必须等于主表 current_version 才允许写入新版本。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "更新策略配置请求")
public class StrategyConfigUpdateRequest {

    @NotBlank(message = "配置 JSON 不能为空")
    @Size(max = 1048576, message = "配置 JSON 不能超过 1MB")
    @Schema(description = "配置 JSON（统一策略 Schema）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String configJson;

    @Size(max = 500, message = "变更说明长度不能超过 500")
    @Schema(description = "版本变更说明")
    private String changelog;

    @Schema(description = "期望的当前版本号（乐观锁，等于主表 current_version 才允许写入）")
    private Integer expectedVersion;
}
