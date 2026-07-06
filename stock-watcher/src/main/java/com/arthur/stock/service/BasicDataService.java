package com.arthur.stock.service;

/**
 * 基本面数据服务：负责从 Tushare 拉取 daily_basic（估值/换手率/市值）与
 * fina_indicator（ROE/ROA/毛利率/同比/资产负债率）并持久化到本地库。
 * <p>
 * 供选股中心 buildFundamentals 消费，补齐 fundamentals 缺口。
 */
public interface BasicDataService {

    /**
     * 拉取某交易日全市场每日基本面（daily_basic）并保存。
     *
     * @param tradeDate yyyyMMdd
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveDailyBasic(String tradeDate);

    /**
     * 增量拉取财务指标（fina_indicator）。策略：拉取最近 N 个报告期。
     *
     * @param startPeriod 起始报告期 yyyyMMdd（如 20240101）
     * @param endPeriod   结束报告期 yyyyMMdd
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveFinaIndicator(String startPeriod, String endPeriod);
}
