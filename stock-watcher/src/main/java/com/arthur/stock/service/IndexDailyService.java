package com.arthur.stock.service;

import com.arthur.stock.model.IndexDailyDO;

import java.util.List;

/**
 * 指数日线行情查询服务。
 * <p>
 * 仅负责本地 index_daily 表的读取查询；数据抓取与定时同步由
 * {@link IndexDailyFetchService} 负责。
 */
public interface IndexDailyService {

    /**
     * 取给定指数代码的最新交易日行情。
     * <p>
     * 实现先查 index_daily 表中的 MAX(trade_date)，再按该日期取这些指数的行情。
     *
     * @param codes 指数代码列表（如 000001.SH）
     * @return 最新交易日的指数行情列表；表为空或代码无数据时返回空列表
     */
    List<IndexDailyDO> getLatestByCodes(List<String> codes);

    /**
     * 按多指数代码 + 指定交易日查询。
     */
    List<IndexDailyDO> getByCodesAndTradeDate(List<String> codes, String tradeDate);

    /**
     * 取单个指数的近 N 个交易日行情（按 trade_date DESC）。
     *
     * @param tsCode 指数代码
     * @param limit  返回条数
     */
    List<IndexDailyDO> getByCodeOrderByTradeDate(String tsCode, int limit);
}
