package com.arthur.stock.dto.screener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 更新选股方案请求，字段全部可选（仅更新非空字段）。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "更新选股方案请求")
public class ScreenPlanUpdateRequestDTO {

    @Size(max = 100, message = "方案名长度不能超过 100")
    @Schema(description = "方案名")
    private String name;

    @Size(max = 1000, message = "方案描述长度不能超过 1000")
    @Schema(description = "方案描述")
    private String description;

    @Schema(description = "方案配置（universe/conditions/ranking/filters/topN 等任意 JSON）")
    private Object screenConfig;
}
