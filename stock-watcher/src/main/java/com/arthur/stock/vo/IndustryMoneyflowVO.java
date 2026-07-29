package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行业资金流聚合视图对象，用于板块资金流展示。
 * <p>
 * 各净额单位均为「万元」，与 stock_moneyflow 表口径一致；按行业（sw_industry_member.index_code）分组聚合。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryMoneyflowVO {

    /** 行业代码（申万一级行业 index_code，如 801010） */
    private String industryCode;

    /** 行业名称（如 农林牧渔） */
    private String industryName;

    /** 交易日 yyyyMMdd */
    private String tradeDate;

    /** 主力净流入（大单净额 + 特大单净额，万元） */
    private Double mainNetInflow;

    /** 特大单净流入（万元） */
    private Double elgNetInflow;

    /** 大单净流入（万元） */
    private Double lgNetInflow;

    /** 中单净流入（万元） */
    private Double mdNetInflow;

    /** 小单净流入（万元） */
    private Double smNetInflow;

    /** 全量净流入（SUM(net_mf_amount)，万元） */
    private Double netMfAmount;
}
