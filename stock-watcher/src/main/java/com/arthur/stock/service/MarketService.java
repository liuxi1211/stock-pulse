package com.arthur.stock.service;

import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;

import java.util.List;

/**
 * 市场行情服务接口
 */
public interface MarketService {

    /**
     * 获取大盘指数列表
     */
    List<MarketIndexVO> getMarketIndices();

    /**
     * 获取市场排行数据（涨幅、跌幅、成交额 TOP 10）
     */
    MarketRankingVO getMarketRanking();
}