package com.arthur.stock.vo;

import com.arthur.stock.constant.MarketEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 股票信息视图对象，用于前端展示股票详情
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StockVO {

    /** 股票代码 */
    private String code;

    /** 股票名称 */
    private String name;

    /** 所属市场 */
    private MarketEnum market;

    /** 当前价格 */
    private BigDecimal currentPrice;

    /** 涨跌额 */
    private BigDecimal changeAmount;

    /** 涨跌幅（%） */
    private BigDecimal changePercent;

    /** 开盘价 */
    private BigDecimal openPrice;

    /** 最高价 */
    private BigDecimal highPrice;

    /** 最低价 */
    private BigDecimal lowPrice;

    /** 收盘价 */
    private BigDecimal closePrice;

    /** 昨收价 */
    private BigDecimal prevClose;

    /** 成交量（手） */
    private Long volume;

    /** 成交额 */
    private String turnover;

    /** 所属行业 */
    private String industry;

    /** 数据更新时间 */
    private LocalDateTime updateTime;
}