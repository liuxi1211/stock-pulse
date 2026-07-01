package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * K线数据视图对象，用于前端TradingView图表展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KlineDataVO {

    /** 交易日期 */
    private String date;

    /** 开盘价 */
    private BigDecimal open;

    /** 收盘价 */
    private BigDecimal close;

    /** 最低价 */
    private BigDecimal low;

    /** 最高价 */
    private BigDecimal high;

    /** 成交量（手） */
    private Long volume;
}