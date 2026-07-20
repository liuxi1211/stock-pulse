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
 * 资产负债表数据对象，对应 balancesheet 表（Tushare balancesheet）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("balancesheet")
public class BalancesheetDO {

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

    // ==================== 流动资产 ====================
    private BigDecimal monetaryFunds;
    private BigDecimal accountsRece;
    private BigDecimal notesRece;
    private BigDecimal accountsReceFin;
    private BigDecimal otherRece;
    private BigDecimal prepayment;
    private BigDecimal dividendsRece;
    private BigDecimal intRece;
    private BigDecimal inventories;
    private BigDecimal nonCurrentAssetsIn1Yr;
    private BigDecimal otherCurrentAssets;
    private BigDecimal totalCurrentAssets;

    // ==================== 非流动资产 ====================
    private BigDecimal equityJointCap;
    private BigDecimal ltReceivable;
    private BigDecimal eqtInvest;
    private BigDecimal invRealEstate;
    private BigDecimal fixAssetsNca;
    private BigDecimal cip;
    private BigDecimal constructionMaterials;
    private BigDecimal intangAssets;
    private BigDecimal goodwill;
    private BigDecimal ltAmortDeferredExp;
    private BigDecimal deferTaxAssets;
    private BigDecimal otherNonCurrentAssets;
    private BigDecimal totalNonCurrentAssets;

    // ==================== 总资产 ====================
    private BigDecimal totalAssets;

    // ==================== 流动负债 ====================
    private BigDecimal ltBorr;
    private BigDecimal notesPayable;
    private BigDecimal accountsPayable;
    private BigDecimal accountsPayableFin;
    private BigDecimal prepaymentReceivables;
    private BigDecimal wagePayable;
    private BigDecimal taxesSurcharges;
    private BigDecimal otherPayable;
    private BigDecimal nonCurrentLiabIn1Yr;
    private BigDecimal otherCurrentLiab;
    private BigDecimal totalCurrentLiab;

    // ==================== 非流动负债 ====================
    private BigDecimal longTermBorr;
    private BigDecimal ppayableBonds;
    private BigDecimal longTermPayable;
    private BigDecimal specificPayable;
    private BigDecimal estimatedLiab;
    private BigDecimal deferTaxLiab;
    private BigDecimal deferIncNonCurrLiab;
    private BigDecimal otherNonCurrentLiab;
    private BigDecimal totalNonCurrentLiab;

    // ==================== 总负债 ====================
    private BigDecimal totalLiab;

    // ==================== 所有者权益 ====================
    private BigDecimal shareCapital;
    private BigDecimal capitalReserve;
    private BigDecimal treasuryStock;
    private BigDecimal specificReserves;
    private BigDecimal surplusReserve;
    private BigDecimal generalRiskReserve;
    private BigDecimal undistributedProfit;
    private BigDecimal equityParentCompany;
    private BigDecimal minorityInterest;
    private BigDecimal totalEquity;
    private BigDecimal totalLiabEquity;

    // ==================== 调整项 ====================
    private BigDecimal accountsReceDecr;
    private BigDecimal accountsReceFinDecr;
    private BigDecimal minorityInterestInc;
    private BigDecimal minorityInterestDec;
    private String updateFlag;
}
