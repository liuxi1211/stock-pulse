package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 股票市场枚举（沪市/深市）
 */
@Getter
public enum MarketEnum implements DisplayableEnum {

    /** 上海市场 */
    SH("SH", "沪市"),
    /** 深圳市场 */
    SZ("SZ", "深市");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    MarketEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static MarketEnum fromCode(String code) {
        if (code == null) return null;
        for (MarketEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }

    /**
     * 根据股票代码首位数字推断市场：6→沪市，0/3→深市
     */
    public static MarketEnum fromSymbol(String symbol) {
        if (symbol == null || symbol.isEmpty()) return null;
        return switch (symbol.charAt(0)) {
            case '6' -> SH;
            case '0', '3' -> SZ;
            default -> null;
        };
    }
}
