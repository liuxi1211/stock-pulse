package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 日线行情数据对象，对应 daily_quote 表
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("daily_quote")
public class DailyQuoteDO {

    /** TS股票代码，如 000001.SZ */
    private String tsCode;

    /** 交易日期，格式 yyyyMMdd */
    private String tradeDate;

    /** 开盘价 */
    private BigDecimal open;

    /** 最高价 */
    private BigDecimal high;

    /** 最低价 */
    private BigDecimal low;

    /** 收盘价 */
    private BigDecimal close;

    /** 昨收价 */
    private BigDecimal preClose;

    /** 涨跌额 */
    private BigDecimal changeAmt;

    /** 涨跌幅（%） */
    private BigDecimal pctChg;

    /** 成交量（手） */
    private BigDecimal vol;

    /** 成交额（千元） */
    private BigDecimal amount;
}