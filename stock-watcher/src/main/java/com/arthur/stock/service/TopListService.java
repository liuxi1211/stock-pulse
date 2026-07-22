package com.arthur.stock.service;

import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;

import java.util.List;

/**
 * 龙虎榜数据服务：管理 top_list（个股明细）和 top_inst（营业部席位明细）两张表。
 */
public interface TopListService {

    /**
     * 从 Tushare 拉取指定交易日的龙虎榜个股明细并落库。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 保存条数
     */
    int fetchAndSaveTopList(String tradeDate);

    /**
     * 从 Tushare 拉取指定交易日的龙虎榜营业部席位明细并落库。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @return 保存条数
     */
    int fetchAndSaveTopInst(String tradeDate);

    /**
     * 查询某交易日的龙虎榜个股列表（按净额降序）。
     *
     * @param tradeDate 交易日 yyyyMMdd；为 null 时取最新交易日
     */
    List<TopListDO> queryList(String tradeDate);

    /**
     * 查询某股票某交易日的营业部席位明细。
     *
     * @param tradeDate 交易日 yyyyMMdd
     * @param tsCode    股票代码
     */
    List<TopInstDO> queryInst(String tradeDate, String tsCode);

    /**
     * 查询某交易日的知名游资席位明细（按关键词模糊匹配）。
     *
     * @param tradeDate 交易日 yyyyMMdd
     */
    List<TopInstDO> queryNotable(String tradeDate);

    /**
     * 取 top_list 表中最新的交易日。
     *
     * @return 最新交易日 yyyyMMdd；表为空时返回 null
     */
    String getLatestTradeDate();
}
