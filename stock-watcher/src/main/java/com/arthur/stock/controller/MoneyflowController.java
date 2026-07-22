package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.model.MoneyflowDO;
import com.arthur.stock.service.MoneyflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "资金流向-个股", description = "个股资金流向数据查询")
@RestController
@RequestMapping("/api/moneyflow")
@RequiredArgsConstructor
public class MoneyflowController {

    private final MoneyflowService moneyflowService;

    @Operation(summary = "主力净流入TOP排行")
    @GetMapping("/top")
    public ApiResponse<List<MoneyflowDO>> top(
            @Parameter(description = "交易日期 yyyyMMdd") @RequestParam(required = false) String tradeDate,
            @Parameter(description = "返回条数") @RequestParam(defaultValue = "50") int limit,
            @Parameter(description = "排序字段") @RequestParam(defaultValue = "net_mf_amount") String sortBy,
            @Parameter(description = "排序方向") @RequestParam(defaultValue = "desc") String order) {
        if (limit > 200) {
            limit = 200;
        }
        return ApiResponse.success(moneyflowService.queryTop(tradeDate, limit, sortBy, order));
    }

    @Operation(summary = "单股资金流向明细")
    @GetMapping("/detail")
    public ApiResponse<List<MoneyflowDO>> detail(
            @Parameter(description = "股票代码") @RequestParam String tsCode,
            @Parameter(description = "天数") @RequestParam(defaultValue = "30") int days) {
        return ApiResponse.success(moneyflowService.queryDetail(tsCode, days));
    }
}
