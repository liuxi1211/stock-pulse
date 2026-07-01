package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.MarketService;
import com.arthur.stock.vo.MarketIndexVO;
import com.arthur.stock.vo.MarketRankingVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 市场行情控制器
 */
@RestController
@RequestMapping("/api/market")
@RequiredArgsConstructor
public class MarketController {

    private final MarketService marketService;

    @GetMapping("/indices")
    public ApiResponse<List<MarketIndexVO>> getMarketIndices() {
        return ApiResponse.success(marketService.getMarketIndices());
    }

    @GetMapping("/ranking")
    public ApiResponse<MarketRankingVO> getMarketRanking() {
        return ApiResponse.success(marketService.getMarketRanking());
    }
}