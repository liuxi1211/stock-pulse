package com.arthur.stock.service;

import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import com.arthur.stock.vo.MarketTemperatureVO;

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
     * 获取市场排行数据（涨幅、跌幅、成交额、换手率 TOP 10）
     */
    MarketRankingVO getMarketRanking();

    /**
     * 获取市场温度（涨/跌/平/涨停/跌停家数）。
     *
     * @param tradeDate 交易日 yyyyMMdd；为 null 时取 daily_quote 最新交易日
     */
    MarketTemperatureVO getMarketTemperature(String tradeDate);
}
