package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 每日基本面数据视图对象，用于前端展示。
 * <p>
 * 字段单位保持原始存储口径，由前端做展示格式化：
 * <ul>
 *   <li>close：收盘价（元）</li>
 *   <li>totalMv：总市值（万元，前端转亿）</li>
 *   <li>peTtm / pb：可能为 null 或负数</li>
 *   <li>turnoverRate：换手率（%）</li>
 *   <li>volumeRatio：量比</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DailyBasicVO {

    /** 股票代码，如 000001.SZ */
    private String tsCode;

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 收盘价 */
    private BigDecimal close;

    /** 总市值（万元） */
    private BigDecimal totalMv;

    /** PE TTM，可能为 null/负数 */
    private BigDecimal peTtm;

    /** PB，可能为 null/负数 */
    private BigDecimal pb;

    /** 换手率（%） */
    private BigDecimal turnoverRate;

    /** 量比 */
    private BigDecimal volumeRatio;
}
