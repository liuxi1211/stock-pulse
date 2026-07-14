package com.arthur.stock.service;

import java.util.List;

/**
 * 指数成分���权重服务。
 * <p>
 * 负责从 tushare index_weight 接口拉取成分股快照并落库，
 * 提供实时选股（最新快照）与回测（按日期取历史快照，防幸存者偏差）两类查询。
 */
public interface IndexWeightService {

    /**
     * 拉取指定指数在指定交易日的成分股快照并落库（幂等：同主键覆盖）。
     *
     * @param indexCode 指数代码，如 000300.SH
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSave(String indexCode, String tradeDate);

    /**
     * 按日期区间拉取指定指数的成分股快照并落库（历史回补用）。
     *
     * @param indexCode 指数代码
     * @param startDate 起始日期 yyyyMMdd
     * @param endDate   结束日期 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSaveRange(String indexCode, String startDate, String endDate);

    /**
     * 取该指数最新交易日的成分股代码列表（实时选股用）。
     */
    List<String> getLatestConstituents(String indexCode);

    /**
     * 取该指数 ≤ 指定日期的最新快照的成分股代码列表（回测防幸存者偏差用）。
     */
    List<String> getConstituentsAt(String indexCode, String tradeDate);

    /**
     * 取该指数在指定日期区间内所有曾入选的成分股代码并集（回测防幸存者偏差）。
     * startDate/endDate 为 null 时不加该侧边界（取全部历史）。
     */
    List<String> getConstituentsInRange(String indexCode, String startDate, String endDate);
}
