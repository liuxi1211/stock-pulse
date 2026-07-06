package com.arthur.stock.dto.screener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新建选股方案请求（spec FR-10）。
 * <p>
 * {@code screenConfig} 为任意 JSON 树（含 universe/conditions/ranking/filters/topN 等），原样存取。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "新建选股方案请求")
public class ScreenPlanCreateRequestDTO {

    @NotBlank(message = "方案名不能为空")
    @Size(max = 100, message = "方案名长度不能超过 100")
    @Schema(description = "方案名", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Size(max = 1000, message = "方案描述长度不能超过 1000")
    @Schema(description = "方案描述")
    private String description;

    @NotNull(message = "方案配置不能为空")
    @Schema(description = "方案配置（universe/conditions/ranking/filters 等任意 JSON）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Object screenConfig;
}
