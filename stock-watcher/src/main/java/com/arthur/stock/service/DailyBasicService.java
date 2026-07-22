package com.arthur.stock.service;

import com.arthur.stock.model.DailyBasicDO;

import java.util.List;

/**
 * 每日基本面数据服务：估值/换手率/市值等 daily_basic 表查询。
 */
public interface DailyBasicService {

    /**
     * 查某股票某交易日的基本面数据。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param tradeDate 交易日 yyyyMMdd；为 null 时取最新交易日
     */
    DailyBasicDO getByCodeAndDate(String tsCode, String tradeDate);

    /**
     * 查某股票在指定日期区间内的基本面数据（按 trade_date 升序）。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 开始日期 yyyyMMdd，可为 null
     * @param endDate   结束日期 yyyyMMdd，可为 null
     */
    List<DailyBasicDO> listByCodeAndDateRange(String tsCode, String startDate, String endDate);

    /**
     * 批量查询多只股票某交易日的基本面数据。
     *
     * @param tsCodes   股票代码列表
     * @param tradeDate 交易日 yyyyMMdd；为 null 时取最新交易日
     */
    List<DailyBasicDO> listByCodesAndDate(List<String> tsCodes, String tradeDate);

    /**
     * 取 daily_basic 表中最新的交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
