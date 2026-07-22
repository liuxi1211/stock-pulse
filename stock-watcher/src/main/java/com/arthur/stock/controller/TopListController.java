package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.TopInstDO;
import com.arthur.stock.model.TopListDO;
import com.arthur.stock.service.TopListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 龙虎榜查询接口
 */
@Tag(name = "龙虎榜", description = "龙虎榜个股明细与营业部席位查询")
@RestController
@RequestMapping("/api/top-list")
@RequiredArgsConstructor
public class TopListController {

    private final TopListService topListService;

    @Operation(summary = "当日龙虎榜个股列表", description = "按交易日查询龙虎榜个股明细，按净额降序返回；tradeDate 为空时取最新交易日")
    @GetMapping
    public ApiResponse<List<TopListDO>> list(
            @Parameter(description = "交易日 yyyyMMdd，为空时取最新")
            @RequestParam(required = false) String tradeDate) {
        return ApiResponse.success(topListService.queryList(tradeDate));
    }

    @Operation(summary = "营业部席位明细", description = "按交易日和股票代码查询龙虎榜营业部席位明细")
    @GetMapping("/inst")
    public ApiResponse<List<TopInstDO>> inst(
            @Parameter(description = "交易日 yyyyMMdd", required = true)
            @RequestParam String tradeDate,
            @Parameter(description = "股票代码", required = true)
            @RequestParam String tsCode) {
        return ApiResponse.success(topListService.queryInst(tradeDate, tsCode));
    }

    @Operation(summary = "知名游资汇总", description = "按交易日查询知名游资/机构席位明细；tradeDate 为空时取最新交易日")
    @GetMapping("/inst/notable")
    public ApiResponse<List<TopInstDO>> notableInst(
            @Parameter(description = "交易日 yyyyMMdd，为空时取最新")
            @RequestParam(required = false) String tradeDate) {
        return ApiResponse.success(topListService.queryNotable(tradeDate));
    }
}
