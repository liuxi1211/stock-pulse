package com.arthur.stock.dto.internal;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 内部接口：point-in-time 成分股查询请求（spec 010 缺陷 A 修复）。
 * <p>
 * 由 stock-engine 在回测/选股时调用，按查询日取 ≤ 该日的最新指数成分股快照，
 * 用于消除幸存者偏差。
 */
@Data
@Schema(description = "PIT 成分股查询请求")
public class ConstituentsQueryRequestDTO {

    @Schema(description = "指数代码，如 000300.SH / 000905.SH", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "index_code 不能为空")
    private String index_code;

    @Schema(description = "查询日 yyyyMMdd，取 ≤ 该日的最新快照", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "trade_date 不能为空")
    private String trade_date;
}
