package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.StkHoldernumberDTO;
import com.arthur.stock.service.StkHoldernumberService;
import com.arthur.stock.util.StockQueryValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "股东人数", description = "个股股东人数变化查询")
@RestController
@RequestMapping("/api/stk-holdernumber")
@RequiredArgsConstructor
public class StkHoldernumberController {

    private final StkHoldernumberService stkHoldernumberService;

    @Operation(summary = "查询最近股东人数", description = "按报告期倒序返回指定股票最近 N 期股东人数")
    @GetMapping
    public ApiResponse<List<StkHoldernumberDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ", required = true)
            @RequestParam String tsCode,
            @Parameter(description = "最近期数，1-500")
            @RequestParam(defaultValue = "20") int limit) {
        String symbol = StockQueryValidator.requireStockCode(tsCode);
        int effectiveLimit = StockQueryValidator.validateLimit(limit);
        return ApiResponse.success(stkHoldernumberService.queryRecent(symbol, effectiveLimit));
    }
}
