package com.arthur.stock.service;

import com.arthur.stock.model.StockStkLimitDO;

import java.util.List;
import java.util.Map;

/**
 * 涨跌停价服务。
 * <p>
 * 负责从 tushare stk_limit 接口（doc_id=183）拉取股票涨跌停价信息并落库，
 * 提供批量查询能力（供 buildKlineData 内存精确判定涨停/跌停）。
 */
public interface StockStkLimitService {

    /**
     * 全量分页拉取并落库（按业务键 (ts_code, trade_date) 幂等 delete-then-insert）。
     *
     * @return 落库记录数
     */
    int fetchAndSaveAll();

    /**
     * 增量拉取某日（tradeDate）的涨跌停价（按业务键 (ts_code, trade_date) 幂等 delete-then-insert）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSaveIncremental(String tradeDate);

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
