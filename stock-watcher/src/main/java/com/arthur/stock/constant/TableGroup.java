package com.arthur.stock.constant;

import lombok.Getter;

@Getter
public enum TableGroup {
    BASIC("基础数据"),
    MARKET("行情数据"),
    FINANCE("财务数据"),
    EVENT("事件数据"),
    INDEX("指数与市场");

    private final String label;

    TableGroup(String label) {
        this.label = label;
    }
}
