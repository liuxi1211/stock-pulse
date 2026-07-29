package com.arthur.stock.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 龙虎榜营业部席位明细数据对象，对应 top_inst 表（Tushare top_inst：龙虎榜营业部席位明细）。
 * <p>
 * 数据库复合主键：(trade_date, ts_code, exalter, side)。trade_date 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("top_inst")
public class TopInstDO {

    /** 交易日期 yyyyMMdd（复合主键首字段） */
    @TableId(type = IdType.INPUT)
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 营业部名称 */
    private String exalter;

    /** 买卖方向（0：买入前5名，1：卖出前5名） */
    private String side;

    /** 买入额（元） */
    private BigDecimal buy;

    /** 买入占总成交比例（%） */
    private BigDecimal buyRate;

    /** 卖出额（元） */
    private BigDecimal sell;

    /** 卖出占总成交比例（%） */
    private BigDecimal sellRate;

    /** 净成交额（元） */
    private BigDecimal netBuy;
}
