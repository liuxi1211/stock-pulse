package com.arthur.stock.service;

import com.arthur.stock.dto.BlockTradePremiumVO;
import com.arthur.stock.dto.BlockTradeWithCloseVO;

import java.util.List;

/**
 * 大宗交易服务：拉取落库 + 分页查询 + 折溢价分布。
 */
public interface BlockTradeService {

    /**
     * 从 Tushare 拉取某交易日大宗交易并落库（按主键幂等 delete-then-insert）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 落库记录数
     */
    int fetchAndSave(String tradeDate);

    /**
     * 分页查询大宗交易（JOIN daily_quote 取收盘价）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @param page      页码（从 1 开始）
     * @param size      每页条数
     * @param sortBy    排序字段：amount / price / vol
     * @param order     排序方向：asc / desc
     */
    List<BlockTradeWithCloseVO> queryPage(String tradeDate, int page, int size, String sortBy, String order);

    /**
     * 按交易日统计记录数（用于分页总数）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     */
    int countPage(String tradeDate);

    /**
     * 查询某交易日大宗交易折溢价率分布。
     * <p>
     * 折溢价 = (成交价 - 收盘价) / 收盘价 * 100，按 8 个区间统计笔数。
     *
     * @param tradeDate 交易日 yyyyMMdd
     */
    List<BlockTradePremiumVO> queryPremiumDistribution(String tradeDate);

    /**
     * 取 block_trade 表中最新交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
