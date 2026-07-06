package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股结果锁定状态枚举（spec FR-9 追踪）。
 * <p>
 * TRACKING：锁定后追踪中；DONE：20 个交易日收益已填齐。
 */
@Getter
public enum ScreenLockStatusEnum implements DisplayableEnum {

    TRACKING("TRACKING", "追踪中"),
    DONE("DONE", "已完成");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    ScreenLockStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static ScreenLockStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (ScreenLockStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
