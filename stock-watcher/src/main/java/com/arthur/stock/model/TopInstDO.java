package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 龙虎榜营业部席位明细数据对象，对应 top_inst 表（Tushare top_inst：龙虎榜营业部席位明细）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("top_inst")
public class TopInstDO {

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 营业部名称 */
    private String exalter;

    /** 买卖方向（Buy/Sell） */
    private String side;

    /** 买入额（万元） */
    private BigDecimal buy;

    /** 买入占比（%） */
    private BigDecimal buyRate;

    /** 卖出额（万元） */
    private BigDecimal sell;

    /** 卖出占比（%） */
    private BigDecimal sellRate;

    /** 净买入额（万元） */
    private BigDecimal netBuy;
}
