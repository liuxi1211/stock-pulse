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
 * 每日基本面数据对象，对应 daily_basic 表（Tushare daily_basic：估值/换手率/市值）。
 * <p>
 * 数据库复合主键：(trade_date, ts_code)。trade_date 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 selectById/updateById/deleteById
 * 等 xxById 方法；复合主键查询请通过自定义 Mapper 方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("daily_basic")
public class DailyBasicDO {

    /** 交易日期 yyyyMMdd（复合主键首字段） */
    @TableId(type = IdType.INPUT)
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
