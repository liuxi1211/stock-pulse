package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 每日基本面数据对象，对应 daily_basic 表（Tushare daily_basic：估值/换手率/市值）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("daily_basic")
public class DailyBasicDO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    private BigDecimal close;
    private BigDecimal turnoverRate;
    private BigDecimal turnoverRateF;
    private BigDecimal volumeRatio;
    private BigDecimal pe;
    private BigDecimal peTtm;
    private BigDecimal pb;
    private BigDecimal ps;
    private BigDecimal psTtm;
    private BigDecimal dvRatio;
    private BigDecimal dvTtm;
    private BigDecimal totalShare;
    private BigDecimal floatShare;
    private BigDecimal freeShare;
    private BigDecimal totalMv;
    private BigDecimal circMv;
}
