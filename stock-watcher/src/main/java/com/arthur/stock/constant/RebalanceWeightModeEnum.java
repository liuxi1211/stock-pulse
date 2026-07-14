package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 权重模式枚举（统一策略配置 Schema §3.3.4 / engine RebalanceModel.weight_mode）。
 * <p>
 * 与 engine 侧 PydanticLiteral["equal","score"] 同源，
 * 前端通过 GET /constants 获取（键 strategies.rebalanceWeightMode）。
 */
@Getter
public enum RebalanceWeightModeEnum implements DisplayableEnum {

    EQUAL("equal", "等权"),
    SCORE("score", "按分加权");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RebalanceWeightModeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RebalanceWeightModeEnum fromCode(String code) {
        if (code == null) return null;
        for (RebalanceWeightModeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
