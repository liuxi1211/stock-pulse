package com.arthur.stock.controller;

import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.WatchlistService;
import com.arthur.stock.vo.StockVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 自选股控制器
 */
@RestController
@RequestMapping("/api/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @GetMapping
    public ApiResponse<List<StockVO>> getWatchlist() {
        return ApiResponse.success(watchlistService.getWatchlist(UserContext.getUserId()));
    }

    @PostMapping("/{stockCode}")
    public ApiResponse<Void> addToWatchlist(@PathVariable String stockCode) {
        watchlistService.addToWatchlist(UserContext.getUserId(), stockCode);
        return ApiResponse.success("已添加到自选股", null);
    }

    @DeleteMapping("/{stockCode}")
    public ApiResponse<Void> removeFromWatchlist(@PathVariable String stockCode) {
        watchlistService.removeFromWatchlist(UserContext.getUserId(), stockCode);
        return ApiResponse.success("已从自选股移除", null);
    }
}