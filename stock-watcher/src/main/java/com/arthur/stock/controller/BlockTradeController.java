package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.BlockTradePremiumVO;
import com.arthur.stock.dto.BlockTradeWithCloseVO;
import com.arthur.stock.service.BlockTradeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 大宗交易查询接口
 */
@Tag(name = "大宗交易", description = "大宗交易数据查询与折溢价分布")
@RestController
@RequestMapping("/api/block-trade")
@RequiredArgsConstructor
public class BlockTradeController {

    private final BlockTradeService blockTradeService;

    @Operation(summary = "分页查询大宗交易", description = "按交易日分页查询大宗交易，JOIN daily_quote 返回收盘价，支持按成交额/成交价/成交量排序")
    @GetMapping
    public ApiResponse<List<BlockTradeWithCloseVO>> query(
            @Parameter(description = "交易日 yyyyMMdd", required = true)
            @RequestParam String tradeDate,
            @Parameter(description = "页码，从1开始")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页条数，最大100")
            @RequestParam(defaultValue = "20") int size,
            @Parameter(description = "排序字段：amount / price / vol")
            @RequestParam(defaultValue = "amount") String sortBy,
            @Parameter(description = "排序方向：asc / desc")
            @RequestParam(defaultValue = "desc") String order) {
        if (tradeDate == null || tradeDate.isBlank()) {
            return ApiResponse.success(Collections.emptyList());
        }
        if (page < 1) {
            page = 1;
        }
        if (size < 1) {
            size = 20;
        }
        if (size > 100) {
            size = 100;
        }
        List<BlockTradeWithCloseVO> list = blockTradeService.queryPage(tradeDate, page, size, sortBy, order);
        return ApiResponse.success(list);
    }

    @Operation(summary = "大宗交易折溢价分布", description = "按交易日统计大宗交易成交价相对收盘价的折溢价率分布（8个区间）")
    @GetMapping("/premium-distribution")
    public ApiResponse<List<BlockTradePremiumVO>> premiumDistribution(
            @Parameter(description = "交易日 yyyyMMdd", required = true)
            @RequestParam String tradeDate) {
        if (tradeDate == null || tradeDate.isBlank()) {
            return ApiResponse.success(Collections.emptyList());
        }
        return ApiResponse.success(blockTradeService.queryPremiumDistribution(tradeDate));
    }
}
