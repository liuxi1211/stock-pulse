package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 仓位管理卖出方法枚举（spec 004 §3.3.2 sell_method）。
 * <p>
 * 与 stock-engine {@code services/strategy/constants.py} 的 SELL_METHODS 白名单同源。
 * 前端通过 {@code GET /constants}（key=strategies.sellMethods）获取。
 * <ul>
 *   <li>{@link #CLOSE_POSITION}：默认平仓（清掉当前标的持仓）</li>
 *   <li>{@link #SELL}：按 position_sizing.target 指定数量卖出</li>
 *   <li>{@link #SIGNAL_BASED}：按信号命中规则卖出</li>
 * </ul>
 */
@Getter
public enum SellMethodEnum implements DisplayableEnum {

    CLOSE_POSITION("close_position", "平仓"),
    SELL("sell", "指定数量卖出"),
    SIGNAL_BASED("signal_based", "按信号卖出");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    SellMethodEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static SellMethodEnum fromCode(String code) {
        if (code == null) return null;
        for (SellMethodEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
