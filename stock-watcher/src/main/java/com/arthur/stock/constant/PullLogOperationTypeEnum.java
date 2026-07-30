package com.arthur.stock.constant;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 数据拉取操作类型枚举（data_pull_log.operation_type）。
 */
@Getter
public enum PullLogOperationTypeEnum implements DisplayableEnum {

    /** 定时任务触发 */
    SCHEDULED("SCHEDULED", "定时任务"),
    /** 手动增量更新 */
    MANUAL_INCREMENTAL("MANUAL_INCREMENTAL", "手动增量"),
    /** 手动全量重建 */
    MANUAL_FULL("MANUAL_FULL", "手动全量");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    PullLogOperationTypeEnum(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public static PullLogOperationTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (PullLogOperationTypeEnum v : values()) {
            if (v.code.equals(code)) return v;
        }
        return null;
    }
}
