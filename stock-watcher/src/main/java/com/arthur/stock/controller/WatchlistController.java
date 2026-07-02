package com.arthur.stock.controller;

import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.service.WatchlistService;
import com.arthur.stock.vo.StockVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "自选股管理", description = "用户自选股的增删查操作")
@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @Operation(summary = "获取自选股列表", description = "获取当前登录用户的自选股列表")
    @GetMapping
    public ApiResponse<List<StockVO>> getWatchlist() {
        return ApiResponse.success(watchlistService.getWatchlist(UserContext.getUserId()));
    }

    @Operation(summary = "添加自选股", description = "将指定股票添加到当前用户的自选股列表中")
    @PostMapping("/{stockCode}")
    public ApiResponse<Void> addToWatchlist(
            @Parameter(description = "股票代码", required = true) @PathVariable String stockCode) {
        watchlistService.addToWatchlist(UserContext.getUserId(), stockCode);
        return ApiResponse.success("已添加到自选股", null);
    }

    @Operation(summary = "移除自选股", description = "将指定股票从当前用户的自选股列表中移除")
    @DeleteMapping("/{stockCode}")
    public ApiResponse<Void> removeFromWatchlist(
            @Parameter(description = "股票代码", required = true) @PathVariable String stockCode) {
        watchlistService.removeFromWatchlist(UserContext.getUserId(), stockCode);
        return ApiResponse.success("已从自选股移除", null);
    }
}