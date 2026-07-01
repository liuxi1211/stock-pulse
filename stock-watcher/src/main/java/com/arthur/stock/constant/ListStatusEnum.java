package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 股票上市状态枚举
 */
@Getter
public enum ListStatusEnum implements DisplayableEnum {

    /** 正常上市 */
    LISTED("L", "上市"),
    /** 已退市 */
    DELISTED("D", "退市"),
    /** 暂停上市 */
    SUSPENDED("P", "暂停上市"),
    /** 已过会但未交易 */
    APPROVED("G", "过会未交易");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ListStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ListStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (ListStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
