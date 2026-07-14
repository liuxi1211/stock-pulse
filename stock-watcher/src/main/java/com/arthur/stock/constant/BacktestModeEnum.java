package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 回测模式枚举（spec FR-3 / FR-5）。
 * <p>
 * 第一波仅支持 SINGLE；GRID/WALK_FORWARD 为第二波预留枚举值，
 * 请求层会返回 BACKTEST_MODE_NOT_SUPPORTED。
 */
@Getter
public enum BacktestModeEnum implements DisplayableEnum {

    SINGLE("SINGLE", "单次回测"),
    GRID("GRID", "网格寻优（第二波）"),
    WALK_FORWARD("WALK_FORWARD", "滚动验证（第二波）");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    BacktestModeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static BacktestModeEnum fromCode(String code) {
        if (code == null) return null;
        for (BacktestModeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
