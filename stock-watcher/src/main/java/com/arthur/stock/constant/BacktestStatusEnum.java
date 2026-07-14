package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 回测任务状态枚举与状态机（spec FR-3 / FR-6）。
 * <p>
 * 状态流转规则：
 * <pre>
 *   PENDING ──▶ RUNNING ──▶ SUCCESS
 *          │           └──▶ FAILED
 *          └──▶ CANCELLED
 * </pre>
 * 第一波 RUNNING 不可取消（无法终止 engine 进程），仅 PENDING 可取消。
 */
@Getter
public enum BacktestStatusEnum implements DisplayableEnum {

    PENDING("PENDING", "等待中"),
    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "已完成"),
    FAILED("FAILED", "已失败"),
    CANCELLED("CANCELLED", "已取消");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    BacktestStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static BacktestStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (BacktestStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }

    /**
     * 判断当前状态是否为终态（不可再流转）。
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

    /**
     * 判断是否可以取消（第一波仅 PENDING 可取消）。
     */
    public boolean canCancel() {
        return this == PENDING;
    }
}
