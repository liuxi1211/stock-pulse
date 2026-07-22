package com.arthur.stock.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 大宗交易折溢价率分布 VO。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BlockTradePremiumVO {

    /** 折溢价区间标签，如 "&lt;-5%"、"3%~5%" */
    private String premiumRange;

    /** 该区间内的大宗交易笔数 */
    private int count;
}
