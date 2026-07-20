package com.arthur.stock.service;

import com.arthur.stock.model.ExpressDO;

import java.util.List;

/**
 * 业绩快报服务接口（Tushare express，doc_id=46）。
 */
public interface ExpressService {

    /**
     * 从 Tushare 拉取指定股票的日期范围内业绩快报数据并保存。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveExpress(String tsCode, String startDate, String endDate);

    /**
     * 拉取所有在市股票的日期范围内业绩快报数据并保存（定时任务用）。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 拉取并保存的记录总数
     */
    int fetchAndSaveAllByRange(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部业绩快报数据（按报告期升序）。
     */
    List<ExpressDO> queryLocalByTsCode(String tsCode);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期业绩快报（point-in-time 查询，防 lookahead bias）。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日期 yyyyMMdd
     */
    ExpressDO selectLatestAnnouncedBefore(String tsCode, String tradeDate);
}
