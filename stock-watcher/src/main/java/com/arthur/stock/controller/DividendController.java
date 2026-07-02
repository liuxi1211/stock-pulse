package com.arthur.stock.controller;

import com.arthur.stock.dto.ApiResponse;
import com.arthur.stock.dto.tushare.DividendDTO;
import com.arthur.stock.service.DividendService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Tushare-分红送股", description = "分红送股数据查询与数据初始化")
@RestController
@RequestMapping("/tushare/dividend")
@RequiredArgsConstructor
public class DividendController {

    private final DividendService dividendService;

    @Operation(summary = "查询分红送股数据", description = "根据股票代码查询分红送股历史记录")
    @GetMapping
    public ApiResponse<List<DividendDTO>> query(
            @Parameter(description = "股票代码，如 000001.SZ") @RequestParam(required = false) String tsCode) {
        return ApiResponse.success(dividendService.queryByTsCode(tsCode));
    }

    @Operation(summary = "初始化分红送股数据", description = "从 Tushare 全量拉取指定股票的分红送股数据并存入本地数据库")
    @PostMapping("/init/{tsCode}")
    public ApiResponse<List<DividendDTO>> initDividend(
            @Parameter(description = "股票代码，如 000001.SZ", required = true) @PathVariable String tsCode) {
        List<DividendDTO> dividends = dividendService.fetchAndSaveDividend(tsCode);
        return ApiResponse.success("Fetched " + dividends.size() + " records for " + tsCode, dividends);
    }
}
