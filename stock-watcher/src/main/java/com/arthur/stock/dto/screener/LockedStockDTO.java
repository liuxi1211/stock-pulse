package com.arthur.stock.dto.screener;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 锁定组合中的单只股票 DTO（spec 003 阶段 2 Task 11，FR-9）。
 * <p>
 * 用于解析 screen_lock / screen_result 的 stocks_json，结构与 engine
 * SnapshotResultVO.stocks 元素一致：{symbol, rank, score, factor_values}。
 * <br>symbol 即 tsCode（如 "000001.SZ"），可直接查 daily_quote。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "锁定组合中的单只股票")
public class LockedStockDTO {

    @Schema(description = "股票代码（tsCode，如 000001.SZ）")
    private String symbol;

    @Schema(description = "排名")
    private Integer rank;

    @Schema(description = "综合评分")
    private BigDecimal score;

    @Schema(description = "因子明细")
    private Map<String, Object> factorValues;
}
