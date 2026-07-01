package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 沪深港通标识枚举，标识股票是否为沪港通/深港通标的
 */
@Getter
public enum HsConnectEnum implements DisplayableEnum {

    /** 非沪深港通标的 */
    NO("N", "否"),
    /** 沪股通标的 */
    SH_CONNECT("H", "沪股通"),
    /** 深港通标的 */
    SZ_CONNECT("S", "深港通");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    HsConnectEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static HsConnectEnum fromCode(String code) {
        if (code == null) return null;
        for (HsConnectEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
