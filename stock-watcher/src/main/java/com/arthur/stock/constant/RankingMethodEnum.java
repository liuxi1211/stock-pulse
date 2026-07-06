package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股排序方法枚举。
 * <p>
 * 对齐 engine screener.py Ranking.method。
 */
@Getter
public enum RankingMethodEnum implements DisplayableEnum {

    SINGLE("single", "单因子"),
    COMPOSITE("composite", "复合权重");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RankingMethodEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RankingMethodEnum fromCode(String code) {
        if (code == null) return null;
        for (RankingMethodEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
