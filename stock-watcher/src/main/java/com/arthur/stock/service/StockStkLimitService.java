package com.arthur.stock.service;

import com.arthur.stock.model.StockStkLimitDO;

import java.util.List;
import java.util.Map;

/**
 * 涨跌停价服务。
 * <p>
 * 负责从 tushare stk_limit 接口（doc_id=183）拉取股票涨跌停价信息并落库，
 * 提供批量查询能力（供 buildKlineData 内存精确判定涨停/跌停）。
 * <p>
 * 落库策略：按日期范围（start_date ~ end_date）查询，内部 offset/limit 分页（每页 5000），
 * 每页一个独立事务，事务内按 500 批次先删后插，保证幂等。
 */
public interface StockStkLimitService {

    /**
     * 按日期范围拉取涨跌停价并保存到数据库。
     * <p>
     * 使用 start_date / end_date 查询，内部按 offset/limit 分页（每页 5000），
     * 每查到一页立即用一个独立事务落库（事务内按 500 批次 delete-then-insert），保证幂等。
     * 适合按月或更大范围一次性拉取，避免逐日查询产生大量空请求。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 落库记录数
     */
    int fetchAndSaveByRange(String startDate, String endDate);

    /**
     * 增量拉取涨跌停价：从本地 MAX(trade_date) 到今天，一次性范围查询 + 分页落库。
     * <p>
     * 内部调用 {@link #fetchAndSaveByRange}，适合每日定时增量同步（通常只跨几天到一个月）。
     *
     * @return 落库记录数
     */
    int fetchAndSaveIncremental();

    /**
     * 批量取多只股票在 [startDate, endDate] 的涨跌停价，按 ts_code → {trade_date → DO} 双层 map（buildKlineData 用）。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return key=tsCode，value={key=trade_date, value=DO}
     */
    Map<String, Map<String, StockStkLimitDO>> listByRange(List<String> tsCodes, String startDate, String endDate);
}
