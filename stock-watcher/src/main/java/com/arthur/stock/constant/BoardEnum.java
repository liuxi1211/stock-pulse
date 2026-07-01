package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 股票板块类型枚举
 */
@Getter
public enum BoardEnum implements DisplayableEnum {

    /** 主板 */
    MAIN("主板", "主板"),
    /** 创业板 */
    GEM("创业板", "创业板"),
    /** 科创板 */
    STAR("科创板", "科创板"),
    /** CDR（中国存托凭证） */
    CDR("CDR", "CDR"),
    /** 北交所 */
    BSE("北交所", "北交所");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    BoardEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static BoardEnum fromCode(String code) {
        if (code == null) return null;
        for (BoardEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
