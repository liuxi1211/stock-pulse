package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 全市场股票列表视图对象（行情中心 stock-list 页）。
 * <p>
 * 由 daily_quote JOIN daily_basic + stock_basic + sw_industry_member 一次查询组装而成，
 * 共 13 列。数值字段单位保持原始存储口径，由前端做展示格式化：
 * <ul>
 *   <li>vol：成交量（手）</li>
 *   <li>amount：成交额（千元）</li>
 *   <li>totalMv：总市值（万元，前端转亿）</li>
 *   <li>peTtm / pb：可能为 null 或负数</li>
 *   <li>turnoverRate / volumeRatio：百分比/比值</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockListDTO {

    /** TS股票代码，如 000001.SZ */
    private String tsCode;

    /** 股票名称 */
    private String name;

    /** 收盘价 */
    private Double close;

    /** 涨跌幅（%） */
    private Double pctChg;

    /** 涨跌额（= close - preClose） */
    private Double change;

    /** 成交量（手） */
    private Double vol;

    /** 成交额（千元） */
    private Double amount;

    /** 总市值（万元） */
    private Double totalMv;

    /** PE TTM，可能为 null/负数 */
    private Double peTtm;

    /** PB，可能为 null/负数 */
    private Double pb;

    /** 换手率（%） */
    private Double turnoverRate;

    /** 量比 */
    private Double volumeRatio;

    /** 申万一级行业名 */
    private String industryName;

    /** 振幅（%，(high-low)/pre_close*100） */
    private Double amplitude;
}
