package com.arthur.stock.dto.screener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 执行选股方案时的可选覆盖参数。
 * <p>
 * 字段优先级：本对象 &gt; plan.screenConfig 中的默认值。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "执行选股方案的可选覆盖参数")
public class ScreenPlanRunRequestDTO {

    @Schema(description = "选股日，格式 YYYY-MM-DD（可选，默认今天）")
    private String date;

    @Schema(description = "候选池标识（可选，覆盖 plan 中的 universe）")
    private String universe;

    @Schema(description = "覆盖 conditions/ranking/filters/topN 等（任意 JSON）")
    private Object overrides;
}
