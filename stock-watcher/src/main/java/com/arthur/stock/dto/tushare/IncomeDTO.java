package com.arthur.stock.dto.tushare;

import com.alibaba.fastjson2.annotation.JSONField;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Tushare income 接口返回的利润表数据。
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=33">Tushare income 接口文档</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncomeDTO {

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

    @JSONField(name = "basic_eps")
    private BigDecimal basicEps;

    @JSONField(name = "diluted_eps")
    private BigDecimal dilutedEps;

    @JSONField(name = "total_revenue")
    private BigDecimal totalRevenue;

    private BigDecimal revenue;

    @JSONField(name = "total_cogs")
    private BigDecimal totalCogs;

    @JSONField(name = "operate_cost")
    private BigDecimal operateCost;

    @JSONField(name = "operate_profit")
    private BigDecimal operateProfit;

    @JSONField(name = "non_oper_income")
    private BigDecimal nonOperIncome;

    @JSONField(name = "non_oper_exp")
    private BigDecimal nonOperExp;

    @JSONField(name = "total_profit")
    private BigDecimal totalProfit;

    @JSONField(name = "n_income")
    private BigDecimal nIncome;

    @JSONField(name = "n_income_attr_p")
    private BigDecimal nIncomeAttrP;

    @JSONField(name = "minority_interest")
    private BigDecimal minorityInterest;

    @JSONField(name = "adjust_profit")
    private BigDecimal adjustProfit;

    @JSONField(name = "income_tax")
    private BigDecimal incomeTax;

    @JSONField(name = "n_income_yoy")
    private BigDecimal nIncomeYoy;

    @JSONField(name = "dt_profit_yoy")
    private BigDecimal dtProfitYoy;

    @JSONField(name = "sell_exp")
    private BigDecimal sellExp;

    @JSONField(name = "admin_exp")
    private BigDecimal adminExp;

    @JSONField(name = "financial_exp")
    private BigDecimal financialExp;

    @JSONField(name = "rd_exp")
    private BigDecimal rdExp;

    @JSONField(name = "impair_end_invest")
    private BigDecimal impairEndInvest;

    @JSONField(name = "impair_end_oper")
    private BigDecimal impairEndOper;

    @JSONField(name = "invest_income")
    private BigDecimal investIncome;

    @JSONField(name = "invest_income_inc")
    private BigDecimal investIncomeInc;

    @JSONField(name = "invest_income_dec")
    private BigDecimal investIncomeDec;

    @JSONField(name = "fairvalue_change_income")
    private BigDecimal fairvalueChangeIncome;

    @JSONField(name = "exchange_gain")
    private BigDecimal exchangeGain;

    @JSONField(name = "asset_dispose_income")
    private BigDecimal assetDisposeIncome;

    @JSONField(name = "other_income")
    private BigDecimal otherIncome;

    @JSONField(name = "operate_n_income")
    private BigDecimal operateNIncome;

    @JSONField(name = "credit_impair_loss")
    private BigDecimal creditImpairLoss;

    @JSONField(name = "asset_impair_loss")
    private BigDecimal assetImpairLoss;

    private BigDecimal bbit;

    @JSONField(name = "bbit_yoy")
    private BigDecimal bbitYoy;

    @JSONField(name = "operate_profit_income_yoy")
    private BigDecimal operateProfitIncomeYoy;

    @JSONField(name = "update_flag")
    private String updateFlag;
}
