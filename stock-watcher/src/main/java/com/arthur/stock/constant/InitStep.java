package com.arthur.stock.constant;

import lombok.Getter;

/**
 * 数据初始化步骤枚举，定义每个可独立执行的初始化步骤及其对应的数据库表。
 * <p>
 * 执行时间相关的可视化已下沉到 data_pull_log.cron_expression（由 AOP 切面解析 @Scheduled 写入），
 * 本枚举不再持有 expectedUpdateTime 字段；updateFrequency 仅保留作人类可读备注。
 */
@Getter
public enum InitStep {

    STOCK_BASIC("stock_basic", "股票基础信息", "stock_basic", TableGroup.BASIC, "每日 16:00", false, "stock_basic"),
    TRADE_CAL("trade_cal", "交易日历", "trade_cal", TableGroup.BASIC, "每日 16:00", false, "trade_cal"),
    INDEX_WEIGHT("index_weight", "指数成分权重", "index_weight", TableGroup.INDEX, "每日 20:00", false, "index_weight"),
    INDEX_BASIC("index_basic", "指数基本信息", "index_basic", TableGroup.INDEX, "每日 16:00", false, "index_basic"),
    SW_INDUSTRY("sw_industry", "申万行业分类", "sw_industry", TableGroup.INDEX, "每半年", false, "index_classify"),
    DAILY("daily", "日线行情", "daily_quote", TableGroup.MARKET, "每个交易日 16:00", true, "daily"),
    ADJ_FACTOR("adj_factor", "复权因子", "adj_factor", TableGroup.MARKET, "每个交易日 16:00", true, "adj_factor"),
    DIVIDEND("dividend", "分红送股", "dividend", TableGroup.EVENT, "每日 16:00", false, "dividend"),
    NAMECHANGE("namechange", "股票更名历史(ST)", "stock_namechange", TableGroup.EVENT, "每日 16:30", false, "namechange"),
    SUSPEND_D("suspend_d", "停复牌信息", "stock_suspend_d", TableGroup.EVENT, "每日 16:40", false, "suspend_d"),
    STK_LIMIT("stk_limit", "涨跌停价", "stock_stk_limit", TableGroup.MARKET, "每个交易日 16:40", true, "stk_limit"),
    STK_HOLDERTRADE("stk_holdertrade", "股东增减持", "stk_holdertrade", TableGroup.EVENT, "每周日", false, "stk_holdertrade"),
    STK_HOLDERNUMBER("stk_holdernumber", "股东人数", "stk_holdernumber", TableGroup.EVENT, "每周日", false, "stk_holdernumber"),
    INCOME("income", "利润表", "income", TableGroup.FINANCE, "每周日", false, "income"),
    BALANCESHEET("balancesheet", "资产负债表", "balancesheet", TableGroup.FINANCE, "每周日", false, "balancesheet"),
    CASHFLOW("cashflow", "现金流量表", "cashflow", TableGroup.FINANCE, "每周日", false, "cashflow"),
    FORECAST("forecast", "业绩预告", "forecast", TableGroup.FINANCE, "每周日", false, "forecast"),
    EXPRESS("express", "业绩快报", "express", TableGroup.FINANCE, "每周日", false, "express"),
    DAILY_BASIC("daily_basic", "每日基本面/估值", "daily_basic", TableGroup.MARKET, "每个交易日 16:30", true, "daily_basic"),
    FINA_INDICATOR("fina_indicator", "财务指标", "fina_indicator", TableGroup.FINANCE, "每周日", false, "fina_indicator"),
    MONEYFLOW("moneyflow", "个股资金流向", "stock_moneyflow", TableGroup.MARKET, "每个交易日 16:30", true, "moneyflow"),
    TOP_LIST("top_list", "龙虎榜-每日榜单", "top_list", TableGroup.EVENT, "每个交易日 16:30", true, "top_list"),
    TOP_INST("top_inst", "龙虎榜-机构席位", "top_inst", TableGroup.EVENT, "每个交易日 16:30", true, "top_inst"),
    BLOCK_TRADE("block_trade", "大宗交易", "block_trade", TableGroup.EVENT, "每个交易日 16:30", true, "block_trade"),
    HK_HOLD("hk_hold", "沪深港通持股", "hk_hold", TableGroup.INDEX, "每个交易日 16:30 (T+1)", true, "hk_hold"),
    MARGIN("margin", "融资融券-汇总", "margin", TableGroup.INDEX, "每个交易日 16:30", true, "margin"),
    MARGIN_DETAIL("margin_detail", "融资融券-明细", "margin_detail", TableGroup.INDEX, "每个交易日 16:30", true, "margin_detail"),
    INDEX_DAILY("index_daily", "指数日线", "index_daily", TableGroup.INDEX, "每个交易日 16:30", true, "index_daily");

    private final String code;
    private final String label;
    private final String tableName;
    private final TableGroup group;
    private final String updateFrequency;
    private final boolean isDaily;
    private final String tushareApi;

    InitStep(String code, String label, String tableName, TableGroup group, String updateFrequency, boolean isDaily, String tushareApi) {
        this.code = code;
        this.label = label;
        this.tableName = tableName;
        this.group = group;
        this.updateFrequency = updateFrequency;
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
