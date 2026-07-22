package com.arthur.stock.service;

import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.StockListQueryDTO;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import com.arthur.stock.vo.MarketTemperatureVO;
import com.arthur.stock.vo.StockListDTO;

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

    /**
     * 获取全市场股票列表（行情中心 stock-list 页，13 列 JOIN 查询）。
     * <p>
     * 支持按榜单预设（rankType）/行业（industryCode）/市场（market）过滤，排序可由 sortBy 覆盖，
     * 分页 page/size（size 上限 500）。tradeDate 取 daily_quote 最新交易日。
     *
     * @param query 查询参数
     * @return 分页股票列表
     */
    PageResult<StockListDTO> getStockList(StockListQueryDTO query);
}
