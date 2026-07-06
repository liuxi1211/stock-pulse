package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股条件树逻辑运算符枚举。
 * <p>
 * 对齐 engine condition.py ConditionTree.operator。
 */
@Getter
public enum LogicalOperatorEnum implements DisplayableEnum {

    AND("AND", "且"),
    OR("OR", "或");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    LogicalOperatorEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static LogicalOperatorEnum fromCode(String code) {
        if (code == null) return null;
        for (LogicalOperatorEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
