package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 龙虎榜个股明细数据对象，对应 top_list 表（Tushare top_list：龙虎榜每日明细）。
 * <p>
 * 注意：表无主键。Tushare 可能返回 trade_date+ts_code+name+reason 相同但金额不同的多条记录。
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

    /** 总成交额（元） */
    private BigDecimal amount;

    /** 龙虎榜卖出额（元） */
    private BigDecimal lSell;

    /** 龙虎榜买入额（元） */
    private BigDecimal lBuy;

    /** 龙虎榜成交额（元） */
    private BigDecimal lAmount;

    /** 龙虎榜净买入额（元） */
    private BigDecimal netAmount;

    /** 龙虎榜净买额占比（%） */
    private BigDecimal netRate;

    /** 龙虎榜成交额占比（%） */
    private BigDecimal amountRate;

    /** 当日流通市值（元） */
    private BigDecimal floatValues;

    /** 上榜理由 */
    private String reason;
}
