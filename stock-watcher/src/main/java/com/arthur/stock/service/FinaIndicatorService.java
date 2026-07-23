package com.arthur.stock.service;

import com.arthur.stock.model.FinaIndicatorDO;

import java.util.List;

/**
 * 财务指标服务接口（Tushare fina_indicator，doc_id=79）。
 */
public interface FinaIndicatorService {

    /**
     * 从 Tushare 拉取指定股票的报告期范围内财务指标数据并保存。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 起始报告期 yyyyMMdd（含）
     * @param endDate   结束报告期 yyyyMMdd（含）
     * @return 拉取并保存的记录数
     */
    int fetchAndSaveFinaIndicator(String tsCode, String startDate, String endDate);

    /**
     * 拉取所有在市股票的报告期范围内财务指标数据并保存（定时任务用）。
     *
     * @param startDate 起始报告期 yyyyMMdd（含）
     * @param endDate   结束报告期 yyyyMMdd（含）
     * @return 拉取并保存的记录总数
     */
    int fetchAndSaveAllByRange(String startDate, String endDate);

    /**
     * 从本地数据库查询指定股票的全部财务指标数据（按报告期升序）。
     */
    List<FinaIndicatorDO> queryLocalByTsCode(String tsCode);

    /**
     * 取某股票「在 tradeDate 当日已公告」的最近一期财务指标（point-in-time 查询，防 lookahead bias）。
     */
    FinaIndicatorDO selectLatestAnnouncedBefore(String tsCode, String tradeDate);
}
