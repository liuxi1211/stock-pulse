package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新策略元信息请求（不含配置；改配置走 {@link StrategyConfigUpdateRequest}）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "更新策略元信息请求")
public class StrategyUpdateRequest {

    @Size(max = 100, message = "策略名称长度不能超过 100")
    @Schema(description = "策略名称")
    private String name;

    @Size(max = 1000, message = "策略描述长度不能超过 1000")
    @Schema(description = "策略描述")
    private String description;

    @Schema(description = "策略分类（TECHNICAL/FUNDAMENTAL/MIXED/CUSTOM）")
    private String category;

    @Schema(description = "标签列表")
    private List<String> tags;
}
