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
 * 融资融券个股明细数据对象，对应 margin_detail 表（Tushare margin_detail：融资融券个股明细）。
 * <p>
 * 数据库复合主键：(trade_date, ts_code)。trade_date 上标注 @TableId 仅用于满足
 * MyBatis-Plus 单主键元数据要求，严禁调用 xxById 系列方法。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("margin_detail")
public class MarginDetailDO {

    /** 交易日期 yyyyMMdd（复合主键首字段） */
    @TableId(type = IdType.INPUT)
    private String tradeDate;

    /** 股票代码 */
    private String tsCode;

    /** 股票名称 */
    private String name;

    /** 融资余额（万元） */
    private BigDecimal rzye;

    /** 融券余额（万元） */
    private BigDecimal rqye;

    /** 融资买入额（万元） */
    private BigDecimal rzmre;

    /** 融资偿还额（万元） */
    private BigDecimal rzche;

    /** 融券卖出量（万股） */
    private BigDecimal rqmcl;

    /** 融资融券余额（万元） */
    private BigDecimal rzrqye;
}
