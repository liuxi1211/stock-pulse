package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.AdjFactorDTO;
import com.arthur.stock.service.AdjFactorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tushare-复权因子", description = "复权因子数据查询与数据初始化")
@RestController
@RequestMapping("/tushare/adj-factor")
@RequiredArgsConstructor
public class AdjFactorController {

    private final AdjFactorService adjFactorService;

    @Operation(summary = "查询复权因子", description = "传 tradeDate 查某日全市场复权因子，传 tsCode+startDate+endDate 查单只股票")
    @GetMapping
    public ApiResponse<List<AdjFactorDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ") @RequestParam(required = false) String tsCode,
            @Parameter(description = "交易日期，格式 yyyyMMdd") @RequestParam(required = false) String tradeDate,
            @Parameter(description = "开始日期，格式 yyyyMMdd") @RequestParam(required = false) String startDate,
            @Parameter(description = "结束日期，格式 yyyyMMdd") @RequestParam(required = false) String endDate) {
        if (tradeDate != null && !tradeDate.isEmpty()) {
            return ApiResponse.success(adjFactorService.queryByTradeDate(tradeDate));
        }
        return ApiResponse.success(adjFactorService.queryByCodeAndDateRange(tsCode, startDate, endDate));
    }

    @Operation(summary = "初始化复权因子数据", description = "从 Tushare 全量拉取指定股票的复权因子并存入本地数据库")
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<AdjFactorDTO>> initAdjFactor(
            @Parameter(description = "股票代码，如 000001.SZ", required = true) @PathVariable String tsCode) {
        List<AdjFactorDTO> factors = adjFactorService.fetchAndSaveAdjFactor(tsCode);
        return ApiResponse.success("Fetched " + factors.size() + " records for " + tsCode, factors);
    }
}
