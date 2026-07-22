package com.arthur.stock.service;

import com.arthur.stock.model.MoneyflowDO;

import java.util.List;

/**
 * 个股资金流向数据服务：主力净流入排行、单股资金流向明细等 stock_moneyflow 表查询。
 */
public interface MoneyflowService {

    /**
     * 从 Tushare 拉取指定交易日的个股资金流向数据并保存。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 保存的记录数
     */
    int fetchAndSave(String tradeDate);

    /**
     * TOP N 主力净流入排行。
     *
     * @param tradeDate 交易日 yyyyMMdd；为 null 时取最新交易日
     * @param limit     返回条数
     * @param sortBy    排序字段（net_mf_amount / net_mf_vol）
     * @param order     排序方向（asc / desc）
     */
    List<MoneyflowDO> queryTop(String tradeDate, int limit, String sortBy, String order);

    /**
     * 单股近 N 日资金流向明细。
     *
     * @param tsCode 股票代码，如 000001.SZ
     * @param days   天数
     */
    List<MoneyflowDO> queryDetail(String tsCode, int days);

    /**
     * 取 stock_moneyflow 表中最新的交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
