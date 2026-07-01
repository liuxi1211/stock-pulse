package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 交易日状态枚举，标识某日是否为交易日
 */
@Getter
public enum TradeDayStatusEnum implements DisplayableEnum {

    /** 休市（非交易日） */
    CLOSED("0", "休市"),
    /** 开市（交易日） */
    OPEN("1", "交易");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    TradeDayStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static TradeDayStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (TradeDayStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
