package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 选股候选池类型枚举（spec FR-10）。
 * <p>
 * 与 engine screener.py 的 universe Literal、ScreenConfigValidator.VALID_UNIVERSES、
 * ScreenerServiceImpl.resolveUniverse 同源，前端通过 {@code GET /constants} 获取。
 */
@Getter
public enum UniverseEnum implements DisplayableEnum {

    ALL_A_SHARES("all_a_shares", "全部 A 股"),
    CSI300("csi300", "沪深 300"),
    CSI500("csi500", "中证 500"),
    MANUAL("manual", "手动指定");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    UniverseEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static UniverseEnum fromCode(String code) {
        if (code == null) return null;
        for (UniverseEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
