package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 策略分类枚举（spec FR-2）。
 * <p>
 * 标识策略主要依据的数据维度：技术面 / 基本面 / 混合 / 自定义。
 */
@Getter
public enum StrategyCategoryEnum implements DisplayableEnum {

    TECHNICAL("TECHNICAL", "技术面"),
    FUNDAMENTAL("FUNDAMENTAL", "基本面"),
    MIXED("MIXED", "混合"),
    CUSTOM("CUSTOM", "自定义");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    StrategyCategoryEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static StrategyCategoryEnum fromCode(String code) {
        if (code == null) return null;
        for (StrategyCategoryEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
