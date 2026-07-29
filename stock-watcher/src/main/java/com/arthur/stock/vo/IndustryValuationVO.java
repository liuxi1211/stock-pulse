package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行业估值聚合视图对象，用于板块估值展示。
 * <p>
 * PE/PB 基于 daily_basic 与 sw_industry_member 关联聚合得到。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryValuationVO {

    /** 行业代码（申万一级行业 index_code，如 801010） */
    private String industryCode;

    /** 行业名称（如 农林牧渔） */
    private String industryName;

    /** 交易日 yyyyMMdd */
    private String tradeDate;

    /** 市值加权 PE_TTM（SUM(pe_ttm * total_mv) / SUM(total_mv)），仅统计 pe_ttm>0 且 total_mv>0 的成分股 */
    private Double peTtm;

    /** 算术平均 PB（AVG(pb)），仅统计 pb>0 的成分股 */
    private Double pb;
}
