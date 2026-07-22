package com.arthur.stock.controller;

import com.arthur.stock.context.UserContext;
import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.WatchlistBatchRequest;
import com.arthur.stock.dto.WatchlistReminderRequest;
import com.arthur.stock.service.WatchlistService;
import com.arthur.stock.vo.WatchlistItemVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "自选股管理", description = "用户自选股的增删查、分组、提醒、批量操作")
@RestController
@RequestMapping("/watchlist")
@RequiredArgsConstructor
public class WatchlistController {

    private final WatchlistService watchlistService;

    @Operation(summary = "获取自选股列表", description = "获取当前登录用户的自选股列表，支持按分组过滤和排序")
    @GetMapping
    public ApiResponse<List<WatchlistItemVO>> getWatchlist(
            @Parameter(description = "分组ID，不传则返回全部")
            @RequestParam(value = "groupId", required = false) Long groupId,
            @Parameter(description = "排序字段：pct_chg / created_at / name / sort_order，默认 pct_chg")
            @RequestParam(value = "sortBy", defaultValue = "pct_chg") String sortBy,
            @Parameter(description = "排序方向：asc / desc，默认 desc")
            @RequestParam(value = "order", defaultValue = "desc") String order) {
        return ApiResponse.success(watchlistService.getWatchlist(UserContext.getUserId(), groupId, sortBy, order));
    }

    @Operation(summary = "添加自选股", description = "将指定股票添加到当前用户的自选股列表中，可指定分组")
    @PostMapping("/{stockCode}")
    public ApiResponse<Void> addToWatchlist(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode,
            @Parameter(description = "分组ID，不传则为未分组")
            @RequestParam(value = "groupId", required = false) Long groupId) {
        watchlistService.addToWatchlist(UserContext.getUserId(), stockCode, groupId);
        return ApiResponse.success("已添加到自选股", null);
    }

    @Operation(summary = "移除自选股", description = "将指定股票从当前用户的自选股列表中移除")
    @PostMapping("/{stockCode}/delete")
    public ApiResponse<Void> removeFromWatchlist(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode) {
        watchlistService.removeFromWatchlist(UserContext.getUserId(), stockCode);
        return ApiResponse.success("已从自选股移除", null);
    }

    @Operation(summary = "设置价格提醒", description = "为指定自选股设置价格上下限提醒，突破时触发通知")
    @PostMapping("/{stockCode}/reminder")
    public ApiResponse<Void> setReminder(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode,
            @RequestBody WatchlistReminderRequest request) {
        watchlistService.setReminder(UserContext.getUserId(), stockCode,
                request.getTargetPriceHigh(), request.getTargetPriceLow());
        return ApiResponse.success("提醒设置成功", null);
    }

    @Operation(summary = "清除价格提醒", description = "清除指定自选股的价格提醒设置")
    @DeleteMapping("/{stockCode}/reminder")
    public ApiResponse<Void> clearReminder(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode) {
        watchlistService.clearReminder(UserContext.getUserId(), stockCode);
        return ApiResponse.success("提醒已清除", null);
    }

    @Operation(summary = "更新排序序号", description = "更新指定自选股的手动排序序号")
    @PutMapping("/{stockCode}/sort")
    public ApiResponse<Void> updateSortOrder(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode,
            @Parameter(description = "排序序号", required = true)
            @RequestParam Integer sortOrder) {
        watchlistService.updateSortOrder(UserContext.getUserId(), stockCode, sortOrder);
        return ApiResponse.success("排序已更新", null);
    }

    @Operation(summary = "更新所属分组", description = "将指定自选股移动到指定分组，传 null 表示取消分组")
    @PutMapping("/{stockCode}/group")
    public ApiResponse<Void> updateGroup(
            @Parameter(description = "股票代码", required = true)
            @PathVariable String stockCode,
            @Parameter(description = "分组ID，传 null 表示未分组")
            @RequestParam(required = false) Long groupId) {
        watchlistService.updateGroup(UserContext.getUserId(), stockCode, groupId);
        return ApiResponse.success("分组已更新", null);
    }

    @Operation(summary = "批量移除自选股", description = "批量将多只股票从自选股列表中移除")
    @PostMapping("/batch-delete")
    public ApiResponse<Void> batchRemove(@RequestBody WatchlistBatchRequest request) {
        watchlistService.batchRemove(UserContext.getUserId(), request.getStockCodes());
        return ApiResponse.success("批量移除成功", null);
    }

    @Operation(summary = "批量移动分组", description = "批量将多只自选股移动到指定分组")
    @PostMapping("/batch-move-group")
    public ApiResponse<Void> batchMoveGroup(@RequestBody WatchlistBatchRequest request) {
        watchlistService.batchMoveGroup(UserContext.getUserId(), request.getStockCodes(), request.getGroupId());
        return ApiResponse.success("批量移动成功", null);
    }
}
