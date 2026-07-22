package com.arthur.stock.constant;

import lombok.Getter;

/**
 * 数据初始化步骤枚举，定义每个可独立执行的初始化步骤及其对应的数据库表
 */
@Getter
public enum InitStep {

    STOCK_BASIC("stock_basic", "股票基础信息", "stock_basic", TableGroup.BASIC, "每日 16:00", "16:00", false, "stock_basic"),
    TRADE_CAL("trade_cal", "交易日历", "trade_cal", TableGroup.BASIC, "每日 16:00", "16:00", false, "trade_cal"),
    INDEX_WEIGHT("index_weight", "指数成分权重", "index_weight", TableGroup.INDEX, "每日 20:00", "20:00", false, "index_weight"),
    SW_INDUSTRY("sw_industry", "申万行业分类", "sw_industry", TableGroup.INDEX, "每半年", null, false, "index_classify"),
    DAILY("daily", "日线行情", "daily_quote", TableGroup.MARKET, "每个交易日 16:00", "16:00", true, "daily"),
    ADJ_FACTOR("adj_factor", "复权因子", "adj_factor", TableGroup.MARKET, "每个交易日 16:00", "16:00", true, "adj_factor"),
    DIVIDEND("dividend", "分红送股", "dividend", TableGroup.EVENT, "每日 16:00", "16:00", false, "dividend"),
    NAMECHANGE("namechange", "股票更名历史(ST)", "stock_namechange", TableGroup.EVENT, "每日 16:30", "16:30", false, "namechange"),
    SUSPEND_D("suspend_d", "停复牌信息", "stock_suspend_d", TableGroup.EVENT, "每日 16:35", "16:35", false, "suspend_d"),
    STK_LIMIT("stk_limit", "涨跌停价", "stock_stk_limit", TableGroup.MARKET, "每个交易日 16:40", "16:40", true, "stk_limit"),
    INCOME("income", "利润表", "income", TableGroup.FINANCE, "每周日 17:30", "17:30", false, "income"),
    BALANCESHEET("balancesheet", "资产负债表", "balancesheet", TableGroup.FINANCE, "每周日 18:00", "18:00", false, "balancesheet"),
    CASHFLOW("cashflow", "现金流量表", "cashflow", TableGroup.FINANCE, "每周日 18:30", "18:30", false, "cashflow"),
    FORECAST("forecast", "业绩预告", "forecast", TableGroup.FINANCE, "每周日 19:00", "19:00", false, "forecast"),
    EXPRESS("express", "业绩快报", "express", TableGroup.FINANCE, "每周日 19:30", "19:30", false, "express"),
    DAILY_BASIC("daily_basic", "每日基本面/估值", "daily_basic", TableGroup.MARKET, "每个交易日 16:10", "16:10", true, "daily_basic"),
    FINA_INDICATOR("fina_indicator", "财务指标", "fina_indicator", TableGroup.FINANCE, "每周日 17:00", "17:00", false, "fina_indicator"),
    MONEYFLOW("moneyflow", "个股资金流向", "stock_moneyflow", TableGroup.MARKET, "每个交易日 16:10", "16:10", true, "moneyflow"),
    TOP_LIST("top_list", "龙虎榜-每日榜单", "top_list", TableGroup.EVENT, "每个交易日 16:10", "16:10", true, "top_list"),
    TOP_INST("top_inst", "龙虎榜-机构席位", "top_inst", TableGroup.EVENT, "每个交易日 16:10", "16:10", true, "top_inst"),
    BLOCK_TRADE("block_trade", "大宗交易", "block_trade", TableGroup.EVENT, "每个交易日 16:10", "16:10", true, "block_trade"),
    HK_HOLD("hk_hold", "沪深港通持股", "hk_hold", TableGroup.INDEX, "每个交易日 16:10 (T+1)", "16:10", true, "hk_hold"),
    MARGIN("margin", "融资融券-汇总", "margin", TableGroup.INDEX, "每个交易日 16:10", "16:10", true, "margin"),
    MARGIN_DETAIL("margin_detail", "融资融券-明细", "margin_detail", TableGroup.INDEX, "每个交易日 16:10", "16:10", true, "margin_detail"),
    INDEX_DAILY("index_daily", "指数日线", "index_daily", TableGroup.INDEX, "每个交易日 16:30", "16:30", true, "index_daily");

    private final String code;
    private final String label;
    private final String tableName;
    private final TableGroup group;
    private final String updateFrequency;
    private final String expectedUpdateTime;
    private final boolean isDaily;
    private final String tushareApi;

    InitStep(String code, String label, String tableName, TableGroup group, String updateFrequency, String expectedUpdateTime, boolean isDaily, String tushareApi) {
        this.code = code;
        this.label = label;
        this.tableName = tableName;
        this.group = group;
        this.updateFrequency = updateFrequency;
        this.expectedUpdateTime = expectedUpdateTime;
        this.isDaily = isDaily;
        this.tushareApi = tushareApi;
    }

    public static InitStep fromCode(String code) {
        for (InitStep step : values()) {
            if (step.code.equalsIgnoreCase(code)) {
                return step;
            }
        }
        return null;
    }

    public static InitStep fromTableName(String tableName) {
        for (InitStep step : values()) {
            if (step.tableName.equalsIgnoreCase(tableName)) {
                return step;
            }
        }
        return null;
    }
}
