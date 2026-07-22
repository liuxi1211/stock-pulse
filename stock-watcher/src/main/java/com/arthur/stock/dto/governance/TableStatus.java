package com.arthur.stock.dto.governance;

import lombok.Getter;
import lombok.AllArgsConstructor;

@Getter
@AllArgsConstructor
public enum TableStatus {
    NORMAL("正常"),
    DELAYED("延迟"),
    ERROR("异常"),
    UPDATING("更新中");

    private final String label;
}
