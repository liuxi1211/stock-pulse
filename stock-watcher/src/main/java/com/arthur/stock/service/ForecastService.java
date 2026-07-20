package com.arthur.stock.service;

import com.arthur.stock.model.ForecastDO;

import java.util.List;

/**
 * 业绩预告服务接口（Tushare forecast，doc_id=45）。
 */
public interface ForecastService {

    /**
     * 从 Tushare 拉取指定股票的日期范围内业绩预告数据并保存。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveForecast(String tsCode, String startDate, String endDate);

    /**
     * 拉取所有在市股票的日期范围内业绩预告数据并保存（定时任务用）。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 拉取并保存的记录总数
     */
    int fetchAndSaveAllByRange(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部业绩预告数据（按报告期升序）。
     */
    List<ForecastDO> queryLocalByTsCode(String tsCode);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期业绩预告（point-in-time 查询，防 lookahead bias）。
     * 同一报告期可能有多条预告（首次+修正），取最新公告日的一条。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日期 yyyyMMdd
     */
    ForecastDO selectLatestAnnouncedBefore(String tsCode, String tradeDate);
}
