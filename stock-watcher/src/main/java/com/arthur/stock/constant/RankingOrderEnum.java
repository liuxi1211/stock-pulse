package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股排序方向枚举。
 * <p>
 * 对齐 engine screener.py Ranking.order。
 */
@Getter
public enum RankingOrderEnum implements DisplayableEnum {

    ASC("asc", "升序"),
    DESC("desc", "降序");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RankingOrderEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RankingOrderEnum fromCode(String code) {
        if (code == null) return null;
        for (RankingOrderEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
