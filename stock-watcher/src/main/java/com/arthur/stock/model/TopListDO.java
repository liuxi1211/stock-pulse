package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 龙虎榜个股明细数据对象，对应 top_list 表（Tushare top_list：龙虎榜个股明细）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("top_list")
public class TopListDO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 股票名称 */
    private String name;

    /** 收盘价 */
    private BigDecimal close;

    /** 涨跌幅（%） */
    private BigDecimal pctChange;

    /** 换手率（%） */
    private BigDecimal turnoverRate;

    /** 成交额（万元） */
    private BigDecimal amount;

    /** 龙虎榜买入额（万元） */
    private BigDecimal lBuy;

    /** 龙虎榜卖出额（万元） */
    private BigDecimal lSell;

    /** 龙虎榜买入净额（万元） */
    private BigDecimal lBuyAmount;

    /** 龙虎榜卖出净额（万元） */
    private BigDecimal lSellAmount;

    /** 净额（万元） */
    private BigDecimal netAmount;

    /** 机构买入额（万元） */
    private BigDecimal bAmount;

    /** 机构卖出额（万元） */
    private BigDecimal sAmount;

    /** 上榜原因 */
    private String reason;
}
