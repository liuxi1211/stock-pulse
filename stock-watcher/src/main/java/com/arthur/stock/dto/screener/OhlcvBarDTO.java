package com.arthur.stock.dto.screener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 单根 OHLCV K 线（选股候选股历史），透传 engine snapshot 的 ohlcv_history 元素。
 * <p>
 * 字段名小写 open/high/low/close/volume 与 engine 期望完全一致，无需 @JsonProperty。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "选股候选股 OHLCV 历史 K 线")
public class OhlcvBarDTO {

    @Schema(description = "交易日期，格式 YYYYMMDD（与 daily_quote.trade_date 一致）", requiredMode = Schema.RequiredMode.REQUIRED)
    private String date;

    @Schema(description = "开盘价", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal open;

    @Schema(description = "最高价", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal high;

    @Schema(description = "最低价", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal low;

    @Schema(description = "收盘价", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal close;

    @Schema(description = "成交量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal volume;
}
