package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 股票排行视图对象，用于涨幅/跌幅/成交额/换手率排行展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockRankVO {

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 收盘价 */
    private BigDecimal close;

    /** 涨跌幅（%） */
    private BigDecimal pctChg;

    /** 成交额（千元） */
    private BigDecimal amount;

    /** 换手率（%）；仅换手率排行填充，其他排行可空 */
    private BigDecimal turnoverRate;
}
