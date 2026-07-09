package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 策略适用范围枚举（spec FR-2）。
 * <p>
 * 标识策略面向的标的范围：单标的 / 组合 / 混合。
 */
@Getter
public enum StrategyScopeEnum implements DisplayableEnum {

    SINGLE("single", "单标的"),
    PORTFOLIO("portfolio", "组合"),
    MIXED("mixed", "混合");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    StrategyScopeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static StrategyScopeEnum fromCode(String code) {
        if (code == null) return null;
        for (StrategyScopeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
