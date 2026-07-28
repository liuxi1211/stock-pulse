package com.arthur.stock.service;

import com.arthur.stock.model.StockSuspendDDO;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股票停复牌服务。
 * <p>
 * 负责从 tushare suspend_d 接口（doc_id=161）拉取股票停复牌事件并落库（事件模型：S=停牌，R=复牌）。
 * 提供批量查询能力：通过状态机推导实际停牌日期，供 buildKlineData 内存停牌过滤使用。
 */
public interface StockSuspendDService {

    /**
     * 全量拉取并落库（按月拆分，月内分页；月内超 10w 自动降级按股票拉取）。
     * <p>
     * 落库策略：按业务键 (ts_code, trade_date, suspend_type) 幂等 delete-then-insert。
     *
     * @return 落库记录数
     */
    int fetchAndSaveAll();

    /**
     * 增量拉取某日（tradeDate）的停复牌事件。
     * <p>
     * 落库策略：按业务键 (ts_code, trade_date, suspend_type) 幂等 delete-then-insert。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSaveIncremental(String tradeDate);

    /**
     * 按日期区间拉取全市场停复牌事件并落库（按月拆分，月内分页；月内超 10w 自动降级按股票拉取）。
     * <p>
     * 停复牌事件稀疏（并非每个交易日都有事件），按交易日逐日拉取会产生大量空请求、受限于 Tushare 限流。
     * 按月拆分后单月数据量约 2000 条，远低于 offset 上限 10w，月内 1~2 页即可拉完；
     * 极端月份（如股灾月）若超 10w，自动降级按股票逐只拉取兜底，确保数据不丢。
     * <p>
     * 落库策略：按业务键 (ts_code, trade_date, suspend_type) 幂等 delete-then-insert，
     * 每页一个独立事务流式落库（拉一页存一页）。
     *
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 落库记录数
     */
    int fetchAndSaveByRange(String startDate, String endDate);

    /**
     * 批量计算多只股票在 [startDate, endDate] 内的实际停牌日期，按 ts_code 分组（buildKlineData 用）。
     * <p>
     * 基于事件模型（S=停牌/R=复牌）用状态机推导每日停牌状态；
     * 仅全天停牌（suspend_timing 为空）计入，盘中临时停牌忽略。
     *
     * @param tsCodes   股票代码列表
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return key=tsCode，value=该股票在区间内的停牌日期集合
     */
    Map<String, Set<String>> listSuspendDates(List<String> tsCodes, String startDate, String endDate);

    /**
     * 查询某只股票在指定日期区间内的全部停复牌事件记录（供个股诊断展示）。
     *
     * @param tsCode    股票代码，如 000001.SZ
     * @param startDate 起始日期 yyyyMMdd（含）
     * @param endDate   结束日期 yyyyMMdd（含）
     * @return 事件记录列表，按 ts_code、trade_date 升序
     */
    List<StockSuspendDDO> queryEventsByTsCode(String tsCode, String startDate, String endDate);
}
