package com.arthur.stock.dto.factor;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

/**
 * 单个因子的计算规格（透传 engine 的 FactorComputeSpec）。
 */
@Data
@Schema(description = "单个因子的计算规格")
public class FactorComputeSpecDTO {

    @Schema(description = "因子唯一标识，如 MA", requiredMode = Schema.RequiredMode.REQUIRED,
            example = "MA")
    private String factorKey;

    @Schema(description = "参数键值，覆盖因子默认值；为空用默认参数",
            example = "{\"timeperiod\": 5}")
    private Map<String, Object> params;

    @Schema(description = "多输出因子取第几路（为空用 defaultOutputIndex）", example = "0")
    private Integer outputIndex;
}
