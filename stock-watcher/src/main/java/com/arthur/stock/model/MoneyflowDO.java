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
 * 个股资金流向数据对象，对应 stock_moneyflow 表（Tushare moneyflow：个股资金流向）。
 * <p>
 * 数据库复合主键：(ts_code, trade_date)。ts_code 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("stock_moneyflow")
public class MoneyflowDO {

    /** 股票代码（复合主键首字段） */
    @TableId(type = IdType.INPUT)
    private String tsCode;

    /** 交易日期 yyyyMMdd */
    private String tradeDate;

    /** 小单买入金额（万元） */
    private BigDecimal buySmAmount;

    /** 小单卖出金额（万元） */
    private BigDecimal sellSmAmount;

    /** 小单买入量（万手） */
    private BigDecimal buySmVol;

    /** 小单卖出量（万手） */
    private BigDecimal sellSmVol;

    /** 中单买入金额（万元） */
    private BigDecimal buyMdAmount;

    /** 中单卖出金额（万元） */
    private BigDecimal sellMdAmount;

    /** 中单买入量（万手） */
    private BigDecimal buyMdVol;

    /** 中单卖出量（万手） */
    private BigDecimal sellMdVol;

    /** 大单买入金额（万元） */
    private BigDecimal buyLgAmount;

    /** 大单卖出金额（万元） */
    private BigDecimal sellLgAmount;

    /** 大单买入量（万手） */
    private BigDecimal buyLgVol;

    /** 大单卖出量（万手） */
    private BigDecimal sellLgVol;

    /** 特大单买入金额（万元） */
    private BigDecimal buyElgAmount;

    /** 特大单卖出金额（万元） */
    private BigDecimal sellElgAmount;

    /** 特大单买入量（万手） */
    private BigDecimal buyElgVol;

    /** 特大单卖出量（万手） */
    private BigDecimal sellElgVol;

    /** 净流入额（万元） */
    private BigDecimal netMfAmount;

    /** 净流入量（万手） */
    private BigDecimal netMfVol;
}
