package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare cashflow 接口返回的现金流量表数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=44">Tushare cashflow 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CashflowDTO {

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

    // ==================== 现金流量净额 ====================

    @JSONField(name = "n_cashflow_act")
    private BigDecimal nCashflowAct;

    @JSONField(name = "n_cashflow_inv_act")
    private BigDecimal nCashflowInvAct;

    @JSONField(name = "n_cash_flows_fnc_act")
    private BigDecimal nCashFlowsFncAct;

    @JSONField(name = "free_cashflow")
    private BigDecimal freeCashflow;

    // ==================== 经营活动 ====================

    @JSONField(name = "c_fr_sale_sg")
    private BigDecimal cFrSaleSg;

    @JSONField(name = "c_fr_oth_sg")
    private BigDecimal cFrOthSg;

    @JSONField(name = "c_paid_goods_s")
    private BigDecimal cPaidGoodsS;

    @JSONField(name = "c_paid_to_for_empl")
    private BigDecimal cPaidToForEmpl;

    @JSONField(name = "c_paid_for_taxes")
    private BigDecimal cPaidForTaxes;

    @JSONField(name = "c_paid_oth_op_f")
    private BigDecimal cPaidOthOpF;

    // ==================== 投资活动 ====================

    @JSONField(name = "c_paid_invest")
    private BigDecimal cPaidInvest;

    @JSONField(name = "c_paid_invest_f")
    private BigDecimal cPaidInvestF;

    @JSONField(name = "c_pay_acq_const_fiolta")
    private BigDecimal cPayAcqConstFiolta;

    @JSONField(name = "c_pay_acq_int_long_loan")
    private BigDecimal cPayAcqIntLongLoan;

    @JSONField(name = "disp_fix_assets_oth")
    private BigDecimal dispFixAssetsOth;

    @JSONField(name = "n_invest_loss")
    private BigDecimal nInvestLoss;

    // ==================== 筹资活动 ====================

    @JSONField(name = "c_fr_fnc_loan")
    private BigDecimal cFrFncLoan;

    @JSONField(name = "c_fr_fnc_oth")
    private BigDecimal cFrFncOth;

    @JSONField(name = "proceeds_long_loan")
    private BigDecimal proceedsLongLoan;

    @JSONField(name = "c_paid_fin_fees")
    private BigDecimal cPaidFinFees;

    @JSONField(name = "c_pay_dist_dpcp_int_exp")
    private BigDecimal cPayDistDpcpIntExp;

    // ==================== 现金等价物 ====================

    @JSONField(name = "end_bal_cash")
    private BigDecimal endBalCash;

    @JSONField(name = "beg_bal_cash")
    private BigDecimal begBalCash;

    @JSONField(name = "n_cash_equ")
    private BigDecimal nCashEqu;

    @JSONField(name = "n_increase_incl_child")
    private BigDecimal nIncreaseInclChild;

    // ==================== 间接法调整项 ====================

    @JSONField(name = "prov_depr_assets")
    private BigDecimal provDeprAssets;

    @JSONField(name = "depr_fa_coga_dpba")
    private BigDecimal deprFaCogaDpba;

    @JSONField(name = "amort_intang")
    private BigDecimal amortIntang;

    @JSONField(name = "amort_lt_deferred_exp")
    private BigDecimal amortLtDeferredExp;

    @JSONField(name = "loss_disp_fa")
    private BigDecimal lossDispFa;

    @JSONField(name = "loss_scr_fa")
    private BigDecimal lossScrFa;

    @JSONField(name = "loss_fair_valu")
    private BigDecimal lossFairValu;

    @JSONField(name = "fin_exp")
    private BigDecimal finExp;

    @JSONField(name = "loss_inv")
    private BigDecimal lossInv;

    @JSONField(name = "dec_def_inc_tax_assets")
    private BigDecimal decDefIncTaxAssets;

    @JSONField(name = "inc_def_inc_tax_liab")
    private BigDecimal incDefIncTaxLiab;

    @JSONField(name = "dec_inv")
    private BigDecimal decInv;

    @JSONField(name = "dec_oper_rece")
    private BigDecimal decOperRece;

    @JSONField(name = "inc_oper_payable")
    private BigDecimal incOperPayable;

    @JSONField(name = "net_profit")
    private BigDecimal netProfit;

    @JSONField(name = "minority_interest")
    private BigDecimal minorityInterest;

    @JSONField(name = "undistributed_profit_in")
    private BigDecimal undistributedProfitIn;

    @JSONField(name = "update_flag")
    private String updateFlag;
}
