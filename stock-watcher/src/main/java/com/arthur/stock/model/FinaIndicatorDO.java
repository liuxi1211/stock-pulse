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
 * 财务指标数据对象，对应 fina_indicator 表（Tushare fina_indicator）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("fina_indicator")
public class FinaIndicatorDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;

    /** 报告期 yyyyMMdd */
    private String endDate;

    /** 公告日期 yyyyMMdd */
    private String annDate;

    private BigDecimal roe;
    private BigDecimal roa;
    private BigDecimal grossprofitMargin;
    private BigDecimal netprofitMargin;
    private BigDecimal dtNetprofitYoy;
    private BigDecimal revenueYoy;
    private BigDecimal debtToAssets;
    private BigDecimal epsYoy;
}
