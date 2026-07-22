package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.PageResult;
import com.arthur.stock.dto.StockListQueryDTO;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import com.arthur.stock.vo.MarketTemperatureVO;
import com.arthur.stock.vo.StockListDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "市场行情", description = "大盘指数、市场涨跌排行等行情数据")
@RestController
@RequestMapping("/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @Operation(summary = "获取大盘指数", description = "获取上证指数、深证成指、创业板指等主要大盘指数实时行情")
    @GetMapping("/indices")
    public ApiResponse<List<MarketIndexVO>> getMarketIndices() {
        return ApiResponse.success(marketService.getMarketIndices());
    }

    @Operation(summary = "获取市场涨跌排行", description = "获取涨幅榜、跌幅榜、成交额榜、换手率榜等市场排名数据")
    @GetMapping("/ranking")
    public ApiResponse<MarketRankingVO> getMarketRanking() {
        return ApiResponse.success(marketService.getMarketRanking());
    }

    @Operation(summary = "获取市场温度", description = "获取市场涨/跌/平家数与涨停/跌停数；tradeDate 不传则取最新交易日")
    @GetMapping("/temperature")
    public ApiResponse<MarketTemperatureVO> getMarketTemperature(
            @RequestParam(required = false) String tradeDate) {
        return ApiResponse.success(marketService.getMarketTemperature(tradeDate));
    }

    @Operation(summary = "获取全市场股票列表",
            description = "行情中心股票列表：JOIN daily_quote + daily_basic + stock_basic + sw_industry_member，"
                    + "返回 13 列。支持 rankType 预设排序、industryCode 行业过滤、market 市场过滤、sortBy/order 覆盖排序、page/size 分页（size 上限 500）。")
    @GetMapping("/stock-list")
    public ApiResponse<PageResult<StockListDTO>> getStockList(StockListQueryDTO query) {
        return ApiResponse.success(marketService.getStockList(query));
    }
}
