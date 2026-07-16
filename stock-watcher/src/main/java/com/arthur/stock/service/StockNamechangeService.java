package com.arthur.stock.service;

import com.arthur.stock.model.StockNamechangeDO;

import java.util.List;
import java.util.Map;

/**
 * 股票更名历史服务。
 * <p>
 * 负责从 tushare namechange 接口（doc_id=160）拉取股票更名历史并落库，
 * 提供批量查询能力（供 buildKlineData 内存判定 ST 状态）。
 */
public interface StockNamechangeService {

    /**
     * 全量分页拉取并落库（按 ts_code 幂等 delete-then-insert）。
     *
     * @return 落库记录数
     */
    int fetchAndSaveAll();

    /**
     * 增量拉取某日（tradeDate）的更名记录（按业务键 (ts_code, start_date) 幂等 delete-then-insert）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSaveIncremental(String tradeDate);

    /**
     * 批量取多只股票的全部更名记录（buildKlineData 内存判定 ST 用）。
     *
     * @param tsCodes 股票代码列表
     * @return key=tsCode，value=该股票的更名记录列表（按 start_date 升序）
     */
    Map<String, List<StockNamechangeDO>> listByTsCodes(List<String> tsCodes);
}
