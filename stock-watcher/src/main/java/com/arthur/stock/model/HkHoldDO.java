package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 沪深港通持股明细数据对象，对应 hk_hold 表（Tushare hk_hold：沪深港通持股明细）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("hk_hold")
public class HkHoldDO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 持股代码 */
    private String code;

    /** 持股名称 */
    private String name;

    /** 持股数量（万股） */
    private BigDecimal vol;

    /** 持股占比（%） */
    private BigDecimal ratio;

    /** 股票代码 */
    private String tsCode;

    /** 交易所代码（SH/SZ） */
    private String exchangeId;
}
