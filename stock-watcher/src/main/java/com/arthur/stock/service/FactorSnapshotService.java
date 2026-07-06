package com.arthur.stock.service;

import com.arthur.stock.model.FactorSnapshotDO;

import java.util.List;
import java.util.Map;

/**
 * 因子预计算服务：每日收盘后对白名单内的技术面因子批量预计算并落库 factor_snapshot。
 * <p>
 * 选股链路当前仍走 engine 实时算（fundamentals 已补齐）；本表为 Phase 2 性能优化预留，
 * 后续可让 buildOneCandidate 优先查本表，未命中再回退 engine。
 */
public interface FactorSnapshotService {

    /**
     * 对最新交易日的全市场股票，预计算白名单内全部 (factorKey, params, outputIndex) 因子并入库。
     *
     * @return 入库行数
     */
    int computeForLatestTradeDate();

    /**
     * 查某交易日某股票的全部预计算因子（factor_snapshot 行）。
     */
    List<FactorSnapshotDO> queryByDateAndCode(String tradeDate, String tsCode);

    /**
     * 批量查某交易日多只股票的预计算因子，按 (ts_code, factor_key, params_json, output_index) 索引。
     *
     * @return key = ts_code + "|" + factorKey + "|" + paramsJson + "|" + outputIndex
     */
    Map<String, java.math.BigDecimal> queryByDateAndCodes(String tradeDate, List<String> codes);
}
