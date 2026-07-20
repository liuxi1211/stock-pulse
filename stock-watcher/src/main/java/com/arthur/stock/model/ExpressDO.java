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
 * 业绩快报数据对象，对应 express 表（Tushare express，doc_id=46）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("express")
public class ExpressDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;

    /** 公告日期 yyyyMMdd */
    private String annDate;

    /** 报告期 yyyyMMdd */
    private String endDate;

    /** 营业收入 */
    private BigDecimal revenue;

    /** 营业利润 */
    private BigDecimal operateProfit;

    /** 利润总额 */
    private BigDecimal totalProfit;

    /** 净利润 */
    private BigDecimal nIncome;

    /** 总资产 */
    private BigDecimal totalAssets;

    /** 股东权益合计-不含少数股东权益 */
    private BigDecimal totalHldrEqyExcMinInt;

    /** 每股收益（摊薄） */
    private BigDecimal basicEps;

    /** 每股收益（摊薄）（稀释） */
    private BigDecimal dilutedEps;

    /** 净利润增长率（%） */
    private BigDecimal growthYield;

    /** 营业收入增长率（%） */
    private BigDecimal orGrowthYield;

    /** 上年三季度净利润 */
    private BigDecimal ystNetProfit;

    /** 上年全年净利润 */
    private BigDecimal bmNetProfit;

    /** 上年全年营业收入增长率 */
    private BigDecimal bmGrowthSales;

    /** 更新标识 */
    private String updateFlag;
}
