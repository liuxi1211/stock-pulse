package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 新建策略请求（spec 004 Task 6）。
 * <p>
 * 当 {@code configJson} 非空时：会先调用 engine 校验，校验通过后 status=VERIFIED 并落 v1 版本；
 * 为空时：status=DRAFT，并落一份 buildDefaultConfig 生成的 v1 默认配置。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "新建策略请求")
public class StrategyCreateRequest {

    @NotBlank(message = "策略名称不能为空")
    @Size(max = 100, message = "策略名称长度不能超过 100")
    @Schema(description = "策略名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 1000, message = "策略描述长度不能超过 1000")
    @Schema(description = "策略描述")
    private String description;

    @Schema(description = "策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）")
    private String category;

    @Schema(description = "标签列表")
    private List<String> tags;

    @Schema(description = "初始配置 JSON（可选，留空则使用默认配置）")
    private String configJson;

    @Size(max = 500, message = "变更说明长度不能超过 500")
    @Schema(description = "初始版本变更说明")
    private String changelog;
}
