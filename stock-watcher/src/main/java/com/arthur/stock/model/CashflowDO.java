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
 * 现金流量表数据对象，对应 cashflow 表（Tushare cashflow）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("cashflow")
public class CashflowDO {

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

    // ==================== 现金流量净额 ====================
    private BigDecimal nCashflowAct;
    private BigDecimal nCashflowInvAct;
    private BigDecimal nCashFlowsFncAct;
    private BigDecimal freeCashflow;

    // ==================== 经营活动 ====================
    private BigDecimal cFrSaleSg;
    private BigDecimal cFrOthSg;
    private BigDecimal cPaidGoodsS;
    private BigDecimal cPaidToForEmpl;
    private BigDecimal cPaidForTaxes;
    private BigDecimal cPaidOthOpF;

    // ==================== 投资活动 ====================
    private BigDecimal cPaidInvest;
    private BigDecimal cPaidInvestF;
    private BigDecimal cPayAcqConstFiolta;
    private BigDecimal cPayAcqIntLongLoan;
    private BigDecimal dispFixAssetsOth;
    private BigDecimal nInvestLoss;

    // ==================== 筹资活动 ====================
    private BigDecimal cFrFncLoan;
    private BigDecimal cFrFncOth;
    private BigDecimal proceedsLongLoan;
    private BigDecimal cPaidFinFees;
    private BigDecimal cPayDistDpcpIntExp;

    // ==================== 现金等价物 ====================
    private BigDecimal endBalCash;
    private BigDecimal begBalCash;
    private BigDecimal nCashEqu;
    private BigDecimal nIncreaseInclChild;

    // ==================== 间接法调整项 ====================
    private BigDecimal provDeprAssets;
    private BigDecimal deprFaCogaDpba;
    private BigDecimal amortIntang;
    private BigDecimal amortLtDeferredExp;
    private BigDecimal lossDispFa;
    private BigDecimal lossScrFa;
    private BigDecimal lossFairValu;
    private BigDecimal finExp;
    private BigDecimal lossInv;
    private BigDecimal decDefIncTaxAssets;
    private BigDecimal incDefIncTaxLiab;
    private BigDecimal decInv;
    private BigDecimal decOperRece;
    private BigDecimal incOperPayable;
    private BigDecimal netProfit;
    private BigDecimal minorityInterest;
    private BigDecimal undistributedProfitIn;

    private String updateFlag;
}
