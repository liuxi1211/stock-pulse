package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.HkHoldTrendVO;
import com.arthur.stock.model.HkHoldDO;
import com.arthur.stock.service.HkHoldService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 沪深港通持股明细查询接口
 */
@Tag(name = "沪深港通持股", description = "沪深港通持股明细与占比趋势查询")
@RestController
@RequestMapping("/api/hk-hold")
@RequiredArgsConstructor
public class HkHoldController {

    private static final int MAX_LIMIT = 50;

    private final HkHoldService hkHoldService;

    @Operation(summary = "持股占比趋势", description = "按交易日汇总 SUM(ratio)，支持按交易所 SH/SZ/ALL 过滤")
    @GetMapping("/ratio-trend")
    public ApiResponse<List<HkHoldTrendVO>> ratioTrend(
            @Parameter(description = "最近天数，默认30")
            @RequestParam(defaultValue = "30") int days,
            @Parameter(description = "交易所代码：SH/SZ/ALL，默认ALL")
            @RequestParam(defaultValue = "ALL") String exchangeId) {
        return ApiResponse.success(hkHoldService.queryRatioTrend(days, exchangeId));
    }

    @Operation(summary = "持股数量 Top-N", description = "查询某交易日持股数量最多的标的")
    @GetMapping("/top-holdings")
    public ApiResponse<List<HkHoldDO>> topHoldings(
            @Parameter(description = "交易日 yyyyMMdd，为空时取最新交易日")
            @RequestParam(required = false) String tradeDate,
            @Parameter(description = "交易所代码：SH/SZ/ALL，默认ALL")
            @RequestParam(defaultValue = "ALL") String exchangeId,
            @Parameter(description = "返回条数，默认10，上限50")
            @RequestParam(defaultValue = "10") int limit) {
        int effectiveLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);
        return ApiResponse.success(hkHoldService.queryTopHoldings(tradeDate, exchangeId, effectiveLimit));
    }

    @Operation(summary = "个股持股明细", description = "查某股票最近N天的沪深港通持股明细")
    @GetMapping("/detail")
    public ApiResponse<List<HkHoldDO>> detail(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @RequestParam String tsCode,
            @Parameter(description = "最近天数，默认30")
            @RequestParam(defaultValue = "30") int days) {
        if (tsCode == null || tsCode.isBlank()) {
            return ApiResponse.success(List.of());
        }
        return ApiResponse.success(hkHoldService.queryDetail(tsCode, days));
    }
}
