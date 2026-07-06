package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股截面比较器枚举（spec FR-2）。
 * <p>
 * cross_up / cross_down 仅交易路径，选股禁用。
 */
@Getter
public enum ComparatorEnum implements DisplayableEnum {

    GT(">", "大于"),
    LT("<", "小于"),
    GE(">=", "大于等于"),
    LE("<=", "小于等于"),
    EQ("==", "等于"),
    NE("!=", "不等于");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ComparatorEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ComparatorEnum fromCode(String code) {
        if (code == null) return null;
        for (ComparatorEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
