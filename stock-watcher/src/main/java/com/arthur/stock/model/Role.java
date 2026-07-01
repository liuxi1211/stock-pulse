package com.arthur.stock.model;

import com.arthur.stock.constant.DisplayableEnum;
import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

/**
 * 用户角色枚举
 */
@Getter
public enum Role implements DisplayableEnum {

    /** 管理员，拥有系统管理权限 */
    ADMIN("ADMIN", "管理员"),
    /** 普通用户，仅可使用行情和自选股功能 */
    USER("USER", "普通用户");

    @EnumValue
    @JsonValue
    private final String code;
    private final String label;

    Role(String code, String label) {
        this.code = code;
        this.label = label;
    }
}
