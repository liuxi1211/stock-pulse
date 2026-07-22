package com.arthur.stock.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 行业成分股视图对象，用于行业成分股行情展示
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndustryMemberVO {

    /** 股票代码，如 000001.SZ */
    private String tsCode;

    /** 股票名称 */
    private String name;

    /** 收盘价 */
    private BigDecimal close;

    /** 涨跌幅（%） */
    private BigDecimal pctChg;

    /** 成交量（手） */
    private BigDecimal vol;

    /** 成交额（千元） */
    private BigDecimal amount;

    /** 市场板块（主板/创业板/科创板/北交所） */
    private String market;
}
