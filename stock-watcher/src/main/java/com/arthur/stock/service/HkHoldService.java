package com.arthur.stock.service;

import com.arthur.stock.dto.HkHoldTrendVO;
import com.arthur.stock.model.HkHoldDO;

import java.util.List;

/**
 * 沪深港通持股明细数据服务。
 */
public interface HkHoldService {

    /**
     * 从 Tushare 拉取指定交易日的沪深港通持股明细并落库。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSave(String tradeDate);

    /**
     * 查询持股占比趋势（按交易日汇总 SUM(ratio)）。
     *
     * @param days       最近天数（从今天往前推）
     * @param exchangeId 交易所代码 SH/SZ，为 "ALL"/null 时不分交易所
     */
    List<HkHoldTrendVO> queryRatioTrend(int days, String exchangeId);

    /**
     * 查询某交易日持股数量 Top-N。
     *
     * @param tradeDate  交易日 yyyyMMdd，为 null 时取最新交易日
     * @param exchangeId 交易所代码 SH/SZ，为 "ALL"/null 时不分交易所
     * @param limit      返回条数
     */
    List<HkHoldDO> queryTopHoldings(String tradeDate, String exchangeId, int limit);

    /**
     * 查某股票最近 N 天的持股明细。
     *
     * @param tsCode 股票代码，如 000001.SZ
     * @param days   最近天数
     */
    List<HkHoldDO> queryDetail(String tsCode, int days);

    /**
     * 取 hk_hold 表中最新的交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
