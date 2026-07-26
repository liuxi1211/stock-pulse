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
     * 按日期区间分页拉取全市场更名记录并落库（start_date~end_date + 分页 5000 条/页）。
     * <p>
     * 更名是稀疏事件（并非每个交易日都有更名），按交易日逐日拉取会产生大量空请求、受限于 Tushare 限流，
     * 30 年区间需数千次请求。改为按日期区间 + 分页一次性拉取，仅按实际数据量发起少量分页请求，
     * 大幅减少请求次数与耗时。
     * <p>
     * 落库策略与 {@link #fetchAndSaveAll()} 一致：按业务键 (ts_code, start_date) 幂等 delete-then-insert，
     * 每页一个独立事务流式落库（拉一页存一页）。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 落库记录数
     */
    int fetchAndSaveByRange(String startDate, String endDate);

    /**
     * 批量取多只股票的全部更名记录（buildKlineData 内存判定 ST 用）。
     *
     * @param tsCodes 股票代码列表
     * @return key=tsCode，value=该股票的更名记录列表（按 start_date 升序）
     */
    Map<String, List<StockNamechangeDO>> listByTsCodes(List<String> tsCodes);
}
