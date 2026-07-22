package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 行业排行视图对象，用于板块行情展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryRankingVO {

    /** 行业代码（对应 sw_industry.index_code，如 801010） */
    private String industryCode;

    /** 行业名称（对应 sw_industry.index_name，如 农林牧渔） */
    private String industryName;

    /** 指数代码（对应 index_daily.ts_code，如 801010.SI） */
    private String indexCode;

    /** 今日涨跌幅（%） */
    private BigDecimal pctChg;

    /** 今日成交额（亿元） */
    private BigDecimal amount;

    /** 成分股总数 */
    private Integer constituentCount;

    /** 有行情的成分股数（剔除停牌等） */
    private Integer activeCount;

    /** 交易日 yyyyMMdd */
    private String tradeDate;

    /** 领涨股代码 */
    private String topGainerCode;

    /** 领涨股名称 */
    private String topGainerName;

    /** 领涨股涨跌幅（%） */
    private BigDecimal topGainerPctChg;

    /** 领跌股代码 */
    private String topLoserCode;

    /** 领跌股名称 */
    private String topLoserName;

    /** 领跌股涨跌幅（%） */
    private BigDecimal topLoserPctChg;
}
