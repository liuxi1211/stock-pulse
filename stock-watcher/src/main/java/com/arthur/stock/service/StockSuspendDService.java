package com.arthur.stock.service;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股票停复牌服务。
 * <p>
 * 负责从 tushare suspend_d 接口（doc_id=161）拉取股票停复牌信息并落库，
 * 提供批量查询能力（供 buildKlineData 内存停牌过滤）。
 */
public interface StockSuspendDService {

    /**
     * 全量分页拉取并落库（按业务键 (ts_code, trade_date) 幂等 delete-then-insert）。
     *
     * @return 落库记录数
     */
    int fetchAndSaveAll();

    /**
     * 增量拉取某日（tradeDate）的停牌记录（按业务键 (ts_code, trade_date) 幂等 delete-then-insert）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSaveIncremental(String tradeDate);

    /**
     * 批量取多只股票在 [startDate, endDate] 的停牌记录，按 ts_code 分组（buildKlineData 用）。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return key=tsCode，value=该股票在区间内的停牌日期集合
     */
    Map<String, Set<String>> listSuspendDates(List<String> tsCodes, String startDate, String endDate);
}
