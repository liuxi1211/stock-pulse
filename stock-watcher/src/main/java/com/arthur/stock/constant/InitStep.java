package com.arthur.stock.constant;

import lombok.Getter;

/**
 * 数据初始化步骤枚举，定义每个可独立执行的初始化步骤及其对应的数据库表
 */
@Getter
public enum InitStep {

    STOCK_BASIC("stock_basic", "股票基础信息", "stock_basic"),
    TRADE_CAL("trade_cal", "交易日历", "trade_cal"),
    INDEX_WEIGHT("index_weight", "指数成分权重", "index_weight"),
    SW_INDUSTRY("sw_industry", "申万行业分类", "sw_industry"),
    DAILY("daily", "日线行情", "daily_quote"),
    ADJ_FACTOR("adj_factor", "复权因子", "adj_factor"),
    DIVIDEND("dividend", "分红送股", "dividend");

    private final String code;
    private final String label;
    private final String tableName;

    InitStep(String code, String label, String tableName) {
        this.code = code;
        this.label = label;
        this.tableName = tableName;
    }

    public static InitStep fromCode(String code) {
        for (InitStep step : values()) {
            if (step.code.equalsIgnoreCase(code)) {
                return step;
            }
        }
        return null;
    }
}
