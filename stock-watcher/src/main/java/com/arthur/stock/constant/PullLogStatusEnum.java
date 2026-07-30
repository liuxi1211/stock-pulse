package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 数据拉取日志状态枚举（data_pull_log.status）。
 */
@Getter
public enum PullLogStatusEnum implements DisplayableEnum {

    RUNNING("RUNNING", "执行中"),
    SUCCESS("SUCCESS", "成功"),
    FAILED("FAILED", "失败"),
    CANCELLED("CANCELLED", "已取消");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    PullLogStatusEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static PullLogStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (PullLogStatusEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
