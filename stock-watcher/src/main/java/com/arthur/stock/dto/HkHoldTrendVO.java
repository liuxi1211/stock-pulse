package com.arthur.stock.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 沪深港通持股占比趋势聚合 VO（按 trade_date + exchange_id 汇总 SUM(ratio)）。
 */
@Data
public class HkHoldTrendVO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 持股占比合计（%） */
    private BigDecimal ratio;

    /** 交易所代码（SH/SZ） */
    private String exchangeId;
}
