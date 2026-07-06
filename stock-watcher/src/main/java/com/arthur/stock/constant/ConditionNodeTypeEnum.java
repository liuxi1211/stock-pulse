package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股条件树节点类型枚举。
 * <p>
 * 对齐 engine condition.py：叶子节点 type=compare；预留 LOGIC 等扩展。
 */
@Getter
public enum ConditionNodeTypeEnum implements DisplayableEnum {

    COMPARE("compare", "比较叶子节点");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ConditionNodeTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ConditionNodeTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (ConditionNodeTypeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
