package com.arthur.stock.dto.strategy;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 策略模板 DTO，用于前端「新建策略」时的模板选项展示。
 * <p>
 * 注意：模板本身不落库（spec FR-1），由配置或硬编码提供；此 DTO 仅作展示载体。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "策略模板")
public class StrategyTemplateDTO {

    @Schema(description = "模板ID")
    private String id;

    @Schema(description = "模板名称")
    private String name;

    @Schema(description = "模板描述")
    private String description;

    @Schema(description = "策略分类")
    private String category;

    @Schema(description = "适用范围（single/portfolio）")
    private String scope;

    @Schema(description = "模板配置 JSON")
    private String configJson;
}
