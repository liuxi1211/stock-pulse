package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;

/**
 * 策略状态枚举与状态机（spec FR-2）。
 * <p>
 * 状态流转规则：
 * <pre>
 *   DRAFT ──▶ VERIFIED ──▶ ACTIVE ──▶ ARCHIVED
 *     ▲          │
 *     └──────────┘ (VERIFIED 可退回 DRAFT)
 * </pre>
 * ARCHIVED 为终态，不可再流转。
 */
@Getter
public enum StrategyStatusEnum implements DisplayableEnum {

    DRAFT("DRAFT", "草稿"),
    VERIFIED("VERIFIED", "已验证"),
    ACTIVE("ACTIVE", "已激活"),
    ARCHIVED("ARCHIVED", "已归档");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    StrategyStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static StrategyStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (StrategyStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }

    /**
     * 判断当前状态是否可以流转到目标状态。
     * <ul>
     *   <li>DRAFT → VERIFIED、DRAFT(保留)</li>
     *   <li>VERIFIED → DRAFT、ACTIVE</li>
     *   <li>ACTIVE → ARCHIVED</li>
     *   <li>ARCHIVED → 无（终态）</li>
     * </ul>
     *
     * @param target 目标状态
     * @return 允许流转返回 true
     */
    public boolean canTransitionTo(StrategyStatusEnum target) {
        if (target == null || target == this) {
            return true;
        }
        switch (this) {
            case DRAFT:
                return target == VERIFIED;
            case VERIFIED:
                return target == DRAFT || target == ACTIVE;
            case ACTIVE:
                return target == ARCHIVED;
            case ARCHIVED:
                return false;
            default:
                return false;
        }
    }

    /**
     * 返回当前状态可流转到的所有合法目标状态集合（含自身）。
     */
    public Set<StrategyStatusEnum> allowedTransitions() {
        switch (this) {
            case DRAFT:
                return EnumSet.of(DRAFT, VERIFIED);
            case VERIFIED:
                return EnumSet.of(DRAFT, VERIFIED, ACTIVE);
            case ACTIVE:
                return EnumSet.of(ACTIVE, ARCHIVED);
            case ARCHIVED:
                return EnumSet.of(ARCHIVED);
            default:
                return EnumSet.noneOf(StrategyStatusEnum.class);
        }
    }
}
