package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 调仓频率枚举（统一策略配置 Schema §3.3.4 / engine RebalanceModel.frequency）。
 * <p>
 * 与 engine 侧 Pydantic Literal["daily","weekly","monthly","quarterly"] 同源，
 * 前端通过 GET /constants 获取（键 strategies.rebalanceFrequency）。
 */
@Getter
public enum RebalanceFrequencyEnum implements DisplayableEnum {

    DAILY("daily", "每日"),
    WEEKLY("weekly", "每周"),
    MONTHLY("monthly", "每月"),
    QUARTERLY("quarterly", "每季");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    RebalanceFrequencyEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static RebalanceFrequencyEnum fromCode(String code) {
        if (code == null) return null;
        for (RebalanceFrequencyEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
