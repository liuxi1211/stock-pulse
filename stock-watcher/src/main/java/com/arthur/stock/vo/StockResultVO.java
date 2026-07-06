package com.arthur.stock.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 选股结果中的单只股票（engine snapshot 返回 stocks 元素），与 engine 字段对齐。
 */
@Data
public class StockResultVO {

    /** 股票代码，如 000001.SZ */
    private String symbol;

    /** 排名，从 1 开始 */
    private Integer rank;

    /** 综合得分 */
    private BigDecimal score;

    /** 命中的因子值（factorKey -> value） */
    private Map<String, BigDecimal> factorValues;
}
