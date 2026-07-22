package com.arthur.stock.service;

import com.arthur.stock.model.MarginDO;
import com.arthur.stock.model.MarginDetailDO;

import java.util.List;

/**
 * 融资融券数据服务。
 */
public interface MarginService {

    /**
     * 拉取并保存某交易日的融资融券汇总数据。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @return 保存条数
     */
    int fetchAndSaveMargin(String tradeDate);

    /**
     * 拉取并保存某交易日的融资融券个股明细数据。
     *
     * @param tradeDate 交易日期 yyyyMMdd
     * @return 保存条数
     */
    int fetchAndSaveMarginDetail(String tradeDate);

    /**
     * 查询最近 N 天的融资融券汇总趋势。
     *
     * @param days       天数
     * @param exchangeId 交易所代码（SSE/SZSE），"ALL" 或 null 表示不过滤
     * @return 汇总数据列表，按 trade_date 升序
     */
    List<MarginDO> queryTrend(int days, String exchangeId);

    /**
     * 查询某交易日融资融券个股明细 Top-N。
     *
     * @param tradeDate 交易日期 yyyyMMdd，null 时取最新
     * @param limit    返回条数
     * @param sortBy   排序字段（rzrqye/rzye/rqye/rzmre）
     * @param order    排序方向（asc/desc）
     * @return 明细数据列表
     */
    List<MarginDetailDO> queryDetailTop(String tradeDate, int limit, String sortBy, String order);

    /**
     * 获取最新交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
