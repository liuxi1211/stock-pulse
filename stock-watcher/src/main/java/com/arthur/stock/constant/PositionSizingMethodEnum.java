package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 仓位管理下单方法枚举（spec 004 §3.3.2）。
 * <p>
 * 与 stock-engine {@code services/strategy/constants.py} 的 POSITION_SIZING_METHODS 白名单、
 * validator 的 method 校验同源；code 对齐 akquant {@code Strategy} 下单方法名。
 * 前端通过 {@code GET /constants}（key=strategies.positionMethods）获取。
 */
@Getter
public enum PositionSizingMethodEnum implements DisplayableEnum {

    ORDER_TARGET_PERCENT("order_target_percent", "目标百分比"),
    ORDER_TARGET_VALUE("order_target_value", "目标市值"),
    ORDER_TARGET("order_target", "目标数量"),
    BUY("buy", "买入"),
    SELL("sell", "卖出"),
    BUY_ALL("buy_all", "全仓买入"),
    CLOSE_POSITION("close_position", "平仓"),
    ORDER_TARGET_WEIGHTS("order_target_weights", "目标权重"),
    GRID("grid", "网格交易");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    PositionSizingMethodEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static PositionSizingMethodEnum fromCode(String code) {
        if (code == null) return null;
        for (PositionSizingMethodEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
