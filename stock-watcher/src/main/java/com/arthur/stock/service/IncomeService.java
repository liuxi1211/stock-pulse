package com.arthur.stock.service;

import com.arthur.stock.model.IncomeDO;

import java.util.List;

/**
 * 利润表服务接口（Tushare income，doc_id=33）。
 */
public interface IncomeService {

    /**
     * 从 Tushare 拉取指定股票的报告期范围内利润表数据并保存。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 起始报告期 yyyyMMdd（含）
     * @param endDate   结束报告期 yyyyMMdd（含）
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveIncome(String tsCode, String startDate, String endDate);

    /**
     * 拉取所有在市股票的报告期范围内利润表数据并保存（定时任务用）。
     *
     * @param startDate 起始报告期 yyyyMMdd（含）
     * @param endDate   结束报告期 yyyyMMdd（含）
     * @return 拉取并保存的记录总数
     */
    int fetchAndSaveAllByRange(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部利润表数据（按报告期升序）。
     */
    List<IncomeDO> queryLocalByTsCode(String tsCode);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期利润表（point-in-time 查询，防 lookahead bias）。
     *
     * @param tsCode    股票代码
     * @param tradeDate 交易日期 yyyyMMdd
     */
    IncomeDO selectLatestAnnouncedBefore(String tsCode, String tradeDate);
}
