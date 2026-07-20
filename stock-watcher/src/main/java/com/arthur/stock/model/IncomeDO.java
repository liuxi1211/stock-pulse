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
 * 利润表数据对象，对应 income 表（Tushare income）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("income")
public class IncomeDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String tsCode;

    /** 公告日期 yyyyMMdd */
    private String annDate;

    /** 实际公告日期 yyyyMMdd */
    private String fAnnDate;

    /** 报告期 yyyyMMdd */
    private String endDate;

    /** 报告类型：1=合并 / 2=单季合并 / 3=调整单季 / 4=调整合并 / 5=调整前 / 6=调整后 */
    private String reportType;

    /** 公司类型：1=一般工商 / 2=证券 / 3=保险 / 4=银行 */
    private String compType;

    private BigDecimal basicEps;
    private BigDecimal dilutedEps;
    private BigDecimal totalRevenue;
    private BigDecimal revenue;
    private BigDecimal totalCogs;
    private BigDecimal operateCost;
    private BigDecimal operateProfit;
    private BigDecimal nonOperIncome;
    private BigDecimal nonOperExp;
    private BigDecimal totalProfit;
    private BigDecimal nIncome;
    private BigDecimal nIncomeAttrP;
    private BigDecimal minorityInterest;
    private BigDecimal adjustProfit;
    private BigDecimal incomeTax;
    private BigDecimal nIncomeYoy;
    private BigDecimal dtProfitYoy;
    private BigDecimal sellExp;
    private BigDecimal adminExp;
    private BigDecimal financialExp;
    private BigDecimal rdExp;
    private BigDecimal impairEndInvest;
    private BigDecimal impairEndOper;
    private BigDecimal investIncome;
    private BigDecimal investIncomeInc;
    private BigDecimal investIncomeDec;
    private BigDecimal fairvalueChangeIncome;
    private BigDecimal exchangeGain;
    private BigDecimal assetDisposeIncome;
    private BigDecimal otherIncome;
    private BigDecimal operateNIncome;
    private BigDecimal creditImpairLoss;
    private BigDecimal assetImpairLoss;
    private BigDecimal bbit;
    private BigDecimal bbitYoy;
    private BigDecimal operateProfitIncomeYoy;
    private String updateFlag;
}
