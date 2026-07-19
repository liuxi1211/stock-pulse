package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略版本快照 DTO。
 * <p>
 * 列表场景（{@code listVersions}）默认不返回 {@code configJson} 以减少载荷；
 * 单版本详情（{@code getVersion}）返回完整 configJson。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略版本快照")
public class StrategyVersionDTO {

    @Schema(description = "版本号")
    private Integer versionNo;

    @Schema(description = "策略状态（DRAFT/VERIFIED/ACTIVE/ARCHIVED，自主表透传）")
    private String status;

    @Schema(description = "版本配置 JSON（列表接口可不返回）")
    private String configJson;

    @Schema(description = "版本变更说明")
    private String changelog;

    @Schema(description = "创建时间（UTC ISO8601）")
    private String createdAt;
}
