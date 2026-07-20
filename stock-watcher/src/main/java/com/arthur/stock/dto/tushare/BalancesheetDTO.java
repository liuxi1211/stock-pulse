package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare balancesheet 接口返回的资产负债表数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=36">Tushare balancesheet 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BalancesheetDTO {

    @JSONField(name = "ts_code")
    private String tsCode;

    @JSONField(name = "ann_date")
    private String annDate;

    @JSONField(name = "f_ann_date")
    private String fAnnDate;

    @JSONField(name = "end_date")
    private String endDate;

    @JSONField(name = "report_type")
    private String reportType;

    @JSONField(name = "comp_type")
    private String compType;

    // ==================== 流动资产 ====================

    @JSONField(name = "monetary_funds")
    private BigDecimal monetaryFunds;

    @JSONField(name = "accounts_rece")
    private BigDecimal accountsRece;

    @JSONField(name = "notes_rece")
    private BigDecimal notesRece;

    @JSONField(name = "accounts_rece_fin")
    private BigDecimal accountsReceFin;

    @JSONField(name = "other_rece")
    private BigDecimal otherRece;

    private BigDecimal prepayment;

    @JSONField(name = "dividends_rece")
    private BigDecimal dividendsRece;

    @JSONField(name = "int_rece")
    private BigDecimal intRece;

    private BigDecimal inventories;

    @JSONField(name = "non_current_assets_in_1_yr")
    private BigDecimal nonCurrentAssetsIn1Yr;

    @JSONField(name = "other_current_assets")
    private BigDecimal otherCurrentAssets;

    @JSONField(name = "total_current_assets")
    private BigDecimal totalCurrentAssets;

    // ==================== 非流动资产 ====================

    @JSONField(name = "equity_joint_cap")
    private BigDecimal equityJointCap;

    @JSONField(name = "lt_receivable")
    private BigDecimal ltReceivable;

    @JSONField(name = "eqt_invest")
    private BigDecimal eqtInvest;

    @JSONField(name = "inv_real_estate")
    private BigDecimal invRealEstate;

    @JSONField(name = "fix_assets_nca")
    private BigDecimal fixAssetsNca;

    private BigDecimal cip;

    @JSONField(name = "construction_materials")
    private BigDecimal constructionMaterials;

    @JSONField(name = "intang_assets")
    private BigDecimal intangAssets;

    private BigDecimal goodwill;

    @JSONField(name = "lt_amort_deferred_exp")
    private BigDecimal ltAmortDeferredExp;

    @JSONField(name = "defer_tax_assets")
    private BigDecimal deferTaxAssets;

    @JSONField(name = "other_non_current_assets")
    private BigDecimal otherNonCurrentAssets;

    @JSONField(name = "total_non_current_assets")
    private BigDecimal totalNonCurrentAssets;

    // ==================== 总资产 ====================

    @JSONField(name = "total_assets")
    private BigDecimal totalAssets;

    // ==================== 流动负债 ====================

    @JSONField(name = "lt_borr")
    private BigDecimal ltBorr;

    @JSONField(name = "notes_payable")
    private BigDecimal notesPayable;

    @JSONField(name = "accounts_payable")
    private BigDecimal accountsPayable;

    @JSONField(name = "accounts_payable_fin")
    private BigDecimal accountsPayableFin;

    @JSONField(name = "prepayment_receivables")
    private BigDecimal prepaymentReceivables;

    @JSONField(name = "wage_payable")
    private BigDecimal wagePayable;

    @JSONField(name = "taxes_surcharges")
    private BigDecimal taxesSurcharges;

    @JSONField(name = "other_payable")
    private BigDecimal otherPayable;

    @JSONField(name = "non_current_liab_in_1_yr")
    private BigDecimal nonCurrentLiabIn1Yr;

    @JSONField(name = "other_current_liab")
    private BigDecimal otherCurrentLiab;

    @JSONField(name = "total_current_liab")
    private BigDecimal totalCurrentLiab;

    // ==================== 非流动负债 ====================

    @JSONField(name = "long_term_borr")
    private BigDecimal longTermBorr;

    @JSONField(name = "ppayable_bonds")
    private BigDecimal ppayableBonds;

    @JSONField(name = "long_term_payable")
    private BigDecimal longTermPayable;

    @JSONField(name = "specific_payable")
    private BigDecimal specificPayable;

    @JSONField(name = "estimated_liab")
    private BigDecimal estimatedLiab;

    @JSONField(name = "defer_tax_liab")
    private BigDecimal deferTaxLiab;

    @JSONField(name = "defer_inc_non_curr_liab")
    private BigDecimal deferIncNonCurrLiab;

    @JSONField(name = "other_non_current_liab")
    private BigDecimal otherNonCurrentLiab;

    @JSONField(name = "total_non_current_liab")
    private BigDecimal totalNonCurrentLiab;

    // ==================== 总负债 ====================

    @JSONField(name = "total_liab")
    private BigDecimal totalLiab;

    // ==================== 所有者权益 ====================

    @JSONField(name = "share_capital")
    private BigDecimal shareCapital;

    @JSONField(name = "capital_reserve")
    private BigDecimal capitalReserve;

    @JSONField(name = "treasury_stock")
    private BigDecimal treasuryStock;

    @JSONField(name = "specific_reserves")
    private BigDecimal specificReserves;

    @JSONField(name = "surplus_reserve")
    private BigDecimal surplusReserve;

    @JSONField(name = "general_risk_reserve")
    private BigDecimal generalRiskReserve;

    @JSONField(name = "undistributed_profit")
    private BigDecimal undistributedProfit;

    @JSONField(name = "equity_parent_company")
    private BigDecimal equityParentCompany;

    @JSONField(name = "minority_interest")
    private BigDecimal minorityInterest;

    @JSONField(name = "total_equity")
    private BigDecimal totalEquity;

    @JSONField(name = "total_liab_equity")
    private BigDecimal totalLiabEquity;

    // ==================== 调整项 ====================

    @JSONField(name = "accounts_rece_decr")
    private BigDecimal accountsReceDecr;

    @JSONField(name = "accounts_rece_fin_decr")
    private BigDecimal accountsReceFinDecr;

    @JSONField(name = "minority_interest_inc")
    private BigDecimal minorityInterestInc;

    @JSONField(name = "minority_interest_dec")
    private BigDecimal minorityInterestDec;

    @JSONField(name = "update_flag")
    private String updateFlag;
}
