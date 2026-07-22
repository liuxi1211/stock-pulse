package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 涨跌幅/活跃度榜单类型枚举（行情中心 stock-list 页用）
 */
@Getter
public enum RankingType implements DisplayableEnum {

    /** 涨幅榜 */
    GAINERS("gainers", "涨幅榜"),
    /** 跌幅榜 */
    LOSERS("losers", "跌幅榜"),
    /** 换手率榜 */
    TURNOVER("turnover", "换手率榜"),
    /** 成交额榜 */
    AMOUNT("amount", "成交额榜"),
    /** 量比榜 */
    VOLUME_RATIO("volume_ratio", "量比榜"),
    /** 振幅榜 */
    AMPLITUDE("amplitude", "振幅榜");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RankingType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RankingType fromCode(String code) {
        if (code == null) return null;
        for (RankingType v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
