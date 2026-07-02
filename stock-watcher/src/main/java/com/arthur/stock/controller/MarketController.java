package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
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

    @Operation(summary = "获取市场涨跌排行", description = "获取涨幅榜、跌幅榜、换手率榜等市场排名数据")
    @GetMapping("/ranking")
    public ApiResponse<MarketRankingVO> getMarketRanking() {
        return ApiResponse.success(marketService.getMarketRanking());
    }
}