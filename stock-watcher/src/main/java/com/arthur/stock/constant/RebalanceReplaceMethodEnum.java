package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 换仓方式枚举（统一策略配置 Schema §3.3.4 / engine RebalanceModel.replace_method）。
 * <p>
 * 与 engine 侧 PydanticLiteral["full","incremental"] 同源，
 * 前端通过 GET /constants 获取（键 strategies.rebalanceReplaceMethod）。
 */
@Getter
public enum RebalanceReplaceMethodEnum implements DisplayableEnum {

    FULL("full", "全换"),
    INCREMENTAL("incremental", "增量换仓");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RebalanceReplaceMethodEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RebalanceReplaceMethodEnum fromCode(String code) {
        if (code == null) return null;
        for (RebalanceReplaceMethodEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
