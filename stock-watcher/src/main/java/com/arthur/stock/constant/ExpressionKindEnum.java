package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股表达式节点类型枚举。
 * <p>
 * 对齐 engine condition.py ExpressionNode.kind。
 */
@Getter
public enum ExpressionKindEnum implements DisplayableEnum {

    FACTOR("factor", "因子表达式"),
    VALUE("value", "常数值");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ExpressionKindEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ExpressionKindEnum fromCode(String code) {
        if (code == null) return null;
        for (ExpressionKindEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
